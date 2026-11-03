(ns claudia.web.subs
  (:require [re-frame.core :as rf]
            [claudia.web.events :as events]))

(rf/reg-sub :me (fn [db] (:me db)))
(rf/reg-sub :me-error (fn [db] (:me-error db)))
(rf/reg-sub :servers (fn [db] (:servers db)))
(rf/reg-sub :open (fn [db] (:open db)))
(rf/reg-sub :screen (fn [db] (:screen db)))
(rf/reg-sub :sidebar? (fn [db] (:sidebar? db)))
(rf/reg-sub :rail-search? (fn [db] (:rail-search? db)))
(rf/reg-sub :turn-since (fn [db [_ k]] (get-in db [:turn-since k])))
(rf/reg-sub :error (fn [db] (:error db)))
(rf/reg-sub :pending-new (fn [db] (:pending-new db)))
(rf/reg-sub :ask-state (fn [db [_ id]] (get-in db [:ask-state id])))
(rf/reg-sub :draft (fn [db [_ k]] (get-in db [:drafts k] "")))

(rf/reg-sub
 :server-list
 :<- [:me]
 (fn [me] (:servers me)))

(rf/reg-sub
 :multi-server?
 :<- [:server-list]
 (fn [servers] (> (count servers) 1)))

(rf/reg-sub
 :new-server
 (fn [db] (events/default-server db)))

(rf/reg-sub
 :server-label
 :<- [:server-list]
 (fn [servers [_ server]]
   (or (:label (first (filter #(= server (:name %)) servers))) server)))

(rf/reg-sub :archived-mode? (fn [db] (boolean (:archived? db))))
(rf/reg-sub :hidden-server-set (fn [db] (set (:hidden-servers db))))

(defn list-rows
  "Every shown server's chats in one list, each with its :server and :off (its socket not open);
  archived ones only in the archived list (archived? true), every chat for archived? :any. In the order the chats were started (the TypeScript
  Claudia's list): a new chat sits last."
  [servers archived? hidden]
  (->> servers
       (remove (fn [[s]] (hidden s)))
       (mapcat (fn [[s {:keys [rows status]}]]
                 (map #(assoc % :server s :off (not= :open status)) (vals rows))))
       (filter #(or (= :any archived?) (= archived? (boolean (:archived %)))))
       (sort-by events/list-order)))

(rf/reg-sub
 :rows
 :<- [:servers]
 :<- [:archived-mode?]
 :<- [:hidden-server-set]
 (fn [[servers archived? hidden]] (list-rows servers archived? hidden)))

(rf/reg-sub
 :all-rows
 :<- [:servers]
 :<- [:hidden-server-set]
 (fn [[servers hidden]] (list-rows servers :any hidden)))

(rf/reg-sub
 :row
 :<- [:servers]
 (fn [servers [_ [s c]]] (get-in servers [s :rows c])))

(rf/reg-sub
 :server-status
 :<- [:servers]
 (fn [servers [_ s]] (get-in servers [s :status])))

(rf/reg-sub
 :chat
 (fn [db [_ k]] (get-in db [:chats k])))

(rf/reg-sub
 :outbox
 (fn [db [_ k]] (get-in db [:outbox k])))

(rf/reg-sub
 :open-chat
 ;; The open chat as the views need it: its state, row and whether it is a new one still on its way.
 (fn [db]
   (let [[s c :as k] (:open db)
         pending (get-in db [:pending-new c])]
     (when k
       {:ck k
        :server s
        :chat c
        :pending? (boolean pending)
        :row (get-in db [:servers s :rows c])
        :state (get-in db [:chats k])
        :outbox (get-in db [:outbox k])
        :status (get-in db [:servers s :status])}))))

(rf/reg-sub
 :open-color
 :<- [:open-chat]
 (fn [oc] (get-in oc [:row :color])))

(rf/reg-sub
 :waiting-count
 ;; Chats that wait on the person (an open question), for the phone's back button.
 :<- [:rows]
 (fn [rows] (count (filter :waiting rows))))
