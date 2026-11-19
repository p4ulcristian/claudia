(ns claudia.web.folders
  "Files in the right half (README decisions 61 to 64, 68 to 70, 75): the place pill in a chat's
  header (row 2) toggles the right half between the Drawing board and the File Browser
  (claudia.web.right), the folder browser of the open
  chat's server with its folder selected; the chat menu's Folders… opens that chat and its Files.
  Select (a bookmark card's, or Use for this chat) sets the chat's folder: a new chat's folder pick,
  or a started chat moved there. A new chat's first message without a folder opens Files and waits
  (README decision 65): Select then sends it and the picture comes back. The fetches of the runner's
  /api/folders… calls (PROTOCOL.md, \"Runner HTTP: folders\")."
  (:require [re-frame.core :as rf]
            [claudia.web.api :as api]
            [claudia.web.db :as db]
            [claudia.web.right :as right]))

(rf/reg-event-fx
 :folders/open
 ;; Files for chat k ([server chat]; nil: the new chat), opened on a held first message when send?.
 ;; Another chat than the open one is opened first.
 (fn [{:keys [db]} [_ {k :chat send? :send?}]]
   (cond-> (right/show-view (cond-> db send? (assoc :folders {:send? true})) :files (db/phone?))
     (and k (not= k (:open db))) (assoc :fx [[:dispatch [:nav/open k]]]))))

(defn chat-place
  "Where a chat works, the same for a new chat (oc nil or not started) and a started one: {:new?
  :ck (nil for a new chat) :server :folder (nil: the server's work folder)}."
  [{:keys [ck state row pending?] :as oc} new-server new-settings]
  (let [new? (not (and oc (or row (seq (:messages state)) pending?)))]
    {:new? new? :ck (when-not new? ck)
     :server (if new? new-server (:server oc))
     :folder (if new? (:folder new-settings) (or (:folder row) (:folder state)))}))

(defn use-folder
  "What Select does with folder for chat k (nil: the new chat): a started chat moves there, a new
  chat's folder pick becomes it, and when Files opened on a held first message (db's :folders
  :send?, README decision 65) the draft goes and the picture comes back. The effects."
  [db folder k]
  (let [send? (and (not k) (get-in db [:folders :send?]))]
    (cond-> {:db (cond-> (dissoc db :folders) send? (assoc :right-view :picture :right-saving true))
             :fx (cond-> [(if k [:dispatch [:chat/folder k folder]] [:dispatch [:new/settings {:folder folder}]])]
                   send? (conj [:dispatch [:composer/send (get-in db [:drafts :new] "")]]))}
      send? (assoc :right/put :picture))))

(rf/reg-event-fx :folders/use (fn [{:keys [db]} [_ folder k]] (use-folder db folder k)))

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

(defn missing! [server paths] (api/post-json (api/url server "folders/missing" nil) {:paths paths}))

(defn contents! [server p] (api/fetch-json (api/url server "folders/contents" {:path p})))

(defn file-url
  "The bytes of the file at p, for an image, video, audio or PDF preview (byte ranges allowed)."
  [server p]
  (api/url server "folders/file" {:path p}))

(defn file-text! [server p] (api/fetch-json (api/url server "folders/file" {:path p :read 1})))
