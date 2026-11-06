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

(defn byte-range
  "The bytes a Range header asks for of a file of size bytes: {:start :end} (end included), :bad
  when it can't be served (the answer is 416), nil for the whole file (no header, or one this
  doesn't read: several ranges, other units)."
  [header size]
  (when-let [[_ a b] (re-matches #"bytes=(\d*)-(\d*)" (str/trim (str header)))]
    (let [a (when (seq a) (js/parseInt a 10))
          b (when (seq b) (js/parseInt b 10))]
      (cond (and (nil? a) (nil? b)) nil
            (nil? a) (if (pos? b) {:start (max 0 (- size b)) :end (dec size)} :bad)
            (>= a size) :bad
            (and b (< b a)) :bad
            :else {:start a :end (min (dec size) (or b (dec size)))}))))

(defn- file-of [chat k]
  (when (and (re-matches chat-re (str chat)) (re-matches key-re (str k)))
    (let [p (path/join @root chat k)]
      (when (try (.isFile (fs/statSync p)) (catch :default _ false)) p))))

(defn- send-range [^js res f size mime r]
  (if (= r :bad)
    (do (.writeHead res 416 #js {"content-range" (str "bytes */" size)}) (.end res))
    (let [{:keys [start end]} (or r {:start 0 :end (dec size)})
          head #js {"content-type" mime "content-length" (str (inc (- end start))) "accept-ranges" "bytes"
                    "cache-control" "private, max-age=86400" "x-content-type-options" "nosniff"}]
      (when r (aset head "content-range" (str "bytes " start "-" end "/" size)))
      (.writeHead res (if r 206 200) head)
      (.pipe (fs/createReadStream f #js {:start start :end end}) res))))

(defn serve!
  "Answers with the stored video k of chat (the whole file, or the range the request asks for)."
  [^js req ^js res chat k]
  (if-let [f (file-of chat k)]
    (let [size (.-size (fs/statSync f))]
      (if (zero? size)
        (io/send-json res 404 {:error "No such video."})
        (send-range res f size (get video-types (path/extname f)) (byte-range (.. req -headers -range) size))))
    (io/send-json res 404 {:error "No such video."})))
