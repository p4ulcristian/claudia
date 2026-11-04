(ns claudia.slave.files
  "The files form's reads: a folder's entries and a file's text, live from disk, inside a root a
  chat drew a files block for. In a git repo the tree is what git sees (tracked + untracked, not
  ignored) with each file's status; elsewhere the folder itself."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]))

(def skip "Build and tool folders a walk of the disk leaves out." #{".git" "node_modules" ".shadow-cljs" "target" ".next" ".cache" "dist" "out"})
(def ^:private max-bytes 2000000)
(def ^:private fresh-ms 4000)

(defn- git [root args]
  (try (str (cp/execFileSync "git" (clj->js (into ["-C" root] args))
                             #js {:stdio #js ["ignore" "pipe" "ignore"] :maxBuffer (* 64 1024 1024)
                                  :env (js/Object.assign #js {} (.-env js/process) #js {:GIT_OPTIONAL_LOCKS "0"})}))
       (catch :default _ nil)))

(defn line-count
  "A file's bytes as its number of lines (a last line without a newline counts); nil when the first
  8000 bytes hold a zero byte (binary)."
  [^js buf]
  (when-not (.includes (.subarray buf 0 8000) 0)
    (let [n (.-length buf)
          nl (loop [i 0 c 0] (if (< i n) (recur (inc i) (if (= 10 (aget buf i)) (inc c) c)) c))]
      (if (and (pos? n) (not= 10 (aget buf (dec n)))) (inc nl) nl))))

;; A text file's line count, kept until its mtime moves; nil for binary or big files.
(defonce ^:private counted (atom {}))

(defn- lines-of [full]
  (try
    (let [st (fs/statSync full)]
      (when (and (.isFile st) (<= (.-size st) max-bytes))
        (let [hit (get @counted full)]
          (if (and hit (= (:mtime hit) (.-mtimeMs st)))
            (:lines hit)
            (let [n (line-count (fs/readFileSync full))]
              (swap! counted assoc full {:mtime (.-mtimeMs st) :lines n})
              n)))))
    (catch :default _ nil)))

;; root -> {:files [...] :status {path code} :at ms}, or :none when root is not in a repo.
(defonce ^:private repos (atom {}))

(defn status-letter
  "git status's two-letter code as one letter: U new, D deleted, A added, M changed."
  [code]
  (cond (= code "??") "U" (str/includes? code "D") "D" (str/includes? code "A") "A" :else "M"))

(defn parse-status
  "git status --porcelain=v1 -z records (split on NUL) as {path relative to root: letter}; top is the
  repo's top folder. Paths outside root are left out; a rename's or copy's old path is skipped."
  [records root top]
  (loop [i 0 out {}]
    (if (>= i (count records))
      out
      (let [line (nth records i)]
        (if (< (count line) 4)
          (recur (inc i) out)
          (let [code (subs line 0 2)
                rel (path/relative root (path/join top (subs line 3)))]
            (recur (+ i (if (re-find #"[RC]" code) 2 1))
                   (if (str/starts-with? rel "..") out (assoc out rel (status-letter code))))))))))

(defn- read-repo
  "The repo view of root read from git now, or nil outside git."
  [root]
  (when-let [top (some-> (git root ["rev-parse" "--show-toplevel"]) str/trim not-empty)]
    (let [tracked (filterv seq (str/split (or (git root ["ls-files" "-co" "--exclude-standard" "-z"]) "") #"\x00"))
          records (str/split (or (git root ["status" "--porcelain=v1" "-z" "--untracked-files=all" "."]) "") #"\x00")
          status (parse-status records root top)]
      {:files (vec (distinct (concat tracked (keys status)))) :status status :at (js/Date.now)})))

(defn- repo
  "The repo view of a root: its files and their status (M changed, A added, U new, D deleted),
  relative to the root; nil outside git."
  [root]
  (let [hit (get @repos root)]
    (if (and hit (or (= :none hit) (< (- (js/Date.now) (:at hit)) fresh-ms)))
      (when (map? hit) hit)
      (let [r (read-repo root)]
        (swap! repos assoc root (or r :none))
        r))))

(defn inside
  "The absolute path of rel inside root, or nil when it would leave it."
  [root rel]
  (let [full (path/resolve root (if (seq rel) rel "."))]
    (when (or (= full root) (str/starts-with? full (str root path/sep))) full)))

(defn- add-repo-file
  "One repo file f (under the folder prefix pre) added to the folder's entries by name: a file with
  its status, or the folder it is in with a count of changed files."
  [r pre out f]
  (if-not (str/starts-with? f pre)
    out
    (let [rest-path (subs f (count pre))
          slash (str/index-of rest-path "/")
          nm (if slash (subs rest-path 0 slash) rest-path)
          e (get out nm {:name nm :dir (some? slash)})
          st (get-in r [:status f])]
      (assoc out nm (cond (not slash) (cond-> e st (assoc :status st))
                          st (update e :changed (fnil inc 0))
                          :else e)))))

(defn repo-entries
  "A folder's entries by name from a repo view r (rel = the folder inside the root)."
  [r rel]
  (let [pre (if (seq rel) (str (str/replace rel #"/$" "") "/") "")]
    (reduce (partial add-repo-file r pre) {} (:files r))))

(defn- disk-entries [full]
  (try (into {} (for [^js d (fs/readdirSync full #js {:withFileTypes true})
                      :when (not (skip (.-name d)))]
                  [(.-name d) {:name (.-name d) :dir (.isDirectory d)}]))
       (catch :default _ nil)))

(defn folders-first
  "Entries sorted: folders before files, each by name."
  [entries]
  (sort (fn [a b] (if (= (:dir a) (:dir b)) (.localeCompare (:name a) (:name b)) (if (:dir a) -1 1)))
        entries))

(defn list-dir
  "A folder's entries [{:name :dir :status? :changed? :lines?}], folders first; nil when unreadable."
  [root rel]
  (when-let [full (inside root rel)]
    (let [r (repo root)]
      (when-let [entries (if r (repo-entries r rel) (disk-entries full))]
        (->> (vals entries)
             (map #(if (:dir %) % (let [n (lines-of (path/join full (:name %)))] (cond-> % n (assoc :lines n)))))
             folders-first
             vec)))))

(defn read-text
  "{:text :mtime}, {:same true} when the mtime is still since, or {:error}."
  [root rel since]
  (if-let [full (inside root rel)]
    (try
      (let [st (fs/statSync full)]
        (cond (not (.isFile st)) {:error "not a file"}
              (and since (= (.-mtimeMs st) since)) {:same true}
              (> (.-size st) max-bytes) {:error (str "too big to show (" (js/Math.round (/ (.-size st) 1000)) " KB)")}
              :else (let [text (fs/readFileSync full "utf8")]
                      (if (str/includes? text "\u0000") {:error "not a text file"} {:text text :mtime (.-mtimeMs st)}))))
      (catch :default _ {:error "can't read it"}))
    {:error "outside the folder"}))

(def image-re #"(?i)\.(png|jpe?g|gif|webp|avif|svg|bmp|ico)$")

(def mime {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "gif" "image/gif" "webp" "image/webp"
           "avif" "image/avif" "svg" "image/svg+xml" "bmp" "image/bmp" "ico" "image/x-icon"})

(defn image-file
  "The absolute path of an image inside root (up to 25 MB), or nil."
  [root rel]
  (when-let [full (inside root rel)]
    (when (re-find image-re full)
      (try (let [st (fs/statSync full)] (when (and (.isFile st) (<= (.-size st) 25000000)) full))
           (catch :default _ nil)))))
