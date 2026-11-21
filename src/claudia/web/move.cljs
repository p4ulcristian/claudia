(ns claudia.web.move
  "Moving a chat to another server (README decision 82), the page's part: the chat menu's Move to
  server… and the chat header's server pill open a confirm card (claudia.web.views.confirm/move-ask) with the person's other servers
  and the folder there; Move asks the hub (POST /hub/chat-move) and opens the chat where it went."
  (:require [re-frame.core :as rf]
            [claudia.web.format :as format]))

(defn other-servers
  "The person's servers (with their profile: me's servers) a chat on server s can move to."
  [servers s]
  (filterv #(not= s (:name %)) servers))

(defn ask-state
  "The card's first state for chat k ([server chat], its row): to (the header's server pill picked
  it, README decision 90) when it is one of the other servers, else the first other server; and the
  chat's folder written from the home folder (~/…), so it lands in the same place there by default."
  [[s _ :as k] row servers to]
  (let [others (map :name (other-servers servers s))]
    {:k k :to (or (some #{to} others) (first others))
     :folder (if (seq (:folder row)) (format/tilde (:folder row)) "")}))

(defn request-body
  "What the hub gets for the card's state: the chat, from and to, and the folder (blank: the target's
  working folder)."
  [{[s c] :k :keys [to folder]}]
  {:chat c :from s :to to :folder folder})

(rf/reg-event-db
 :move/ask
 ;; The card for chat k; to: the server it goes to (nil: the first other one).
 (fn [db [_ [s c :as k] to]]
   (assoc db :move-ask (ask-state k (get-in db [:servers s :rows c]) (get-in db [:me :servers]) to))))

(rf/reg-event-db :move/keep (fn [db _] (dissoc db :move-ask)))
(rf/reg-event-db :move/set (fn [db [_ field v]] (cond-> db (:move-ask db) (assoc-in [:move-ask field] v))))

(rf/reg-event-fx
 :move/confirm
 (fn [{:keys [db]} _]
   (when-let [m (:move-ask db)]
     (when-not (:busy m)
       {:db (update db :move-ask assoc :busy true :error nil)
        :api/call {:method "POST" :url "/hub/chat-move" :body (request-body m)
                   :on-ok [:move/done] :on-fail [:move/failed]}}))))

(rf/reg-event-fx
 :move/done
 (fn [{:keys [db]} [_ {:keys [server chat]}]]
   {:db (dissoc db :move-ask)
    :fx [[:dispatch [:nav/open [server chat]]]]}))

(rf/reg-event-db :move/failed (fn [db [_ e]] (cond-> db (:move-ask db) (update :move-ask assoc :busy false :error e))))

(rf/reg-sub
 :move-ask
 (fn [db _]
   (when-let [{[s c] :k :as m} (:move-ask db)]
     (assoc m :title (get-in db [:servers s :rows c :title])
            :servers (other-servers (get-in db [:me :servers]) s)))))

(rf/reg-sub
 :can-move?
 ;; The chat menu's Move to server…: the person's own chat (not a view, not a write tick), not
 ;; Claudia's, and another server with their profile to go to.
 (fn [db [_ [s c]]]
   (boolean (and (nil? (:view db))
                 (not (get-in db [:servers s :rows c :claudia]))
                 (seq (other-servers (get-in db [:me :servers]) s))))))
