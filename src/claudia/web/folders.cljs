(ns claudia.web.folders
  "The Folders modal's place in the page (README decisions 61 to 64): opened from the folder button
  in a chat's header (row 1), on that chat's server with its folder selected, over the page until
  Escape or ×. Select (a bookmark card's, or Use for this chat) sets the chat's folder and closes
  it: a new chat's folder pick, or a started chat moved there. A new chat started there, or a chat
  opened there, closes it too. The fetches of the runner's /api/folders… calls (PROTOCOL.md,
  \"Runner HTTP: folders\")."
  (:require [re-frame.core :as rf]
            [claudia.web.api :as api]
            [claudia.web.events :as events]))

(rf/reg-event-db
 :folders/open
 ;; {:server :folder (the chat's, nil: the work folder) :chat ([server chat] of a started chat, nil
 ;; for a new one)}, or nil to close.
 (fn [db [_ opts]] (if opts (-> db (assoc :folders opts) (dissoc :world?)) (dissoc db :folders))))

(rf/reg-sub :folders (fn [db] (:folders db)))

(defn use-folder
  "What Select does with folder for the chat the modal was opened for ({:chat} of db's :folders):
  a started chat moves there, a new chat's folder pick becomes it. The effects; the modal closes."
  [db folder]
  (let [k (get-in db [:folders :chat])]
    {:db (dissoc db :folders)
     :fx [(if k [:dispatch [:chat/folder k folder]] [:dispatch [:new/settings {:folder folder}]])]}))

(rf/reg-event-fx :folders/use (fn [{:keys [db]} [_ folder]] (use-folder db folder)))

(rf/reg-event-fx
 :folders/new-chat
 ;; A new chat on server working in folder, with the picks it would carry otherwise.
 (fn [{:keys [db]} [_ server folder]]
   (-> (events/new-chat db (assoc (events/carried-picks db (:open db)) :folder folder) server)
       (update :db dissoc :folders))))

(rf/reg-event-fx
 :folders/open-chat
 (fn [{:keys [db]} [_ k]]
   {:db (dissoc db :folders) :fx [[:dispatch [:nav/open k]]]}))

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
