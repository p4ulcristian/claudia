(ns claudia.web.order
  "The person's chat order and groups on the page (README decision 38): loaded from the hub
  (GET /hub/chat-order), changed by the person's drags (claudia.shared.order), kept at once on the
  page and sent whole (PUT), and asked again every 20 s and when the page comes back to the front,
  so every device shows the same list. Only for the person's own chats: another person's chats
  (a view) keep their plain order."
  (:require [re-frame.core :as rf]
            [claudia.shared.order :as order]
            [claudia.web.api :as api]
            [claudia.web.events :as events]
            [claudia.web.subs :as subs]))

(def ^:private refresh-ms 20000)

(rf/reg-event-fx :order/load (fn [_ _] {:order/get nil}))

(rf/reg-fx
 :order/get
 (fn [_]
   (-> (api/fetch-json "/hub/chat-order")
       (.then #(rf/dispatch [:order/loaded %]))
       (.catch (fn [_] nil)))))

(rf/reg-event-db
 :order/loaded
 ;; The hub's list, unless a change made here is still on its way (it would be newer).
 (fn [db [_ {:keys [items at]}]]
   (if (:saving (:order db))
     db
     (assoc db :order {:items (order/clean-items items) :at at :loaded true}))))

(rf/reg-fx
 :order/put
 (fn [items]
   (-> (api/call-json "PUT" "/hub/chat-order" {:items items})
       (.then #(rf/dispatch [:order/saved %]))
       (.catch (fn [_] (rf/dispatch [:order/saved nil]))))))

(rf/reg-event-db
 :order/saved
 (fn [db [_ answer]]
   (cond-> (assoc-in db [:order :saving] false)
     answer (assoc-in [:order :at] (:at answer)))))

(defn- shown-rows
  "The person's chats the list shows now (as the :rows sub has them)."
  [db]
  (subs/list-rows (:servers db) false (set (:hidden-servers db)) (:open db)))

(defn changed
  "db with the person's list changed by f (items -> items), the chats not placed yet first settled
  where they are seen (claudia.shared.order/settle); and the effect that keeps it on the hub."
  [db f]
  (let [items (-> (get-in db [:order :items] [])
                  (order/settle (shown-rows db) events/list-order)
                  f
                  order/clean-items)]
    {:db (update db :order assoc :items items :saving true)
     :order/put items}))

;; f: items -> items (a claudia.shared.order function with its arguments).
(rf/reg-event-fx :order/change (fn [{:keys [db]} [_ f]] (changed db f)))

(defn- group-server
  "The server a new chat in group g starts on: the group's last pick, else its first chat's."
  [db g]
  (or (get-in g [:picks :server])
      (first (order/chat-key (first (:chats g))))
      (:new-server db)))

(rf/reg-event-fx
 :order/new-in-group
 ;; A new chat in group gid: it starts with the group's picks (else those of its first chat), never
 ;; a folder (README decision 65), and, once made, goes on top of the group (:order/created).
 (fn [{:keys [db]} [_ gid]]
   (let [g (order/find-group (get-in db [:order :items]) gid)
         picks (or (not-empty (dissoc (:picks g) :server :folder))
                   (events/carried-picks db (order/chat-key (first (:chats g)))))]
     (update (events/new-chat db picks (group-server db g)) :db assoc :new-group gid))))

(rf/reg-event-fx
 :order/created
 ;; The chat made from a group's + (README decision 38): on top of the group, whose picks become
 ;; the ones it started with (the next + keeps the flow).
 (fn [{:keys [db]} [_ cref gid picks]]
   (changed db #(-> (order/into-group % cref gid (first (:chats (order/find-group % gid))))
                    (order/update-group gid (fn [g] (assoc g :picks picks)))))))

(rf/reg-sub :order-items (fn [db] (get-in db [:order :items] [])))

;; The group whose name is being typed (a new group asks at once), or nil.
(rf/reg-event-db :order/renaming (fn [db [_ gid]] (assoc db :renaming-group gid)))
(rf/reg-sub :renaming-group (fn [db] (:renaming-group db)))

(rf/reg-sub
 :arranged
 ;; The list in the person's order, with groups (claudia.shared.order/arrange); a view of another
 ;; person's chats stays in its plain order. Never the archived chats (:chat-rows).
 :<- [:chat-rows]
 :<- [:order-items]
 :<- [:viewing-other?]
 (fn [[rows items viewing?]]
   (if viewing?
     (mapv (fn [r] {:kind :chat :row r}) rows)
     (order/arrange rows items events/list-order))))

(defonce ^:private started (atom false))

(defn start!
  "Loads the list now, then every refresh-ms and whenever the page comes back to the front."
  []
  (when-not @started
    (reset! started true)
    (rf/dispatch [:order/load])
    (js/setInterval #(when (= "visible" js/document.visibilityState) (rf/dispatch [:order/load])) refresh-ms)
    (js/document.addEventListener "visibilitychange" #(when (= "visible" js/document.visibilityState) (rf/dispatch [:order/load])))))
