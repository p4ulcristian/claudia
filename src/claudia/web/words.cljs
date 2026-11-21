(ns claudia.web.words
  "My words: the person's own names and terms for the listen service (claudia.hub.listen-words),
  read and saved through /hub/listen/words, edited in Settings (views.my-words). Claude offers a
  word it saw misheard as a card in the chat (README decision 95); the person's choices about those
  cards (the words they said No to, the words they added from the chat, whether cards show) are
  their prefs, kept by the hub (/hub/listen/prefs, claudia.hub.listen-prefs) for every device."
  (:require [claudia.web.views.pure.dictated :as dictated]
            [re-frame.core :as rf]))

(def ^:private words-url "/hub/listen/words")
(def ^:private prefs-url "/hub/listen/prefs")

(rf/reg-event-fx
 :words/load
 ;; Settings, the edge or a card opened: whether the hub has a listen service, then the list.
 (fn [_ _] {:fx [[:api/call {:url "/hub/listen" :on-ok [:words/listen]}]]}))

(rf/reg-event-fx
 :words/listen
 (fn [{:keys [db]} [_ {:keys [listen]}]]
   (if listen
     {:db (update db :my-words #(dissoc (or % {}) :off))
      :fx [[:api/call {:url words-url :on-ok [:words/loaded] :on-fail [:words/failed]}]
           [:api/call {:url prefs-url :on-ok [:words/prefs-loaded] :on-fail [:words/failed]}]]}
     {:db (assoc db :my-words {:off true})})))

(rf/reg-event-db :words/loaded (fn [db [_ {:keys [words]}]] (assoc db :my-words {:words (vec words)})))

(rf/reg-event-db :words/prefs-loaded (fn [db [_ prefs]] (assoc db :listen-prefs prefs)))

(rf/reg-event-db :words/failed (fn [db [_ error]] (update db :my-words #(-> % (dissoc :busy) (assoc :error error)))))

(rf/reg-event-fx
 :words/save
 ;; The whole list, as it should be now.
 (fn [{:keys [db]} [_ words]]
   {:db (update db :my-words #(-> % (assoc :busy true) (dissoc :error)))
    :fx [[:api/call {:method "PUT" :url words-url :body {:words words}
                     :on-ok [:words/loaded] :on-fail [:words/failed]}]]}))

(rf/reg-event-fx
 :words/prefs-save
 ;; The whole prefs, as they should be now (shown at once, then as the hub kept them).
 (fn [{:keys [db]} [_ prefs]]
   {:db (assoc db :listen-prefs prefs)
    :fx [[:api/call {:method "PUT" :url prefs-url :body prefs
                     :on-ok [:words/prefs-loaded] :on-fail [:words/failed]}]]}))

(defn- prefs-of [db] (merge {:declined [] :learned [] :offers true} (:listen-prefs db)))

(rf/reg-event-fx
 :words/card-add
 ;; Add on a card: the word joins My words, marked as learned from the chat.
 (fn [{:keys [db]} [_ w]]
   {:fx [[:dispatch [:words/save (dictated/with-word (get-in db [:my-words :words]) w)]]
         [:dispatch [:words/prefs-save (update (prefs-of db) :learned dictated/with-word w)]]]}))

(rf/reg-event-fx
 :words/card-no
 ;; No on a card: the word is never offered again.
 (fn [{:keys [db]} [_ w]]
   {:fx [[:dispatch [:words/prefs-save (update (prefs-of db) :declined dictated/with-word w)]]]}))

(rf/reg-event-fx
 :words/remove
 ;; × on a word in Settings: out of the list and out of the learned marks.
 (fn [{:keys [db]} [_ w]]
   {:fx [[:dispatch [:words/save (dictated/without-word (get-in db [:my-words :words]) w)]]
         [:dispatch [:words/prefs-save (update (prefs-of db) :learned dictated/without-word w)]]]}))

(rf/reg-event-fx
 :words/offers
 (fn [{:keys [db]} [_ on?]] {:fx [[:dispatch [:words/prefs-save (assoc (prefs-of db) :offers on?)]]]}))

(rf/reg-event-fx
 :words/offer-again
 (fn [{:keys [db]} _] {:fx [[:dispatch [:words/prefs-save (assoc (prefs-of db) :declined [])]]]}))

(rf/reg-event-fx
 :words/reset
 ;; Reset dictation profile: no words, nothing declined or learned; the offers switch stays.
 (fn [{:keys [db]} _]
   {:fx [[:dispatch [:words/save []]]
         [:dispatch [:words/prefs-save (assoc (prefs-of db) :declined [] :learned [])]]]}))

(rf/reg-sub :my-words (fn [db] (:my-words db)))
(rf/reg-sub :listen-prefs (fn [db] (prefs-of db)))
