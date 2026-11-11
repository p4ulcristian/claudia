(ns claudia.web.extras
  "The page's side of agents, changes and meters: their frames, events and subscriptions.
  app-db gets :meta (server -> {:limits :models :commands :defaultModel}), :agent-view ([server chat] -> agent id,
  \"\" = the list), :agent-detail ([server chat agent] -> the runner's answer), :diffs ([server chat
  change] -> {:before :after}) and :slash-pick; each chat's state gets :agents :changes :settings
  :model :commands :context."
  (:require [re-frame.core :as rf]
            [claudia.web.db :as db]
            [claudia.web.events :as events]))

;; ---- frames ----

(defmethod events/on-frame "agents" [db server {:keys [chat agents]}]
  {:db (assoc-in db [:chats [server chat] :agents] agents)})

(defmethod events/on-frame "changes" [db server {:keys [chat changes]}]
  {:db (update-in db [:chats [server chat] :changes] (fn [cs] (into (vec cs) changes)))})

(defmethod events/on-frame "context" [db server {:keys [chat context]}]
  {:db (assoc-in db [:chats [server chat] :context] context)})

(defmethod events/on-frame "settings" [db server {:keys [chat settings model commands]}]
  {:db (update-in db [:chats [server chat]]
                  (fn [s] (cond-> (assoc s :settings settings)
                            model (assoc :model model)
                            commands (assoc :commands commands))))})

(defmethod events/on-frame "meta" [db server f]
  {:db (assoc-in db [:meta server] (select-keys f [:limits :limitsAt :models :commands :defaultModel :plans :planKey]))})

(defmethod events/on-frame "agent" [db server {:keys [chat agent] :as f}]
  {:db (assoc-in db [:agent-detail [server chat agent]] (dissoc f :type :chat :agent))})

(defmethod events/on-frame "diff" [db server {:keys [chat change] :as f}]
  {:db (assoc-in db [:diffs [server chat change]] (select-keys f [:before :after :skipped :error]))})

(defmethod events/on-frame "agent_git" [db server {:keys [chat agent rev] :as f}]
  {:db (assoc-in db [:agent-git [server chat agent rev]] (select-keys f [:patch :error]))})

;; ---- agents ----

(rf/reg-event-fx
 :agents/open
 ;; id "" = the list of every agent of the chat. On a phone a history entry, so back closes it.
 (fn [{:keys [db]} [_ k id]]
   (when (db/phone?)
     (js/history.pushState #js {:agent id} "" js/location.pathname))
   {:db (assoc-in db [:agent-view k] id)
    :fx [[:dispatch [:agents/fetch k id]]]}))

(rf/reg-event-fx
 :agents/close
 (fn [{:keys [db]} [_ k]]
   (if (and (db/phone?) (some? (some-> js/history.state .-agent)))
     (do (js/history.back) {})
     {:db (update db :agent-view dissoc k)})))

(rf/reg-event-db
 :agents/popstate
 (fn [db _]
   (let [k (:open db)
         id (some-> js/history.state .-agent)]
     (if (string? id)
       (assoc-in db [:agent-view k] id)
       (update db :agent-view dissoc k)))))

(defonce ^:private popstate-listener
  (js/window.addEventListener "popstate" #(rf/dispatch [:agents/popstate])))

(rf/reg-event-fx
 :agents/fetch
 (fn [_ [_ [s c] id]]
   (when (seq id) {:ws/send [s {:type "agent" :chat c :agent id}]})))

(rf/reg-event-fx
 :agents/git
 (fn [_ [_ [s c] id rev]]
   {:ws/send [s {:type "agent_git" :chat c :agent id :rev rev}]}))

(rf/reg-event-fx
 :agents/stop
 (fn [_ [_ [s c] task]]
   {:ws/send [s {:type "stop_task" :chat c :task task}]}))

;; ---- changes ----

(rf/reg-event-fx
 :changes/diff
 (fn [{:keys [db]} [_ [s c] change]]
   (when-not (get-in db [:diffs [s c change]])
     {:ws/send [s {:type "diff" :chat c :change change}]})))

(rf/reg-event-fx
 :changes/split
 (fn [{:keys [db]} [_ v]]
   {:db (assoc db :diff-split v) :store ["claudia.diffSplit" v]}))

;; ---- model, effort, compact ----

(rf/reg-event-fx
 :chat/settings
 (fn [{:keys [db]} [_ [s c :as k] m]]
   {:db (update-in db [:chats k :settings] merge m)
    :ws/send [s (merge {:type "settings" :chat c} m)]}))

(rf/reg-event-fx
 :chat/compact
 ;; The context ring's click: /compact in the open chat.
 (fn [{:keys [db]} [_ [s c :as k]]]
   (when (and c (not (get-in db [:chats k :running])))
     {:db (update-in db [:outbox k] (fnil conj []) "/compact")
      :ws/send [s {:type "send" :chat c :text "/compact"}]})))

(rf/reg-event-db
 :slash/pick
 (fn [db [_ i]] (assoc db :slash-pick i)))

;; ---- subscriptions ----

(rf/reg-sub :meta (fn [db [_ server]] (get-in db [:meta server])))

(defn limits-meta
  "The meta whose limits the page shows: the open chat's server's, else the first server's that has
  some, or nil."
  [db]
  (let [[s] (:open db)
        with-limits #(let [m (get-in db [:meta %])] (when (seq (:limits m)) m))]
    (or (with-limits s) (some #(with-limits (:name %)) (get-in db [:me :servers])))))

;; The plan's limits (the token in use's), as limits-meta picks them.
(rf/reg-sub :limits (fn [db] (or (:limits (limits-meta db)) [])))

;; The same with when they were heard ({:limits :at}), for the popup of every token's limits.
(rf/reg-sub :live-limits (fn [db] (when-let [m (limits-meta db)] {:limits (:limits m) :at (:limitsAt m)})))

(rf/reg-sub :agent-view (fn [db [_ k]] (get-in db [:agent-view k])))
(rf/reg-sub :agent-detail (fn [db [_ k id]] (get-in db [:agent-detail (conj (vec k) id)])))
(rf/reg-sub :agent-git (fn [db [_ k id rev]] (get-in db [:agent-git (conj (vec k) id rev)])))
(rf/reg-sub :diff (fn [db [_ k change]] (get-in db [:diffs (conj (vec k) change)])))
(rf/reg-sub :diff-split (fn [db] (boolean (:diff-split db (try (= "true" (js/localStorage.getItem "claudia.diffSplit")) (catch :default _ false))))))
(rf/reg-sub :slash-pick (fn [db] (or (:slash-pick db) 0)))
