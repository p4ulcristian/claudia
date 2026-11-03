(ns claudia.web.events
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [claudia.web.db :as db]
            [claudia.web.fx :as fx]))

;; ---- helpers ----

(defn- msg-index [msgs id]
  (first (keep-indexed (fn [i m] (when (= id (:id m)) i)) msgs)))

(defn- upsert-msg [msgs msg]
  (if-let [i (msg-index msgs (:id msg))]
    (assoc msgs i msg)
    (conj (or msgs []) msg)))

(defn- append-delta [msgs id text]
  (if-let [i (msg-index msgs id)]
    (update-in msgs [i :text] str text)
    (conj (or msgs []) {:id id :role "assistant" :text text :streaming true})))

(defn- drop-echo
  "The runner echoed a user message: take the oldest matching text off the outbox."
  [outbox text]
  (let [i (first (keep-indexed (fn [i t] (when (= (str/trim t) (str/trim (or text ""))) i)) outbox))]
    (if i (into (subvec outbox 0 i) (subvec outbox (inc i))) outbox)))

(defn- server-names [db] (mapv :name (get-in db [:me :servers])))

(defn- default-server [db]
  (let [names (server-names db)]
    (if (some #{(:new-server db)} names) (:new-server db) (first names))))

(def ^:private uuid-v4
  #(if (exists? js/crypto.randomUUID) (js/crypto.randomUUID) (str (random-uuid))))

;; ---- start ----

(rf/reg-event-fx
 :init
 (fn [_ _]
   (let [open (fx/parse-path js/location.pathname)]
     {:db (assoc db/initial :open open :screen (if open :chat :list))
      :http/get {:url "/hub/me" :on-ok [:me/loaded] :on-fail [:me/failed]}})))

(rf/reg-event-fx
 :me/loaded
 (fn [{:keys [db]} [_ me]]
   (let [names (mapv :name (:servers me))]
     {:db (-> db
              (assoc :me me :me-error nil)
              (assoc :servers (into {} (map (fn [n] [n {:status :connecting :rows {}}])) names)))
      :ws/connect names})))

(rf/reg-event-db
 :me/failed
 (fn [db [_ text]] (assoc db :me-error text)))

;; ---- sockets ----

(rf/reg-event-db
 :ws/status
 (fn [db [_ server status]] (assoc-in db [:servers server :status] status)))

(rf/reg-event-fx
 :ws/opened
 (fn [{:keys [db]} [_ server]]
   (let [[s c] (:open db)]
     {:db (assoc-in db [:servers server :status] :open)
      :ws/send-all (cond-> [[server {:type "hello"}]]
                     ;; After a reconnect the open chat is opened again (full state, live events).
                     (and (= s server) c) (conj [server {:type "open" :chat c}]))})))

(defmulti on-frame (fn [_db _server frame] (:type frame)))

(rf/reg-event-fx
 :ws/frame
 (fn [{:keys [db]} [_ server frame]]
   (on-frame db server frame)))

(defmethod on-frame :default [db _ _] {:db db})

(defmethod on-frame "chats" [db server {:keys [chats]}]
  {:db (assoc-in db [:servers server :rows] (into {} (map (juxt :id identity)) chats))
   :fx [[:dispatch [:chat/check-seen]]]})

(defmethod on-frame "chat" [db server {:keys [chat] :as f}]
  (let [k [server chat]
        msgs (vec (:messages f))]
    {:db (-> db
             (assoc-in [:chats k] (-> (select-keys f [:title :folder :scene :running :asks
                                                  :agents :changes :settings :model :commands :context])
                                      (assoc :messages msgs :loaded true)))
             (update-in [:outbox k] (fn [ob] (reduce drop-echo (vec ob) (keep #(when (= "user" (:role %)) (:text %)) msgs)))))
     :fx [[:dispatch [:chat/check-seen]]]}))

(defmethod on-frame "created" [db server {:keys [chat id]}]
  (if-let [{:keys [text]} (get-in db [:pending-new id])]
    (let [k [server chat]
          was-open? (= (:open db) [server id])]
      {:db (cond-> (-> db
                       (update :pending-new dissoc id)
                       (assoc-in [:outbox k] [text])
                       (update :outbox dissoc [server id]))
             was-open? (assoc :open k))
       :history/replace (if was-open? k (fx/parse-path js/location.pathname))
       :ws/send [server {:type "open" :chat chat}]})
    {:db db}))

(defmethod on-frame "msg" [db server {:keys [chat msg]}]
  (let [k [server chat]]
    {:db (cond-> (update-in db [:chats k :messages] upsert-msg msg)
           (= "user" (:role msg)) (update-in [:outbox k] #(drop-echo (vec %) (:text msg))))}))

(defmethod on-frame "delta" [db server {:keys [chat id text]}]
  {:db (update-in db [:chats [server chat] :messages] append-delta id text)})

(defmethod on-frame "scene" [db server {:keys [chat scene]}]
  {:db (assoc-in db [:chats [server chat] :scene] scene)})

(defmethod on-frame "turn" [db server {:keys [chat running]}]
  {:db (cond-> (assoc-in db [:chats [server chat] :running] running)
         ;; A turn's end settles every streamed message.
         (not running) (update-in [:chats [server chat] :messages]
                                  (fn [ms] (mapv #(dissoc % :streaming) ms))))
   :fx [[:dispatch [:chat/check-seen]]]})

(defmethod on-frame "ask" [db server {:keys [chat ask]}]
  {:db (update-in db [:chats [server chat] :asks]
                  (fn [asks] (conj (vec (remove #(= (:id %) (:id ask)) asks)) ask)))})

(defmethod on-frame "asked" [db server {:keys [chat ask]}]
  {:db (-> db
           (update-in [:chats [server chat] :asks] (fn [asks] (vec (remove #(= (:id %) ask) asks))))
           (update :ask-state dissoc ask))})

(defmethod on-frame "error" [db server {:keys [chat text]}]
  {:db (if chat
         (assoc-in db [:chats [server chat] :error] text)
         (assoc db :error (str server ": " text)))})

;; ---- navigation ----

(rf/reg-event-fx
 :nav/open
 (fn [{:keys [db]} [_ [server chat :as k] {:keys [history?] :or {history? true}}]]
   (let [[ps pc :as prev] (:open db)]
     (if (= prev k)
       {:db (assoc db :screen :chat)}
       {:db (-> db (assoc :open k :screen :chat) (assoc-in [:chats k :error] nil))
        :ws/send-all (cond-> []
                       (and pc (not= prev k)) (conj [ps {:type "close" :chat pc}])
                       chat (conj [server {:type "open" :chat chat}]))
        :fx (cond-> [[:dispatch [:chat/check-seen]]]
              history? (conj [:history/push k]))}))))

(rf/reg-event-fx
 :nav/new
 (fn [{:keys [db]} _]
   (let [[ps pc] (:open db)]
     {:db (assoc db :open nil :screen :chat)
      :ws/send-all (if pc [[ps {:type "close" :chat pc}]] [])
      :history/push nil})))

(rf/reg-event-fx
 :nav/list
 (fn [{:keys [db]} _]
   {:db (assoc db :screen :list)}))

(rf/reg-event-fx
 :nav/popstate
 (fn [{:keys [db]} _]
   (let [k (fx/parse-path js/location.pathname)]
     (if k
       {:fx [[:dispatch [:nav/open k {:history? false}]]]}
       {:db (assoc db :screen :list)}))))

(rf/reg-event-db
 :nav/screen
 (fn [db [_ screen]] (assoc db :screen screen)))

(rf/reg-event-fx
 :sidebar/toggle
 (fn [{:keys [db]} _]
   (let [v (not (:sidebar? db))]
     {:db (assoc db :sidebar? v) :store ["claudia.sidebar" v]})))

(rf/reg-event-fx
 :new/server
 (fn [{:keys [db]} [_ server]]
   {:db (assoc db :new-server server) :store ["claudia.newServer" server]}))

(rf/reg-event-db
 :error/dismiss
 (fn [db [_ k]] (if k (assoc-in db [:chats k :error] nil) (assoc db :error nil))))

;; ---- talking ----

(rf/reg-event-db
 :draft/set
 (fn [db [_ k text]] (assoc-in db [:drafts k] text)))

(rf/reg-event-fx
 :composer/send
 (fn [{:keys [db]} [_ text]]
   (let [text (str/trim (or text ""))
         [server chat :as k] (:open db)]
     (cond
       (str/blank? text) {}
       ;; A new chat still on its way: the text waits in the composer until `created`.
       (contains? (:pending-new db) chat) {}
       chat
       {:db (-> db (update-in [:outbox k] (fnil conj []) text) (assoc-in [:drafts k] ""))
        :ws/send [server {:type "send" :chat chat :text text}]}
       :else
       (let [server (default-server db)
             id (uuid-v4)]
         (when server
           {:db (-> db
                    (assoc-in [:pending-new id] {:server server :text text})
                    (assoc :open [server id])
                    (assoc-in [:outbox [server id]] [text])
                    (assoc-in [:drafts :new] "")
                    (assoc :new-server server))
            :store ["claudia.newServer" server]
            :ws/send [server {:type "new" :id id :text text}]}))))))

(defn- on-open-chat [db f]
  (let [[server chat] (:open db)]
    (if (and chat (not (contains? (:pending-new db) chat)))
      (f server chat)
      {})))

(rf/reg-event-fx
 :chat/stop
 (fn [{:keys [db]} _]
   (on-open-chat db (fn [s c] {:ws/send [s {:type "stop" :chat c}]}))))

(rf/reg-event-fx
 :chat/rename
 (fn [_ [_ [s c] title]]
   (when-not (str/blank? title)
     {:ws/send [s {:type "rename" :chat c :title (str/trim title)}]})))

(rf/reg-event-fx
 :chat/archive
 (fn [{:keys [db]} [_ [s c :as k] archived]]
   (cond-> {:ws/send [s {:type "archive" :chat c :archived archived}]}
     (and archived (= k (:open db))) (assoc :fx [[:dispatch [:nav/new]]]))))

(defn- visible? [] (= "visible" js/document.visibilityState))

(rf/reg-event-fx
 :chat/check-seen
 ;; `seen` once the open chat's last turn is on screen: the chat is shown, its turn has ended and
 ;; its row still says unseen.
 (fn [{:keys [db]} _]
   (let [[s c :as k] (:open db)
         row (get-in db [:servers s :rows c])
         shown? (or (not (db/phone?)) (not= :list (:screen db)))]
     (if (and c row (:unseen row) (visible?) shown?
              (get-in db [:chats k :loaded])
              (not (get-in db [:chats k :running]))
              (not= (:at row) (get-in db [:seen-sent k])))
       {:db (assoc-in db [:seen-sent k] (:at row))
        :ws/send [s {:type "seen" :chat c}]}
       {}))))

;; ---- asks ----

(rf/reg-event-db
 :ask/state
 (fn [db [_ id state]] (assoc-in db [:ask-state id] state)))

(rf/reg-event-fx
 :ask/answer
 (fn [{:keys [db]} [_ [s c] ask-id answers]]
   {:db (assoc-in db [:ask-state ask-id] {:busy true :sent true})
    :ws/send [s {:type "answer" :chat c :ask ask-id :answers answers}]}))

(rf/reg-event-fx
 :ask/deny
 (fn [{:keys [db]} [_ [s c] ask-id]]
   {:db (assoc-in db [:ask-state ask-id] {:busy true :sent true})
    :ws/send [s {:type "deny" :chat c :ask ask-id}]}))

(rf/reg-event-fx
 :ask/allow
 (fn [{:keys [db]} [_ [s c] ask]]
   {:db (assoc-in db [:ask-state (:id ask)] {:busy true})
    :secret/read {:server s :chat c :ask ask}}))

(rf/reg-event-fx
 :ask/paste
 (fn [{:keys [db]} [_ [s c] ask text]]
   {:db (assoc-in db [:ask-state (:id ask)] {:busy true})
    :secret/seal {:server s :chat c :ask ask :text text}}))
