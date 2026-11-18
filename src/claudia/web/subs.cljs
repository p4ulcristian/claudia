(ns claudia.web.subs
  (:require [re-frame.core :as rf]
            [claudia.web.events :as events]
            [claudia.web.views.pure.dictated :as dictated]
            [claudia.web.views.pure.sidebar :as sidebar-pure]
            [claudia.web.views.pure.switches :as switches]))

(rf/reg-sub :me (fn [db] (:me db)))
(rf/reg-sub :me-error (fn [db] (:me-error db)))
(rf/reg-sub :servers (fn [db] (:servers db)))
(rf/reg-sub :open (fn [db] (:open db)))
(rf/reg-sub :screen (fn [db] (:screen db)))
(rf/reg-sub :search-panel? (fn [db] (:search-panel? db)))
(rf/reg-sub :turn-since (fn [db [_ k]] (get-in db [:turn-since k])))
(rf/reg-sub :error (fn [db] (:error db)))
(rf/reg-sub :pending-new (fn [db] (:pending-new db)))
(rf/reg-sub :ask-state (fn [db [_ id]] (get-in db [:ask-state id])))
(rf/reg-sub :draft (fn [db [_ k]] (get-in db [:drafts k] "")))
;; The dictated piece in draft k whose clean and raw words differ (the composer's chip), or nil.
(rf/reg-sub :dictated-swap (fn [db [_ k]] (dictated/swappable (get-in db [:dictated k]) (get-in db [:drafts k]))))
(rf/reg-sub :talk? (fn [db [_ k]] (switches/talk-on? db k)))   ; k nil: the new chat
(rf/reg-sub :bell? (fn [db [_ k]] (switches/bell-on? db k)))
(rf/reg-sub :archive-ask   ; the chat whose archiving waits on a yes: {:k :title}, or nil
            (fn [db _] (when-let [[s c :as k] (:archive-ask db)]
                         {:k k :title (get-in db [:servers s :rows c :title])})))

(rf/reg-sub
 :server-list
 :<- [:me]
 (fn [me] (:servers me)))

(rf/reg-sub
 :all-server-list
 :<- [:me]
 (fn [me] (into (vec (:servers me)) (:viewServers me))))

(rf/reg-sub
 :multi-server?
 :<- [:server-list]
 (fn [servers] (> (count servers) 1)))

(rf/reg-sub
 :new-server
 (fn [db] (events/default-server db)))

(rf/reg-sub
 :server-label
 :<- [:me]
 (fn [me [_ server]]
   (or (:label (first (filter #(= server (:name %)) (concat (:servers me) (:viewServers me))))) server)))

(rf/reg-sub :archived-mode? (fn [db] (boolean (:archived? db))))
(rf/reg-sub :hidden-server-set (fn [db] (set (:hidden-servers db))))

(defn- in-list?
  "Whether row r shows in the list: archived ones only in the archived list (archived? true), save the
  open chat (open: [server chat]), which the chats list keeps while it is open; every chat for
  archived? :any."
  [r archived? open]
  (or (= :any archived?)
      (= archived? (boolean (:archived r)))
      (and (false? archived?) (= open [(:server r) (:id r)]))))

(defn list-rows
  "Every shown server's chats in one list, each with its :server and :off (its socket not open),
  those in-list? only. In the order the chats were started (the TypeScript Claudia's list): a new chat
  sits last."
  [servers archived? hidden & [open]]
  (->> servers
       (remove (fn [[s]] (hidden s)))
       (mapcat (fn [[s {:keys [rows status]}]]
                 (map #(assoc % :server s :off (not= :open status)) (vals rows))))
       (filter #(in-list? % archived? open))
       (sort-by events/list-order)))

(rf/reg-sub
 :rows
 :<- [:servers]
 :<- [:archived-mode?]
 :<- [:hidden-server-set]
 :<- [:open]
 (fn [[servers archived? hidden open]] (list-rows servers archived? hidden open)))

(rf/reg-sub
 :chat-rows
 ;; The chats, never the archived ones: the rail keeps them while the chat column shows the
 ;; archived chats (README decision 24).
 :<- [:servers]
 :<- [:hidden-server-set]
 :<- [:open]
 (fn [[servers hidden open]] (list-rows servers false hidden open)))

(rf/reg-sub
 :all-rows
 :<- [:servers]
 :<- [:hidden-server-set]
 (fn [[servers hidden]] (list-rows servers :any hidden)))

(rf/reg-sub
 :row
 :<- [:servers]
 (fn [servers [_ [s c]]] (get-in servers [s :rows c])))

(rf/reg-sub :offline (fn [db _] (boolean (:offline db))))

(defn shown-status
  "A server's socket status as the views show it: :offline while the device has no network and the
  socket is not open (not the server's fault, so not \"off\")."
  [status offline?]
  (if (and offline? (not= :open status)) :offline status))

(rf/reg-sub
 :server-status
 :<- [:servers]
 :<- [:offline]
 (fn [[servers offline?] [_ s]] (shown-status (get-in servers [s :status]) offline?)))

(rf/reg-sub
 :link
 ;; What the strip over the composer shows of a server's connection (claudia.web.views.pure.chat).
 :<- [:servers]
 :<- [:offline]
 (fn [[servers offline?] [_ s]]
   (let [{:keys [status back down-since]} (get servers s)] [status back offline? (some? down-since)])))

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
 :fresh-chat
 ;; The chat being started, shown on the rail before the list has it (views.pure.sidebar/fresh-chat).
 (fn [db]
   (sidebar-pure/fresh-chat (assoc (select-keys db [:open :pending-new :servers :new-settings :new-server])
                                   :ro (events/read-only? db (events/default-server db))))))

(rf/reg-sub
 :open-color
 ;; The colour the window wears: the open chat's; a new chat's (it has one from the start); every
 ;; screen wears a chat colour, there is no default look (README decision 25).
 (fn [db]
   (let [[s c] (:open db)]
     (or (get-in db [:servers s :rows c :color]) (get-in db [:new-settings :color])))))

