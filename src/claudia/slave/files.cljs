(ns claudia.slave.files
  "The files form's reads: a folder's entries and a file's text, live from disk, inside a root a
  chat drew a files block for. In a git repo the tree is what git sees (tracked + untracked, not
  ignored) with each file's status; elsewhere the folder itself."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]))

(def ^:private skip #{".git" "node_modules" ".shadow-cljs" "target" ".next" ".cache" "dist" "out"})
(def ^:private max-bytes 2000000)
(def ^:private fresh-ms 4000)

(defn- git [root args]
  (try (str (cp/execFileSync "git" (clj->js (into ["-C" root] args))
                             #js {:stdio #js ["ignore" "pipe" "ignore"] :maxBuffer (* 64 1024 1024)
                                  :env (js/Object.assign #js {} (.-env js/process) #js {:GIT_OPTIONAL_LOCKS "0"})}))
       (catch :default _ nil)))

;; A text file's line count, kept until its mtime moves; nil for binary or big files.
(defonce ^:private counted (atom {}))

(defn- lines-of [abs]
  (try
    (let [st (fs/statSync abs)]
      (when (and (.isFile st) (<= (.-size st) max-bytes))
        (let [hit (get @counted abs)]
          (if (and hit (= (:mtime hit) (.-mtimeMs st)))
            (:lines hit)
            (let [buf (fs/readFileSync abs)
                  n (when-not (.includes (.subarray buf 0 8000) 0)
                      (let [nl (loop [i 0 n 0] (if (< i (.-length buf)) (recur (inc i) (if (= 10 (aget buf i)) (inc n) n)) n))]
                        (if (and (pos? (.-length buf)) (not= 10 (aget buf (dec (.-length buf))))) (inc nl) nl)))]
              (swap! counted assoc abs {:mtime (.-mtimeMs st) :lines n})
              n)))))
    (catch :default _ nil)))

;; root -> {:files [...] :status {path code} :at ms}, or :none when root is not in a repo.
(defonce ^:private repos (atom {}))

(defn- repo
  "The repo view of a root: its files and their status (M changed, A added, U new, D deleted),
  relative to the root; nil outside git."
  [root]
  (let [hit (get @repos root)]
    (if (and hit (or (= :none hit) (< (- (js/Date.now) (:at hit)) fresh-ms)))
      (when (map? hit) hit)
      (if-let [top (some-> (git root ["rev-parse" "--show-toplevel"]) str/trim not-empty)]
        (let [files (filterv seq (str/split (or (git root ["ls-files" "-co" "--exclude-standard" "-z"]) "") #"\x00"))
              lines (str/split (or (git root ["status" "--porcelain=v1" "-z" "--untracked-files=all" "."]) "") #"\x00")
              status (loop [i 0 out {}]
                       (if (>= i (count lines))
                         out
                         (let [line (nth lines i)]
                           (if (< (count line) 4)
                             (recur (inc i) out)
                             (let [code (subs line 0 2)
                                   ;; A rename or copy is followed by its old path: skip it.
                                   step (if (re-find #"[RC]" code) 2 1)
                                   rel (path/relative root (path/join top (subs line 3)))]
                               (recur (+ i step)
                                      (if (str/starts-with? rel "..")
                                        out
                                        (assoc out rel (cond (= code "??") "U" (str/includes? code "D") "D"
                                                             (str/includes? code "A") "A" :else "M")))))))))
              r {:files (vec (distinct (concat files (keys status)))) :status status :at (js/Date.now)}]
          (swap! repos assoc root r)
          r)
        (do (swap! repos assoc root :none) nil)))))

(defn inside
  "The absolute path of rel inside root, or nil when it would leave it."
  [root rel]
  (let [abs (path/resolve root (if (seq rel) rel "."))]
    (when (or (= abs root) (str/starts-with? abs (str root path/sep))) abs)))

(defn list-dir
  "A folder's entries [{:name :dir :status? :changed? :lines?}], folders first; nil when unreadable."
  [root rel]
  (when-let [abs (inside root rel)]
    (let [r (repo root)
          entries
          (if r
            (let [pre (if (seq rel) (str (str/replace rel #"/$" "") "/") "")]
              (reduce (fn [out f]
                        (if-not (str/starts-with? f pre)
                          out
                          (let [rest-path (subs f (count pre))
                                slash (str/index-of rest-path "/")
                                nm (if slash (subs rest-path 0 slash) rest-path)
                                e (get out nm {:name nm :dir (some? slash)})
                                st (get-in r [:status f])
                                e (cond (not slash) (cond-> e st (assoc :status st))
                                        st (update e :changed (fnil inc 0))
                                        :else e)]
                            (assoc out nm e))))
                      {} (:files r)))
            (try (into {} (for [^js d (fs/readdirSync abs #js {:withFileTypes true})
                                :when (not (skip (.-name d)))]
                            [(.-name d) {:name (.-name d) :dir (.isDirectory d)}]))
                 (catch :default _ nil)))]
      (when entries
        (->> (vals entries)
             (map #(if (:dir %) % (let [n (lines-of (path/join abs (:name %)))] (cond-> % n (assoc :lines n)))))
             (sort (fn [a b] (if (= (:dir a) (:dir b)) (.localeCompare (:name a) (:name b)) (if (:dir a) -1 1))))
             vec)))))

(defn read-text
  "{:text :mtime}, {:same true} when the mtime is still since, or {:error}."
  [root rel since]
  (if-let [abs (inside root rel)]
    (try
      (let [st (fs/statSync abs)]
        (cond (not (.isFile st)) {:error "not a file"}
              (and since (= (.-mtimeMs st) since)) {:same true}
              (> (.-size st) max-bytes) {:error (str "too big to show (" (js/Math.round (/ (.-size st) 1000)) " KB)")}
              :else (let [text (fs/readFileSync abs "utf8")]
                      (if (str/includes? text "\u0000") {:error "not a text file"} {:text text :mtime (.-mtimeMs st)}))))
      (catch :default _ {:error "can't read it"}))
    {:error "outside the folder"}))

(def image-re #"(?i)\.(png|jpe?g|gif|webp|avif|svg|bmp|ico)$")

(def mime {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "gif" "image/gif" "webp" "image/webp"
           "avif" "image/avif" "svg" "image/svg+xml" "bmp" "image/bmp" "ico" "image/x-icon"})

(defn image-file
  "The absolute path of an image inside root (up to 25 MB), or nil."
  [root rel]
  (when-let [abs (inside root rel)]
    (when (re-find image-re abs)
      (try (let [st (fs/statSync abs)] (when (and (.isFile st) (<= (.-size st) 25000000)) abs))
           (catch :default _ nil)))))
