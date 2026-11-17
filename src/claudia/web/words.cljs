(ns claudia.web.words
  "My words: the person's own names and terms for the listen service (claudia.hub.listen-words),
  read and saved through /hub/listen/words, edited in Settings (views.my-words). A word the person
  corrected in dictated text before sending is offered once (never again on this device, whatever
  the answer), and only when the list does not hold it."
  (:require [claudia.web.views.pure.dictated :as dictated]
            [clojure.string :as str]
            [re-frame.core :as rf]))

(def ^:private words-url "/hub/listen/words")

(rf/reg-event-fx
 :words/load
 ;; Settings opened: whether the hub has a listen service, then the list.
 (fn [_ _] {:fx [[:api/call {:url "/hub/listen" :on-ok [:words/listen]}]]}))

(rf/reg-event-fx
 :words/listen
 (fn [{:keys [db]} [_ {:keys [listen]}]]
   (if listen
     {:db (update db :my-words #(dissoc (or % {}) :off))
      :fx [[:api/call {:url words-url :on-ok [:words/loaded] :on-fail [:words/failed]}]]}
     {:db (assoc db :my-words {:off true})})))

(rf/reg-event-db :words/loaded (fn [db [_ {:keys [words]}]] (assoc db :my-words {:words (vec words)})))

(rf/reg-event-db :words/failed (fn [db [_ error]] (update db :my-words #(-> % (dissoc :busy) (assoc :error error)))))

(rf/reg-event-fx
 :words/save
 ;; The whole list, as it should be now.
 (fn [{:keys [db]} [_ words]]
   {:db (update db :my-words #(-> % (assoc :busy true) (dissoc :error)))
    :fx [[:api/call {:method "PUT" :url words-url :body {:words words}
                     :on-ok [:words/loaded] :on-fail [:words/failed]}]]}))

(rf/reg-event-fx
 :words/maybe-offer
 ;; A word corrected before sending: offered when it was never offered here and the list lacks it.
 (fn [{:keys [db]} [_ w]]
   (let [k (str/lower-case w)
         offered (set (:words-offered db))]
     (when-not (contains? offered k)
       {:db (assoc db :words-offered (conj offered k))
        :store ["claudia.wordsOffered" (clj->js (vec (conj offered k)))]
        :fx [[:api/call {:url words-url :on-ok [:words/offer-if-new w]}]]}))))

(rf/reg-event-db
 :words/offer-if-new
 (fn [db [_ w {:keys [words]}]]
   (cond-> (assoc db :my-words {:words (vec words)})
     (not (dictated/has-word? words w)) (assoc :words-offer w))))

(rf/reg-event-fx
 :words/accept
 (fn [{:keys [db]} _]
   (when-let [w (:words-offer db)]
     {:db (dissoc db :words-offer)
      :fx [[:dispatch [:words/save (dictated/with-word (get-in db [:my-words :words]) w)]]]})))

(rf/reg-event-db :words/dismiss (fn [db _] (dissoc db :words-offer)))

(rf/reg-sub :my-words (fn [db] (:my-words db)))
(rf/reg-sub :words-offer (fn [db] (:words-offer db)))
