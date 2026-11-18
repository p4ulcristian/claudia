(ns claudia.web.folders
  "The Folders modal's place in the page (README decisions 61 to 64): opened from the folder pill in
  a chat's header (row 2) or the chat menu's Folders…, on that chat's server with its folder selected, over the page until
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
 ;; for a new one) :send? (opened on a held first message)}, or nil to close.
 (fn [db [_ opts]] (if opts (-> db (assoc :folders opts) (dissoc :world?)) (dissoc db :folders))))

(rf/reg-sub :folders (fn [db] (:folders db)))

(defn use-folder
  "What Select does with folder for the chat the modal was opened for ({:chat :send?} of db's
  :folders): a started chat moves there, a new chat's folder pick becomes it, and when the modal
  opened on a held first message (send?, README decision 65) the draft goes. The effects; the modal
  closes."
  [db folder]
  (let [{k :chat send? :send?} (:folders db)]
    {:db (dissoc db :folders)
     :fx (cond-> [(if k [:dispatch [:chat/folder k folder]] [:dispatch [:new/settings {:folder folder}]])]
           (and send? (not k)) (conj [:dispatch [:composer/send (get-in db [:drafts :new] "")]]))}))

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

;; ---- each server's work folder (CLAUDIA_CWD, as the listing answers it) ----

(rf/reg-event-fx
 :folders/load-work
 ;; Asked once per server (the header shows it for a chat without a folder).
 (fn [{:keys [db]} [_ server]]
   (when-not (contains? (:work-folders db) server)
     {:db (assoc-in db [:work-folders server] nil) :folders/get-work server})))

(rf/reg-fx
 :folders/get-work
 (fn [server]
   (-> (api/fetch-json (api/url server "folders" {:path "/"}))
       (.then #(rf/dispatch [:folders/work-loaded server (:cwd %)]))
       (.catch (fn [_] nil)))))

(rf/reg-event-db :folders/work-loaded (fn [db [_ server cwd]] (assoc-in db [:work-folders server] cwd)))

(rf/reg-sub :work-folder (fn [db [_ server]] (get-in db [:work-folders server])))

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
