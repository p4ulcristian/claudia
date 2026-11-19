(ns claudia.slave.folders.rules
  "The folder browser's rules as plain data (README decisions 63, 64, 68 and 69): a folder's
  listing, its contents and which files preview, when a leftover Trash item expires, and the
  errors the OS gives as clear words."
  (:require ["node:path" :as path]
            [clojure.string :as str]))

(def keep-ms "How long a leftover Trash item stays (README decision 69)." (* 7 24 60 60 1000))

;; ---- errors ----

(defn error-reply
  "An OS error (its code and message) about path p as {:status :error}: permission (403), missing
  (404), not a folder or not an absolute path (400), busy or in the way (409), anything else (500)."
  [code message p]
  (case code
    ("EACCES" "EPERM") {:status 403 :error (str "Permission denied: the runner's user may not do this to " p)}
    "ENOENT" {:status 404 :error (str "No such folder: " p)}
    "ENOTDIR" {:status 400 :error (str "Not a folder: " p)}
    "EINVAL" {:status 400 :error (str "Not an absolute path: " p)}
    ("EBUSY" "ENOTEMPTY" "EEXIST") {:status 409 :error (str "In use or in the way: " p " (" message ")")}
    {:status 500 :error (str (or message "Failed") ": " p)}))

;; ---- one folder's listing ----

(defn entry-row
  "A folder entry {:name :mtime :link?} inside parent as the page gets it; a symlink keeps its own
  path (the link's place) and names its target in :link."
  [parent {entry-name :name :keys [mtime link]}]
  (cond-> {:name entry-name :path (path/join parent entry-name) :dir true :mtime mtime
           :hidden (str/starts-with? entry-name ".")}
    link (assoc :link link)))

(defn listing
  "The folders among entries ({:name :dir? :mtime :link?}) of parent, symlinks to folders included,
  sorted by name (case folded)."
  [parent entries]
  (->> entries
       (filter :dir?)
       (map #(entry-row parent %))
       (sort-by (juxt (comp str/lower-case :name) :name))
       vec))

;; ---- one folder's contents (folders and files, for the contents pane) ----

(def max-contents "The most entries a folder's contents answer holds; the rest are counted." 5000)

(def preview-types
  "The files the page previews from their bytes, by extension: the content type they are sent with."
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "gif" "image/gif" "webp" "image/webp"
   "avif" "image/avif" "svg" "image/svg+xml" "bmp" "image/bmp" "ico" "image/x-icon"
   "mp4" "video/mp4" "m4v" "video/mp4" "webm" "video/webm" "mov" "video/quicktime"
   "mp3" "audio/mpeg" "m4a" "audio/mp4" "wav" "audio/wav" "ogg" "audio/ogg" "flac" "audio/flac"
   "pdf" "application/pdf"})

(defn ext-of "A file name's extension in lower case, \"\" when it has none (a leading dot is not one)." [file-name]
  (let [i (str/last-index-of file-name ".")]
    (if (and i (pos? i)) (str/lower-case (subs file-name (inc i))) "")))

(defn preview-type "The content type a file is previewed with, or nil when it is none of preview-types." [file-name]
  (get preview-types (ext-of file-name)))

(defn kind-of
  "What a file is to the page: \"image\", \"video\", \"audio\", \"pdf\", or \"file\" (shown as text
  when it reads as text)."
  [file-name]
  (let [t (preview-type file-name)]
    (cond (nil? t) "file"
          (= t "application/pdf") "pdf"
          :else (first (str/split t #"/")))))

(defn content-row
  "An entry {:name :dir? :mtime :size :link?} inside parent as the contents pane gets it; a symlink
  names its target in :link (\"\" when broken)."
  [parent {entry-name :name :keys [dir? mtime size link]}]
  (cond-> {:name entry-name :path (path/join parent entry-name) :dir (boolean dir?) :mtime mtime
           :size (when-not dir? size) :hidden (str/starts-with? entry-name ".")
           :kind (if dir? "folder" (kind-of entry-name))}
    link (assoc :link link)))

(defn ordered
  "Entries ({:name :dir?}) folders first, then files, each by name (case folded)."
  [entries]
  (vec (sort-by (juxt (complement :dir?) (comp str/lower-case :name) :name) entries)))

(defn first-entries "The entries a contents answer holds: the first max-contents of ordered." [entries]
  (vec (take max-contents (ordered entries))))

(defn contents
  "A folder's contents answer: {:path :entries (shown entries, ordered) :more (entries left out past
  max-contents)}. shown: the first entries ({:name :dir? :mtime :size}) inside parent; total: how
  many the folder holds."
  [parent shown total]
  {:path parent
   :entries (mapv #(content-row parent %) (ordered shown))
   :more (max 0 (- total (count shown)))})

;; ---- size ----

;; ---- the Trash ----

(defn expires-at "When a trashed item goes for good." [{:keys [at]}] (+ at keep-ms))

(defn expired
  "The trashed items whose week is over at now."
  [items now]
  (filterv #(<= (expires-at %) now) items))

(defn trash-place
  "Where trashed item id of a folder named folder-name lives: {:dir (RUNNER_DATA/trash/<id>) :moved}."
  [data id folder-name]
  (let [dir (path/join data "trash" id)]
    {:dir dir :moved (path/join dir folder-name)}))

;; ---- a folder's chats ----

;; ---- git ----

