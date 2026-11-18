(ns claudia.web.folders
  "The Folders screen's place in the page (README decisions 61 to 64): opened from the profile menu
  on the rail's foot (the list's foot on a phone), over the chats until Escape or ×; a new chat
  started there, or a chat opened there, closes it. The fetches of the runner's /api/folders… calls
  (PROTOCOL.md, \"Runner HTTP: folders\")."
  (:require [re-frame.core :as rf]
            [claudia.web.api :as api]
            [claudia.web.events :as events]))

(rf/reg-event-db :folders/open (fn [db [_ open?]] (cond-> (assoc db :folders? (boolean open?)) open? (dissoc :world?))))

(rf/reg-sub :folders? (fn [db] (boolean (:folders? db))))

(rf/reg-event-fx
 :folders/new-chat
 ;; A new chat on server working in folder, with the picks it would carry otherwise.
 (fn [{:keys [db]} [_ server folder]]
   (-> (events/new-chat db (assoc (events/carried-picks db (:open db)) :folder folder) server)
       (update :db dissoc :folders?))))

(rf/reg-event-fx
 :folders/open-chat
 (fn [{:keys [db]} [_ k]]
   {:db (dissoc db :folders?) :fx [[:dispatch [:nav/open k]]]}))

;; ---- the runner's calls (promises; a refusal rejects with its error) ----

(defn listing! [server p] (api/fetch-json (api/url server "folders" {:path p})))

(defn facts-url [server p deep?] (api/url server "folders/facts" {:path p :deep (when deep? 1)}))

(defn facts! [server p deep?] (api/fetch-json (facts-url server p deep?)))

(defn trashed! [server] (api/fetch-json (api/url server "folders/trashed" nil)))

(defn missing! [server paths] (api/post-json (api/url server "folders/missing" nil) {:paths paths}))

(defn act!
  "POST /api/folders/<what> (trash, delete, restore, purge) with body."
  [server what body]
  (api/post-json (api/url server (str "folders/" what) nil) body))
