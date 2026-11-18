(ns claudia.web.bookmarks
  "The person's bookmarked folders on the page (README decision 61): loaded from the hub (GET
  /hub/bookmarks) at start and whenever the page comes back to the front, changed by Pin/Unpin
  (claudia.shared.bookmarks), kept at once on the page and sent whole (PUT), so every device shows
  the same list. The Folders screen and the chat header's project list show them."
  (:require [re-frame.core :as rf]
            [claudia.shared.bookmarks :as bm]
            [claudia.web.api :as api]))

(rf/reg-event-fx :bookmarks/load (fn [_ _] {:bookmarks/get nil}))

(rf/reg-fx
 :bookmarks/get
 (fn [_]
   (-> (api/fetch-json "/hub/bookmarks")
       (.then #(rf/dispatch [:bookmarks/loaded %]))
       (.catch (fn [_] nil)))))

(rf/reg-event-db
 :bookmarks/loaded
 ;; The hub's list, unless a change made here is still on its way (it would be newer).
 (fn [db [_ {:keys [items]}]]
   (if (get-in db [:bookmarks :saving])
     db
     (assoc db :bookmarks {:items (bm/clean-items items)}))))

(rf/reg-event-fx
 :bookmarks/toggle
 ;; Pin or unpin folder on server.
 (fn [{:keys [db]} [_ server folder]]
   (let [items (bm/toggled (get-in db [:bookmarks :items] []) server folder)]
     {:db (assoc db :bookmarks {:items items :saving true})
      :bookmarks/put items})))

(rf/reg-fx
 :bookmarks/put
 (fn [items]
   (-> (api/call-json "PUT" "/hub/bookmarks" {:items items})
       (.then #(rf/dispatch [:bookmarks/saved %]))
       (.catch (fn [_] (rf/dispatch [:bookmarks/saved nil]))))))

(rf/reg-event-db
 :bookmarks/saved
 (fn [db [_ answer]]
   (cond-> (assoc-in db [:bookmarks :saving] false)
     answer (assoc-in [:bookmarks :items] (bm/clean-items (:items answer))))))

(rf/reg-sub :bookmarks (fn [db] (get-in db [:bookmarks :items] [])))

(defonce ^:private started (atom false))

(defn start!
  "Loads the list now and whenever the page comes back to the front."
  []
  (when-not @started
    (reset! started true)
    (rf/dispatch [:bookmarks/load])
    (js/document.addEventListener "visibilitychange" #(when (= "visible" js/document.visibilityState) (rf/dispatch [:bookmarks/load])))))
