(ns claudia.slave.folders.disk
  "The folder browser's disk work (README decisions 63 and 64): real paths, one folder's listing,
  sizes by `du -s` in the background (cached), repos inside a folder, and moving and removing
  folders. The OS permissions of the runner's user decide; nothing here asks root."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.slave.files :as slave-files]
            [claudia.slave.folders.rules :as rules]
            [claudia.slave.reach :as reach]))

(def ^:private fsp (.-promises fs))

(def ^:private size-fresh-ms (* 10 60 1000))
(def ^:private du-timeout-ms (* 5 60 1000))

(defn real!
  "Promise of p with `..` resolved and every symlink followed; rejects (its code ENOENT, EACCES…)
  when p is no absolute path or cannot be resolved."
  [p]
  (if (and (string? p) (path/isAbsolute p))
    (.realpath fsp (path/resolve p))
    (js/Promise.reject (doto (js/Error. "not an absolute path") (aset "code" "EINVAL")))))

(defn real-or-nil
  "p with every symlink followed now, or nil when p is unset or missing."
  [p]
  (when (seq p) (try (fs/realpathSync p) (catch :default _ nil))))

(defn install-folder
  "Claudia's own install: the folder holding the running program's out/ (symlinks followed)."
  []
  (some-> (aget (.-argv js/process) 1) real-or-nil path/dirname path/dirname))

;; ---- one folder ----

(defn- entry-of
  "Promise of a directory entry of folder as {:name :dir? :mtime} (a symlink is no folder here)."
  [folder ^js d]
  (if (.isDirectory d)
    (-> (.lstat fsp (path/join folder (.-name d)))
        (.then (fn [^js st] {:name (.-name d) :dir? true :mtime (js/Math.round (.-mtimeMs st))}))
        (.catch (fn [_] {:name (.-name d) :dir? true :mtime nil})))
    (js/Promise.resolve {:name (.-name d) :dir? false})))

(defn entries!
  "Promise of folder's entries ({:name :dir? :mtime}); rejects with the OS error."
  [folder]
  (-> (.readdir fsp folder #js {:withFileTypes true})
      (.then (fn [ds] (js/Promise.all (.map ds #(entry-of folder %)))))
      (.then vec)))

(defn mtime!
  "Promise of a folder's modification time (ms), or nil."
  [folder]
  (-> (.stat fsp folder) (.then #(js/Math.round (.-mtimeMs ^js %))) (.catch (fn [_] nil))))

(defn- missing?!
  "Promise: true when no folder sits at p (gone, or something else there); a folder the runner may
  not look at counts as there."
  [p]
  (-> (.stat fsp p)
      (.then #(not (.isDirectory ^js %)))
      (.catch (fn [^js e] (contains? #{"ENOENT" "ENOTDIR"} (.-code e))))))

(defn missing!
  "Promise of the paths among ps (absolute) where no folder sits."
  [ps]
  (let [absolute (vec (filter #(path/isAbsolute %) ps))]
    (.then (js/Promise.all (into-array (map missing?! absolute)))
           (fn [flags] (vec (keep-indexed (fn [i gone] (when gone (nth absolute i))) flags))))))

;; ---- sizes: `du -s` in the background, kept a while ----

;; real path -> {:size (bytes or nil) :at :measuring bool}
(defonce ^:private sizes (atom {}))

(defn- du-args []
  (if (= "darwin" (.-platform js/process)) [["-sk"] 1024] [["-sb"] 1]))

(defn- measured! [folder size]
  (swap! sizes assoc folder {:size size :at (js/Date.now) :measuring false}))

(defn du!
  "Promise of folder's size in bytes by `du -s` (nil when it gave none in du-timeout-ms). du exits 1
  when some folder inside is unreadable; what it could read still counts."
  [folder]
  (let [[flags unit] (du-args)]
    (js/Promise.
     (fn [done _]
       (cp/execFile "du" (clj->js (conj flags folder)) #js {:timeout du-timeout-ms :maxBuffer (* 1024 1024)}
                    (fn [_ stdout _] (done (rules/du-bytes stdout unit))))))))

(defn- measure! [folder]
  (swap! sizes assoc folder {:size nil :at (js/Date.now) :measuring true})
  (.then (du! folder) #(measured! folder %)))

(defn size
  "folder's size so far: {:size (bytes, nil while measuring or when du gave none) :measuring}; starts
  a measure when there is none fresh."
  [folder]
  (let [hit (get @sizes folder)]
    (if (and hit (or (:measuring hit) (< (- (js/Date.now) (:at hit)) size-fresh-ms)))
      (select-keys hit [:size :measuring])
      (do (measure! folder) {:size nil :measuring true}))))

(defn forget-size!
  "Drops the kept sizes of folder, everything inside it and every folder above it."
  [folder]
  (swap! sizes (fn [m] (into {} (remove (fn [[k _]] (or (reach/within? folder k) (reach/within? k folder)))) m))))

;; ---- repos inside a folder (for the delete dialog) ----

(def ^:private repo-depth 4)
(def ^:private max-repos 40)

(defn- sub-folders
  "Promise of the folders right inside folder worth walking (no dot names, no build folders)."
  [folder]
  (-> (.readdir fsp folder #js {:withFileTypes true})
      (.then (fn [ds] (vec (for [^js d ds :let [n (.-name d)]
                                 :when (and (.isDirectory d) (not (str/starts-with? n ".")) (not (slave-files/skip n)))]
                             (path/join folder n)))))
      (.catch (fn [_] []))))

(defn- repo? [folder] (fs/existsSync (path/join folder ".git")))

(defn inner-repos!
  "Promise of the git repos below folder (not folder itself), at most repo-depth folders down and
  max-repos of them, a repo's own folders not walked."
  [folder]
  (letfn [(walk [dirs depth found]
            (if (or (empty? dirs) (> depth repo-depth) (>= (count found) max-repos))
              (js/Promise.resolve (vec (take max-repos found)))
              (-> (js/Promise.all (into-array (map sub-folders dirs)))
                  (.then (fn [lists]
                           (let [kids (vec (apply concat lists))
                                 repos (filterv repo? kids)]
                             (walk (vec (remove (set repos) kids)) (inc depth) (into found repos))))))))]
    (walk [folder] 1 [])))

;; ---- moving and removing ----

(defn- copy-then-remove!
  "Promise: from copied to to (a whole folder, symlinks as they are), then from removed. When the
  copy is whole but removing from fails, the error says so (its `copied` true)."
  [from to]
  (-> (.cp fsp from to #js {:recursive true :errorOnExist true :force false :preserveTimestamps true :verbatimSymlinks true})
      (.then #(-> (.rm fsp from #js {:recursive true})
                  (.catch (fn [^js e] (aset e "copied" true) (js/Promise.reject e)))))))

(defn move!
  "Promise: the folder from moved to to (whose parent must exist); across filesystems (EXDEV) it is
  copied, then removed (copy-then-remove!)."
  [from to]
  (-> (.rename fsp from to)
      (.catch (fn [^js e] (if (= "EXDEV" (.-code e)) (copy-then-remove! from to) (js/Promise.reject e))))))

(defn remove!
  "Promise: the folder p and everything in it gone."
  [p]
  (.rm fsp p #js {:recursive true}))

(defn there? "True when something (even a broken symlink) sits at p." [p]
  (try (fs/lstatSync p) true (catch :default _ false)))
