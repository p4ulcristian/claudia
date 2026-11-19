(ns claudia.slave.shown
  "Videos Claude shows in the chat (show_video). A tool result can carry a picture but not a video,
  so the file is copied into the runner's data folder, `shown/<chat>/<key>` (<key> = a time, a
  random part and the file's extension), and the tool result's text names it (`[video <key>]`).
  The page plays it from `GET /api/chats/:id/shown/<step>`: the runner finds that step's result in
  the session file, reads the key and sends the file, in byte ranges when the browser asks (Safari
  plays an MP4 only that way, and seeking needs it everywhere). A video stays as long as its chat."
  (:require ["node:fs" :as fs]
            ["node:fs/promises" :as fsp]
            ["node:path" :as path]
            ["node:crypto" :as crypto]
            [clojure.string :as str]
            [claudia.node.http :as io]))

(def video-types {".mp4" "video/mp4" ".m4v" "video/mp4" ".webm" "video/webm" ".mov" "video/quicktime"})

(def video-max
  "The largest video shown: the file is copied and kept with the chat."
  (* 1024 1024 1024))

(defonce ^:private root (atom nil))

(defn set-root! [data-dir] (reset! root (path/join data-dir "shown")))

(def key-re #"^[0-9]+-[0-9a-f]{8}\.(mp4|m4v|webm|mov)$")

(def ^:private chat-re #"^[A-Za-z0-9-]{1,80}$")

(defn new-key
  "A stored video's file name: now, a random part and the extension (ext: \".mp4\" …)."
  [now rand-hex ext]
  (str now "-" rand-hex ext))

(defn video-refusal
  "show_video's refusal for the file at file-path, or nil to show it. stat: {:file? :size}, nil when
  there is nothing there."
  [file-path stat]
  (cond (not (get video-types (str/lower-case (path/extname file-path))))
        (str file-path " is not an mp4, m4v, webm or mov video.")
        (not (:file? stat)) (str "No file at " file-path ".")
        (> (:size stat) video-max) (str file-path " is " (js/Math.round (/ (:size stat) 1e6)) " MB; the chat shows videos up to "
                                        (js/Math.round (/ video-max 1e6)) " MB. Make it smaller first.")
        :else nil))

(defn video-answer
  "show_video's answer text: what was shown and the key the page plays it by."
  [file-name k caption]
  (str "Shown in the chat: " file-name (when (seq caption) (str " (" caption ")")) ". [video " k "]"))

(defn key-in
  "The stored video's key a show_video result text names, or nil."
  [text]
  (let [k (second (re-find #"\[video ([^\]\s]+)\]" (str text)))]
    (when (and k (re-matches key-re k)) k)))

(defn store!
  "Promise of the key of a copy of the video at file-path, kept for chat."
  [chat file-path]
  (if-not (re-matches chat-re (str chat))
    (js/Promise.reject (js/Error. "No such chat."))
    (let [k (new-key (js/Date.now) (.toString (crypto/randomBytes 4) "hex") (str/lower-case (path/extname file-path)))
          dir (path/join @root chat)]
      (-> (fsp/mkdir dir #js {:recursive true})
          (.then #(fsp/copyFile file-path (path/join dir k)))
          (.then (constantly k))))))

(defn- file-of [chat k]
  (when (and (re-matches chat-re (str chat)) (re-matches key-re (str k)))
    (let [p (path/join @root chat k)]
      (when (try (.isFile (fs/statSync p)) (catch :default _ false)) p))))

(defn serve!
  "Answers with the stored video k of chat (the whole file, or the range the request asks for)."
  [^js req ^js res chat k]
  (if-let [f (file-of chat k)]
    (let [size (.-size (fs/statSync f))]
      (if (zero? size)
        (io/send-json res 404 {:error "No such video."})
        (io/send-range res f size {"content-type" (get video-types (path/extname f)) "cache-control" "private, max-age=86400"}
                       (io/byte-range (.. req -headers -range) size))))
    (io/send-json res 404 {:error "No such video."})))
