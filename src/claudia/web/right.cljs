(ns claudia.web.right
  "What the right half shows (README decision 91): the Drawing board, with a pile of sheets on it
  (claudia.shared.views), opened by the buttons that open a view (the header's Files button, the edge's
  rail, ⌘K, the menu's items) and closed by a sheet's ×. The pile is the person's: loaded
  from the hub (GET /hub/right-view) at start and whenever the page comes back to the front, kept at
  once on the page and sent (PUT), so every device of theirs shows the same pile."
  (:require [re-frame.core :as rf]
            [claudia.shared.views :as views]
            [claudia.web.api :as api]
            [claudia.web.db :as db]))

(rf/reg-sub :right-sheets (fn [db] (or (:right-sheets db) [])))

(rf/reg-sub :right-view :<- [:right-sheets] (fn [pile] (views/top pile)))

(defn pile-fx
  "The effects that make the right half show pile, opened: the view just opened (nil when one
  closed): kept for the person; Files loads the bookmarks again (an agent may have changed them,
  README decision 67); a sheet opened closes the world; on a phone it brings the right half up."
  [db pile opened phone?]
  (let [pile (views/clean-pile pile)]
    (cond-> {:db (cond-> (assoc db :right-sheets pile :right-saving true) opened (dissoc :world?))
             :right/put pile}
      (= opened :files) (assoc :bookmarks/get nil)
      (and opened phone?) (assoc-in [:db :screen] :picture))))

(defn- sheet-opened "v when it opens a sheet (not the board), else nil." [v]
  (let [v (views/clean v)] (when (not= v :picture) v)))

(defn show-view "v opened on top ([:right/view v]); the Drawing board takes every sheet off." [db v phone?]
  (pile-fx db (views/open (:right-sheets db) v) (sheet-opened v) phone?))

(defn toggle-view "v closed when it is the top sheet, else opened on top ([:right/toggle v])." [db v phone?]
  (let [pile (:right-sheets db)
        closing? (= (views/clean v) (views/top pile))]
    (pile-fx db (views/toggle pile v) (when-not closing? (sheet-opened v)) phone?)))

(defn close-view "v's sheet taken off ([:right/close v])." [db v]
  (pile-fx db (views/close (:right-sheets db) v) nil false))

(rf/reg-event-fx :right/view (fn [{:keys [db]} [_ v]] (show-view db v (db/phone?))))

(rf/reg-event-fx :right/toggle (fn [{:keys [db]} [_ v]] (toggle-view db v (db/phone?))))

(rf/reg-event-fx :right/close (fn [{:keys [db]} [_ v]] (close-view db v)))

(rf/reg-fx
 :right/put
 (fn [pile]
   (-> (api/call-json "PUT" "/hub/right-view" (views/to-wire pile))
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
 ;; The hub's pile, unless one made here is still on its way (it would be newer).
 (fn [db [_ body]]
   (if (:right-saving db) db (assoc db :right-sheets (views/from-wire body)))))

(defonce ^:private started (atom false))

(defn start!
  "Loads the pile now and whenever the page comes back to the front."
  []
  (when-not @started
    (reset! started true)
    (rf/dispatch [:right/load])
    (js/document.addEventListener "visibilitychange" #(when (= "visible" js/document.visibilityState) (rf/dispatch [:right/load])))))
