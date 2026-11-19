(ns claudia.web.right
  "What the right half shows (README decisions 70 and 75): one of claudia.shared.views, picked in
  its dropdown (claudia.web.views.right-pick) or opened by the buttons that open a view (the
  header's place pill, the edge's search, the cog, …). The pick is the person's: loaded from the hub
  (GET /hub/right-view) at start and whenever the page comes back to the front, kept at once on the
  page and sent (PUT), so every device of theirs shows the same view."
  (:require [re-frame.core :as rf]
            [claudia.shared.views :as views]
            [claudia.web.api :as api]
            [claudia.web.db :as db]))

(rf/reg-sub :right-view (fn [db] (or (:right-view db) :picture)))

(defn show-view
  "The effects that make the right half show v: kept for the person; Files loads the bookmarks again
  (an agent may have changed them, README decision 67); any view but the Drawing board closes the
  world; on a phone the right half comes up."
  [db v phone?]
  (let [v (views/clean v)]
    (cond-> {:db (cond-> (assoc db :right-view v :right-saving true) (not= v :picture) (dissoc :world?))
             :right/put v}
      (= v :files) (assoc :bookmarks/get nil)
      (and (not= v :picture) phone?) (assoc-in [:db :screen] :picture))))

(rf/reg-event-fx :right/view (fn [{:keys [db]} [_ v]] (show-view db v (db/phone?))))

(rf/reg-event-fx
 :right/toggle
 ;; v, or the Drawing board when v already shows (the place pill and the edge's buttons).
 (fn [{:keys [db]} [_ v]]
   (show-view db (if (= v (or (:right-view db) :picture)) :picture v) (db/phone?))))

(rf/reg-fx
 :right/put
 (fn [v]
   (-> (api/call-json "PUT" "/hub/right-view" {:view (name v)})
       (.then #(rf/dispatch [:right/saved]))
       (.catch (fn [_] (rf/dispatch [:right/saved]))))))

(rf/reg-event-db :right/saved (fn [db _] (dissoc db :right-saving)))

(rf/reg-fx
 :right/get
 (fn [_]
   (-> (api/fetch-json "/hub/right-view")
       (.then #(rf/dispatch [:right/loaded %]))
       (.catch (fn [_] nil)))))

(rf/reg-event-fx :right/load (fn [_ _] {:right/get nil}))

(rf/reg-event-db
 :right/loaded
 ;; The hub's pick, unless one made here is still on its way (it would be newer).
 (fn [db [_ {:keys [view]}]]
   (if (:right-saving db) db (assoc db :right-view (views/clean view)))))

(defonce ^:private started (atom false))

(defn start!
  "Loads the pick now and whenever the page comes back to the front."
  []
  (when-not @started
    (reset! started true)
    (rf/dispatch [:right/load])
    (js/document.addEventListener "visibilitychange" #(when (= "visible" js/document.visibilityState) (rf/dispatch [:right/load])))))
