(ns claudia.slave.reach
  "Where a read-only view (another profile's chats) may read the disk through the page (the files,
  git, clojure and calls forms, the project and its logo): inside the profile's work folder
  (CLAUDIA_CWD, where chats run), symlinks followed, never its home or above, and no path with a part
  starting with a dot below the work folder (.git, .env, .config, .ssh…). The profile's own reads
  are not limited here."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn within?
  "True when the absolute path p is the folder top or inside it (both without symlinks)."
  [top p]
  (or (= p top) (str/starts-with? p (if (str/ends-with? top path/sep) top (str top path/sep)))))

(defn hidden?
  "True when a relative path has a part starting with a dot (\".env\", \"a/.git/config\", \"..\")."
  [rel]
  (boolean (some #(str/starts-with? % ".") (str/split (str rel) #"[/\\]"))))

(defn refusal
  "Why a view's read of real (the path with symlinks followed) is refused, or nil when it may go
  ahead: outside work (the work folder, symlinks followed), or a part below work starting with a dot."
  [work real]
  (cond (not (within? work real)) "outside the work folder"
        (hidden? (path/relative work real)) "a dot path, hidden in a read-only view"))

(defn- real-path
  "p with every symlink followed; a path that does not exist is its nearest existing folder's real
  path with the rest of p after it; nil when nothing of it exists."
  [p]
  (try (fs/realpathSync p)
       (catch :default _
         (let [up (path/dirname p)]
           (when (not= up p)
             (some-> (real-path up) (path/join (path/basename p))))))))

(defn view-refusal
  "Why this caller may not read the path p (work: the work folder; view?: a read-only view), or nil
  when it may; nil for every path when the caller is not a view."
  [work p view?]
  (cond
    (not view?) nil
    (not (and (string? p) (path/isAbsolute p))) "not an absolute path"
    :else (let [w (real-path (path/resolve work))
                r (real-path (path/resolve p))]
            (if (and w r) (refusal w r) "outside the work folder"))))

(defn reach?
  "True when this caller may read the path p."
  [work p view?]
  (nil? (view-refusal work p view?)))

(defn shown-entries
  "A folder's entries as a caller sees them: in a view, the ones named with a leading dot left out."
  [entries view?]
  (if view? (filterv #(not (str/starts-with? (str (:name %)) ".")) entries) entries))
