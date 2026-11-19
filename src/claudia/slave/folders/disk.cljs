(ns claudia.slave.folders.disk
  "The folder browser's disk work (README decisions 63 and 64): real paths, one folder's listing,
  a folder's contents. It only reads (README decision
  69: Claudia's Folders never delete). The OS permissions of the runner's user decide; nothing here
  asks root."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [claudia.slave.folders.rules :as rules]))

(def ^:private fsp (.-promises fs))

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

(defn- link-target!
  "Promise of {:target :dir?} for the symlink at p: where it points, every link followed, and whether
  a folder sits there; {:target nil :dir? false} when it is broken."
  [p]
  (-> (js/Promise.all #js [(.realpath fsp p) (.stat fsp p)])
      (.then (fn [[target ^js st]] {:target target :dir? (.isDirectory st)}))
      (.catch (fn [_] {:target nil :dir? false}))))

(defn- folder-entry
  "Promise of {:name :dir? true :mtime} for the folder named n inside folder."
  [folder n]
  (-> (.lstat fsp (path/join folder n))
      (.then (fn [^js st] {:name n :dir? true :mtime (js/Math.round (.-mtimeMs st))}))
      (.catch (fn [_] {:name n :dir? true :mtime nil}))))

(defn- link-entry
  "Promise of the symlink named n inside folder as an entry: a folder when it points to one, with
  :link (its target, \"\" when broken)."
  [folder n]
  (-> (link-target! (path/join folder n))
      (.then (fn [{:keys [target dir?]}]
               (if dir?
                 (.then (folder-entry folder n) #(assoc % :link target))
                 {:name n :dir? false :link (or target "")})))))

(defn- entry-of
  "Promise of a directory entry of folder as {:name :dir? :mtime :link?}: a symlink to a folder is a
  folder with :link (its target)."
  [folder ^js d]
  (cond (.isDirectory d) (folder-entry folder (.-name d))
        (.isSymbolicLink d) (link-entry folder (.-name d))
        :else (js/Promise.resolve {:name (.-name d) :dir? false})))

(defn entries!
  "Promise of folder's entries ({:name :dir? :mtime :link?}); rejects with the OS error."
  [folder]
  (-> (.readdir fsp folder #js {:withFileTypes true})
      (.then (fn [ds] (js/Promise.all (.map ds #(entry-of folder %)))))
      (.then vec)))

(defn logical!
  "Promise of p with `..` resolved but its symlinks kept (the path the user walked), once p resolves
  to something; rejects like real!."
  [p]
  (.then (real! p) (fn [_] (path/resolve p))))

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

;; ---- one folder's contents (files too) ----

(defn- stat-entry
  "Promise of {:name :dir? :mtime :size} for the entry named n of folder, symlinks followed (a
  broken one is a file of unknown size)."
  [folder n]
  (-> (.stat fsp (path/join folder n))
      (.then (fn [^js st] {:name n :dir? (.isDirectory st) :mtime (js/Math.round (.-mtimeMs st)) :size (.-size st)}))
      (.catch (fn [_] {:name n :dir? false :mtime nil :size nil}))))

(defn- content-entry
  "Promise of the contents entry of picked ({:name :link?}) inside folder: stat-entry, and :link
  (the target, \"\" when broken) for a symlink."
  [folder {n :name :keys [link?]}]
  (if link?
    (-> (js/Promise.all #js [(stat-entry folder n) (link-target! (path/join folder n))])
        (.then (fn [[e {:keys [target]}]] (assoc e :link (or target "")))))
    (stat-entry folder n)))

(defn contents!
  "Promise of folder's contents answer (rules/contents); rejects with the OS error."
  [folder]
  (-> (.readdir fsp folder #js {:withFileTypes true})
      (.then (fn [ds]
               (let [picked (rules/first-entries (map (fn [^js d] {:name (.-name d) :dir? (.isDirectory d) :link? (.isSymbolicLink d)}) ds))]
                 (-> (js/Promise.all (into-array (map #(content-entry folder %) picked)))
                     (.then #(rules/contents folder (vec %) (.-length ds)))))))))

(defn file-stat!
  "Promise of the file at p's {:size} (symlinks followed), nil when p is no file."
  [p]
  (-> (.stat fsp p)
      (.then (fn [^js st] (when (.isFile st) {:size (.-size st)})))
      (.catch (fn [_] nil))))
