(ns claudia.slave.folders.rules
  "The folder browser's rules as plain data (README decisions 63 and 64): which folders the runner
  never deletes, a folder's listing, the Trash's expiry and total, a folder's chats, the git summary
  the delete dialog shows, and the errors the OS gives as clear words."
  (:require ["node:path" :as path]
            [clojure.string :as str]
            [claudia.slave.reach :as reach]))

(def keep-ms "How long the Trash keeps a folder." (* 7 24 60 60 1000))

(def max-chats "The most chats a folder's facts name." 20)

;; ---- what is never deleted ----

(defn- at-or-above?
  "True when p is the folder top or one of its ancestors."
  [p top]
  (boolean (and top (reach/within? p top))))

(defn- at-or-inside?
  "True when p is the folder top or inside it."
  [p top]
  (boolean (and top (reach/within? top p))))

(defn delete-refusal
  "Why the runner never deletes the folder real (absolute, symlinks followed), or nil when the OS may
  decide. kept: {:home :cwd :data :install} (each real, or nil): the home folder and the work folder
  (CLAUDIA_CWD) and their ancestors, Claudia's own data (RUNNER_DATA) and install, and inside them."
  [real {:keys [home cwd data install]}]
  (cond
    (not (and (string? real) (path/isAbsolute real))) "not an absolute path"
    (= real "/") "the root folder"
    (= (path/dirname real) "/") "a top-level system folder"
    (= (path/dirname real) "/private") "a system folder"
    (at-or-above? real home) "the home folder, or a folder above it"
    (at-or-above? real cwd) "the work folder (CLAUDIA_CWD), or a folder above it"
    (or (at-or-inside? real data) (at-or-above? real data)) "Claudia's own data (RUNNER_DATA)"
    (or (at-or-inside? real install) (at-or-above? real install)) "Claudia's own install"
    :else nil))

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
  "A folder entry {:name :mtime} inside parent as the page gets it."
  [parent {entry-name :name :keys [mtime]}]
  {:name entry-name :path (path/join parent entry-name) :dir true :mtime mtime
   :hidden (str/starts-with? entry-name ".")})

(defn listing
  "The folders among entries ({:name :dir? :mtime}) of parent, sorted by name (case folded)."
  [parent entries]
  (->> entries
       (filter :dir?)
       (map #(entry-row parent %))
       (sort-by (juxt (comp str/lower-case :name) :name))
       vec))

;; ---- size ----

(defn du-bytes
  "The size `du -s` printed (its last line's first number) in bytes, unit bytes per number; nil when
  it printed none."
  [out unit]
  (when-let [line (last (remove str/blank? (str/split-lines (str out))))]
    (when-let [[_ n] (re-find #"^\s*(\d+)" line)]
      (* unit (js/Number n)))))

;; ---- the Trash ----

(defn expires-at "When a trashed item goes for good." [{:keys [at]}] (+ at keep-ms))

(defn expired
  "The trashed items whose week is over at now."
  [items now]
  (filterv #(<= (expires-at %) now) items))

(defn trash-total "The trashed items' size in bytes (unknown sizes count 0)." [items]
  (reduce + 0 (keep :size items)))

(defn trash-place
  "Where trashed item id of a folder named folder-name lives: {:dir (RUNNER_DATA/trash/<id>) :moved}."
  [data id folder-name]
  (let [dir (path/join data "trash" id)]
    {:dir dir :moved (path/join dir folder-name)}))

(defn trash-row
  "A trashed item as the page gets it."
  [item]
  (assoc (select-keys item [:id :path :name :at :size :by]) :expires (expires-at item)))

;; ---- a folder's chats ----

(defn chats-in
  "This runner's chats (rows {:id :title :at :folder}) whose folder is folder or inside it (a chat
  without a folder works in cwd), newest first, at most max-chats: [{:id :title :at}]."
  [rows folder cwd]
  (->> rows
       (filter #(reach/within? folder (path/resolve (or (:folder %) cwd))))
       (sort-by :at >)
       (take max-chats)
       (mapv #(select-keys % [:id :title :at]))))

;; ---- git ----

(defn git-summary
  "What the delete dialog says about a repo: {:top :branch (nil when detached) :dirty (files not
  committed) :ahead :behind (of the current branch's upstream) :noUpstream (local branches without
  a live upstream, sorted)}. tracking: branch -> {:upstream :gone :ahead :behind}."
  [{:keys [top current tracking dirty]}]
  (let [t (get tracking current)]
    {:top top
     :branch (not-empty current)
     :dirty (or (:total dirty) 0)
     :ahead (or (:ahead t) 0)
     :behind (or (:behind t) 0)
     :noUpstream (vec (sort (for [[b {:keys [upstream gone]}] tracking :when (or (str/blank? upstream) gone)] b)))}))

(defn unpushed?
  "True when a repo summary has work only here: files not committed, commits ahead, or branches
  without an upstream."
  [{:keys [dirty ahead noUpstream]}]
  (boolean (or (pos? dirty) (pos? ahead) (seq noUpstream))))
