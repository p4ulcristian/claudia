(ns claudia.slave.uploads
  "Files the user attaches to a message (anything but a photo or video, which go as images): saved in
  the runner's data folder, `uploads/<chat>/<name>` (a \"-2\" before the extension when that name is
  taken), as the body of `POST /api/chats/:id/uploads?name=`. The message carries one <claudia-file>
  tag per file after the user's text and Claude reads the file at its path. A file stays as long as
  its chat: its messages point at it. <chat> is the id the page used when it added the file (a new
  chat's own uuid, before the runner names the chat)."
  (:require ["node:fs" :as fs]
            [claudia.node.http :as io]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.shared.files :as files]))

(def max-bytes files/max-bytes)

(defonce ^:private root (atom nil))

(defn set-root! [data-dir] (reset! root (path/join data-dir "uploads")))

(def uuid-re #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn safe-name
  "A name that is one file in a folder: no folders, no control characters, at most 200 characters."
  [n]
  (let [s (-> (path/basename (str/replace (str n) "\\" "/"))
              (str/replace #"[\u0000-\u001f\u007f]" "")
              str/trim)]
    (cond (or (= s "") (= s ".") (= s "..")) "file"
          (<= (count s) 200) s
          :else (let [ext (subs (path/extname s) 0 (min 20 (count (path/extname s))))]
                  (str (subs s 0 (- 200 (count ext))) ext)))))

(defn file-path
  "The saved file `name` of chat `chat`, or nil when there is none."
  [chat n]
  (when (and (re-matches uuid-re (str chat)) (= n (safe-name n)))
    (let [p (path/join @root chat n)]
      (when (try (.isFile (fs/statSync p)) (catch :default _ false)) p))))

(defn- open-new
  "Opens a fresh file for want in dir (name-2.ext, name-3.ext… when taken): [path fd]."
  [dir want]
  (let [ext (path/extname want)
        stem (if (seq ext) (subs want 0 (- (count want) (count ext))) want)]
    (loop [n 1]
      (let [p (path/join dir (if (= n 1) want (str stem "-" n ext)))
            fd (try (fs/openSync p "wx" (js/parseInt "600" 8))
                    (catch :default e (if (and (= "EEXIST" (.-code e)) (< n 1000)) nil (throw e))))]
        (if fd [p fd] (recur (inc n)))))))

(defn refusal
  "Why an upload of name n (content-length length) to chat is refused: [status error], or nil."
  [chat n length]
  (cond
    (not (re-matches uuid-re (str chat))) [400 "bad request"]
    (not (seq n)) [400 "bad request"]
    (> length max-bytes) [413 "file too big"]))

(defn- upload-type [^js req]
  (or (not-empty (aget (.-headers req) "content-type")) "application/octet-stream"))

(defn- receive!
  "Writes the request's body into the opened file [p fd]; answers once: the file, or an error (the
  half-written file then removed)."
  [^js req ^js res [p fd]]
  (let [size (atom 0)
        finished (atom false)
        fail! (fn [status msg]
                (when-not @finished
                  (reset! finished true)
                  (try (fs/closeSync fd) (catch :default _))
                  (try (fs/unlinkSync p) (catch :default _))
                  (when-not (.-headersSent res) (io/send-json res status {:error msg}))))]
    (.on req "data" (fn [part]
                      (when-not @finished
                        (if (> (swap! size + (.-length part)) max-bytes)
                          (do (fail! 413 "file too big") (.destroy req))
                          (try (fs/writeSync fd part)
                               (catch :default _ (fail! 500 "could not save the file")))))))
    (.on req "error" #(fail! 400 "the upload broke off"))
    (.on req "aborted" #(fail! 400 "the upload broke off"))
    (.on req "end" (fn []
                     (when-not @finished
                       (reset! finished true)
                       (fs/closeSync fd)
                       (io/send-json res 200 {:path p :name (path/basename p) :size @size
                                              :type (upload-type req)}))))))

(defn save!
  "Saves a request body as the chat's file; answers {path name size type} or an error."
  [^js req ^js res chat n]
  (if-let [[status msg] (refusal chat n (js/Number (aget (.-headers req) "content-length")))]
    (io/send-json res status {:error msg})
    (let [dir (path/join @root chat)]
      (fs/mkdirSync dir #js {:recursive true :mode (js/parseInt "700" 8)})
      (receive! req res (open-new dir (safe-name n))))))

(defn serve!
  "One saved file back, to download (never shown inline: it is whatever the user sent)."
  [^js res chat n]
  (if-let [p (file-path chat n)]
    (do (.writeHead res 200 #js {"content-type" "application/octet-stream"
                                 "content-disposition" (str "attachment; filename*=UTF-8''" (js/encodeURIComponent n))
                                 "x-content-type-options" "nosniff"
                                 "cache-control" "private, max-age=86400"})
        (.pipe (fs/createReadStream p) res))
    (io/send-json res 404 {:error "No such file."})))

;; ---- the tags ----

(defn file-type
  "A message's claimed type for a file when it is a short text, else application/octet-stream."
  [t]
  (if (and (string? t) (< (count t) 200) (seq t)) t "application/octet-stream"))

(defn own-files
  "The files a message names that really are this profile's saved files (sizes read from disk)."
  [files]
  (vec (for [f (take 50 (when (sequential? files) files))
             :let [p (str (:path f))
                   chat (path/basename (path/dirname p))
                   saved (file-path chat (path/basename p))]
             :when (and saved (= saved p))]
         {:path p :name (path/basename p) :size (.-size (fs/statSync p))
          :type (file-type (:type f))})))

(def with-files "The user's text with one <claudia-file> tag per file after it." files/with-files)
