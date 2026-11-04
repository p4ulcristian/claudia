(ns claudia.slave.source
  "The scene3d form's reads of real code: a folder's files with their line counts (for a city of
  the code) and one file's text (for a code slab), from the disk or from a git ref (a branch, tag
  or commit: what is there, not what is checked out). Paths are relative to the scene's root."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.slave.files :as files]))

(def ^:private max-files 20000)

(defn ref-ok?
  "A git ref the reads may pass to git: letters, digits and . _ / - ~ ^, not starting with a dash
  (so it is never read as an option), no \"..\" (no ranges)."
  [r]
  (boolean (and (string? r) (re-matches #"[A-Za-z0-9._/~^-]{1,200}" r)
                (not (str/starts-with? r "-")) (not (str/includes? r "..")))))

(defn rel-ok?
  "A path inside the root: relative, no \"..\" part, not starting with a dash."
  [rel]
  (and (string? rel) (not (path/isAbsolute rel)) (not (str/starts-with? rel "-"))
       (not-any? #(= ".." %) (str/split rel #"/"))))

(defn- git
  "Promise of git's output in folder dir, or nil when git fails."
  [dir args]
  (js/Promise.
   (fn [done]
     (cp/execFile "git" (clj->js (into ["-C" dir] args))
                  #js {:maxBuffer (* 64 1024 1024)
                       :env (js/Object.assign #js {} (.-env js/process) #js {:GIT_OPTIONAL_LOCKS "0"})}
                  (fn [err out] (done (when-not err (str out))))))))

(defn parse-counts
  "git grep -c's lines (\"[ref:]path:count\") as [[path count]], the ref's prefix taken off."
  [out ref]
  (let [pre (when ref (str ref ":"))]
    (vec (for [line (str/split-lines (or out ""))
               :let [i (str/last-index-of line ":")]
               :when (and i (pos? i))
               :let [p (subs line 0 i)
                     p (if (and pre (str/starts-with? p pre)) (subs p (count pre)) p)
                     n (js/parseInt (subs line (inc i)) 10)]
               :when (not (js/isNaN n))]
           [p n]))))

(defn- disk-walk
  "Every text file under full (relative to root) with its line count, skipping build folders."
  [root full]
  (loop [todo [full] out []]
    (if (or (empty? todo) (>= (count out) max-files))
      out
      (let [d (peek todo)
            ents (try (vec (fs/readdirSync d #js {:withFileTypes true})) (catch :default _ []))
            dirs (for [^js e ents :when (and (.isDirectory e) (not (files/skip (.-name e))))] (path/join d (.-name e)))
            fls (for [^js e ents :when (.isFile e)
                      :let [f (path/join d (.-name e))
                            n (try (files/line-count (fs/readFileSync f)) (catch :default _ nil))]
                      :when (and n (pos? n))]
                  [(path/relative root f) n])]
        (recur (into (pop todo) dirs) (into out fls))))))

(defn tree
  "Promise of {:files [[path lines]]} for the files under rel (\"\" = the root), at ref or on disk;
  {:error} when that is not readable."
  [root rel ref]
  (let [where (if (seq rel) rel ".")]
    (-> (git root (if ref
                    ["grep" "-c" "-I" "-e" "" ref "--" where]
                    ["grep" "-c" "-I" "--untracked" "-e" "" "--" where]))
        (.then (fn [out]
                 (cond
                   (some? out) {:files (vec (take max-files (parse-counts out ref)))}
                   ref {:error (str "no " rel " at " ref)}
                   :else (if-let [full (files/inside root rel)]
                           {:files (disk-walk root full)}
                           {:error "outside the folder"})))))))

(defn text
  "Promise of {:text} of the file rel, at ref or on disk, or {:error}."
  [root rel ref]
  (if ref
    (.then (git root ["show" (str ref ":./" rel)])
           (fn [out] (cond (nil? out) {:error (str "no " rel " at " ref)}
                           (> (count out) 2000000) {:error "too big to show"}
                           (str/includes? out "\u0000") {:error "not a text file"}
                           :else {:text out})))
    (js/Promise.resolve (select-keys (files/read-text root rel nil) [:text :error]))))
