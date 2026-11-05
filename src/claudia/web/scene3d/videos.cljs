(ns claudia.web.scene3d.videos
  "The 3D scene's video, made in the background: the film button asks the hub for a video of the
  scene as shown (POST /hub/videos; the page may close at once). The runner makes it and keeps it
  with the chat; this asks the runner (GET /api/chats/:id/videos?block=) every few seconds what
  there is: while one is made, how far it is; when done, a button that plays it here, in a player
  with Download (a web-app window shows no downloads of its own), and a desktop popup (when turned
  on)."
  (:require ["react-dom" :as react-dom]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.shared.files :as files]
            [claudia.shared.text :as text]
            [claudia.web.alerts :as alerts]
            [claudia.web.api :as api]
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

(defn- start!
  "Asks the hub for the video; on its answer the list is asked again at once."
  [{:keys [server chat block title data]} set-asked set-nonce]
  (set-asked "Starting the video…")
  (-> (api/post-json "/hub/videos" {:server server :chat chat :block block :title title :data data})
      (.then #(set-nonce inc))
      (.catch #(set-asked (str "No video: " (.-message %))))))

(defn- making-label [v]
  [:span.f-tool.s3-making {:title "The server is making the video. You can close the page: it waits here."}
   [icon :wait] (str " " (video/making-text v))])

(defn- film-button [props set-asked set-nonce]
  [:button.f-tool {:disabled (not (:live? props))
                   :title (if (:live? props)
                            "Make a video of the story, with the narrator, in the background: you can close the page, it waits here"
                            "Open this chat to make a video")
                   :aria-label "Make a video"
                   :on-click #(start! props set-asked set-nonce)}
   [icon :film]])

(defn- player-bar [props v note set-note close]
  [:div.s3-player-bar
   [:b (or (not-empty (:title props)) "3D scene")]
   [:span.f-note (str (fmt/duration (:seconds v)) (when (:size v) (str " · " (files/size-label (:size v)))))]
   [:a.f-tool {:href (file-url props v) :download (text/file-name (:title props) "scene" "mp4")
               :on-click #(set-note "Download started: it is in the folder your browser saves files to")}
    [icon :download] " Download"]
   (when note [:span.f-note note])
   [:button.f-tool {:title "Close (Esc)" :aria-label "Close" :on-click close} [icon :close]]])

(defn- player
  "The video playing over the window, with Download; Esc or a click beside it closes it. It is put
  in the page's body (or the element in full screen): inside the picture pane, a container, a
  fixed box is placed in the pane, not the window, and fell out of sight."
  [props v close]
  (let [[note set-note] (h/use-state nil)]
    (h/use-effect (fn []
                    (let [f #(when (= "Escape" (.-key %)) (close))]
                      (js/addEventListener "keydown" f)
                      #(js/removeEventListener "keydown" f)))
                  [])
    (react-dom/createPortal
     (r/as-element
      [:div.s3-player {:role "dialog" :aria-label "Video"
                       :on-click #(when (identical? (.-target %) (.-currentTarget %)) (close))}
       [:div.s3-player-box
        [:video {:src (file-url props v) :controls true :auto-play true :plays-inline true}]
        [player-bar props v note set-note close]]])
     (or js/document.fullscreenElement js/document.body))))

(defn- watch-button [v fresh open]
  [:button.f-tool {:class (when fresh "s3-video-new")
                   :title "Play the video here, and download it"
                   :on-click open}
   [icon :play] (str " Video " (fmt/duration (:seconds v)))])

(defn- poll
  "Asks the runner for the scene's videos (again at once when nonce changes)."
  [props nonce on-videos]
  (hooks/use-poll {:on? true :ms poll-ms :url-of (list-url props) :on-answer #(on-videos (:videos %))}
                  [(:server props) (:chat props) (:block props) nonce]))

(defn control
  "The video's place in the 3D bar. props: {:server :chat :block :title :data :live?}."
  [props]
  (let [[videos set-videos] (h/use-state nil)
        [asked set-asked] (h/use-state nil)
        [nonce set-nonce] (h/use-state 0)
        [playing set-playing] (h/use-state nil)
        [fresh set-fresh] (h/use-state nil)
        seen (h/use-ref nil)
        _ (poll props nonce (fn [vs]
                              (announce! props (.-current seen) vs)
                              (when-let [v (first (video/just-done (.-current seen) vs))] (set-fresh (:id v)))
                              (set! (.-current seen) vs)
                              (when (:making (video/latest vs)) (set-asked nil))
                              (set-videos vs)))
        {:keys [making done failed]} (video/latest videos)]
    [:<>
     (cond making [making-label making]
           (= asked "Starting the video…") [:span.f-tool.s3-making [icon :wait] (str " " asked)]
           :else [film-button props set-asked set-nonce])
     (when (and asked (not= asked "Starting the video…")) [:span.f-note asked])
     (when failed [:span.f-note.s3-video-failed {:title (:error failed)} "Video failed"])
     (when done [watch-button done (= fresh (:id done)) #(do (set-fresh nil) (set-playing done))])
     (when playing [:f> player props playing #(set-playing nil)])]))
