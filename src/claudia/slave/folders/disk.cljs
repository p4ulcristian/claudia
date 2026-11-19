(ns claudia.slave.folders.disk
  "The folder browser's disk work (README decisions 63 and 64): real paths, one folder's listing,
  a folder's contents, sizes by `du -s` in the background (cached). It only reads (README decision
  69: Claudia's Folders never delete). The OS permissions of the runner's user decide; nothing here
  asks root."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [claudia.slave.folders.rules :as rules]))

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

;; ---- one folder's contents (files too) ----

(defn- stat-entry
  "Promise of {:name :dir? :mtime :size} for the entry named n of folder, symlinks followed (a
  broken one is a file of unknown size)."
  [folder n]
  (-> (.stat fsp (path/join folder n))
      (.then (fn [^js st] {:name n :dir? (.isDirectory st) :mtime (js/Math.round (.-mtimeMs st)) :size (.-size st)}))
      (.catch (fn [_] {:name n :dir? false :mtime nil :size nil}))))

(defn contents!
  "Promise of folder's contents answer (rules/contents); rejects with the OS error."
  [folder]
  (-> (.readdir fsp folder #js {:withFileTypes true})
      (.then (fn [ds]
               (let [picked (rules/first-entries (map (fn [^js d] {:name (.-name d) :dir? (.isDirectory d)}) ds))]
                 (-> (js/Promise.all (into-array (map #(stat-entry folder (:name %)) picked)))
                     (.then #(rules/contents folder (vec %) (.-length ds)))))))))

(defn file-stat!
  "Promise of the file at p's {:size} (symlinks followed), nil when p is no file."
  [p]
  (-> (.stat fsp p)
      (.then (fn [^js st] (when (.isFile st) {:size (.-size st)})))
      (.catch (fn [_] nil))))
