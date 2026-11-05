(ns claudia.web.scene3d.videos
  "The 3D scene's video, made in the background: the film button asks the hub for a video of the
  scene as shown (POST /hub/videos; the page may close at once). The runner makes it and keeps it
  with the chat; this asks the runner (GET /api/chats/:id/videos?block=) every few seconds what
  there is: while one is made, how far it is; when done, a download button, and a desktop popup
  (when turned on)."
  (:require [reagent.hooks :as h]
            [claudia.web.alerts :as alerts]
            [claudia.web.api :as api]
            [claudia.web.download :as download]
            [claudia.web.format :as fmt]
            [claudia.web.hooks :as hooks]
            [claudia.web.scene3d.icons :refer [icon]]
            [claudia.web.scene3d.video :as video]))

(def ^:private poll-ms 3000)

(defn- list-url [{:keys [server chat block]}]
  (fn [fp] (api/url server (str "chats/" (js/encodeURIComponent chat) "/videos") {:block block :fp fp})))

(defn- file-url [{:keys [server chat]} {:keys [id]}]
  (api/url server (str "chats/" (js/encodeURIComponent chat) "/videos/" (js/encodeURIComponent id) ".mp4") nil))

(defn- announce!
  "A popup for each video that was being made and now is done."
  [{:keys [server chat title]} before after]
  (doseq [v (video/just-done before after)]
    (alerts/notify! (str "Video ready: " (or (not-empty title) "3D scene")) (fmt/duration (:seconds v))
                    (str "video/" (:id v)) [server chat])))

(defn- start! [{:keys [server chat block title data voice]} set-asked]
  (set-asked "asking…")
  (-> (api/post-json "/hub/videos" {:server server :chat chat :block block :title title :data data :voice voice})
      (.then #(set-asked nil))
      (.catch #(set-asked (str "no video: " (.-message %))))))

(defn- making-button [v]
  [:button.f-tool.s3-making {:disabled true :title "The server is making the video; you can close the page"}
   [icon :wait] (str " " (video/making-text v))])

(defn- film-button [props set-asked asked]
  [:button.f-tool {:disabled (or (some? asked) (not (:live? props)))
                   :title (if (:live? props)
                            "Make a video of the story in the background: you can close the page, it waits here"
                            "Open this chat to make a video")
                   :aria-label "Make a video"
                   :on-click #(start! props set-asked)}
   [icon :film]])

(defn- download-link [props v]
  [:a.f-tool {:href (file-url props v) :download (download/file-name (:title props) "scene" "mp4")
              :title (str "Download the video (" (fmt/duration (:seconds v)) ")")}
   [icon :download] (str " " (fmt/duration (:seconds v)))])

(defn control
  "The video's place in the 3D bar. props: {:server :chat :block :title :data :voice :live?}."
  [props]
  (let [[videos set-videos] (h/use-state nil)
        [asked set-asked] (h/use-state nil)
        seen (h/use-ref nil)
        _ (hooks/use-poll {:on? true :ms poll-ms :url-of (list-url props)
                           :on-answer (fn [j]
                                        (announce! props (.-current seen) (:videos j))
                                        (set! (.-current seen) (:videos j))
                                        (set-videos (:videos j)))}
                          [(:server props) (:chat props) (:block props)])
        {:keys [making done failed]} (video/latest videos)]
    [:<>
     (if making [making-button making] [film-button props set-asked asked])
     (when (seq asked) [:span.f-note asked])
     (when failed [:span.f-note.s3-video-failed {:title (:error failed)} "video failed"])
     (when done [download-link props done])]))
