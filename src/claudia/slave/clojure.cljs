(ns claudia.slave.clojure
  "The clojure and calls forms' reads, from clj-kondo's analysis (run with --cache false, so nothing
  is written into the project). One analysis per root is kept, shared by both forms, and redone when
  a source file changes (the fingerprint); one clj-kondo run per root at a time."
  (:require ["node:fs" :as fs]
            [claudia.node.fs :refer [dir?]]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            [clojure.string :as str]))

(def ^:private source-re #"\.(clj|cljs|cljc|bb)$")
(def ^:private skip #{"node_modules" "target" ".git" ".cpcache" ".shadow-cljs" "out" ".clj-kondo"})

(defn- file-exists? [p] (fs/existsSync p))
(defn- config-paths
  "The folders with code: deps.edn / bb.edn :paths, shadow-cljs.edn and project.clj :source-paths."
  [dir]
  (let [found (distinct (for [[file k] [["deps.edn" ":paths"] ["bb.edn" ":paths"] ["shadow-cljs.edn" ":source-paths"] ["project.clj" ":source-paths"]]
                              :let [f (path/join dir file)]
                              :when (file-exists? f)
                              :let [m (re-find (re-pattern (str k "\\s*\\[([^\\]]*)\\]")) (fs/readFileSync f "utf8"))]
                              :when m
                              s (re-seq #"\"([^\"]+)\"" (second m))]
                          (second s)))]
    (vec (for [p found :let [abs (path/resolve dir p)]
               :when (and (str/starts-with? abs dir) (dir? abs))]
           abs))))

(defn source-paths
  "A project's code folders; a folder of projects gives each one's; else src/, else the root."
  [root]
  (let [own (config-paths root)]
    (if (seq own)
      own
      (let [subs (try (vec (for [^js e (fs/readdirSync root #js {:withFileTypes true})
                                 :when (and (.isDirectory e) (not (str/starts-with? (.-name e) ".")) (not (skip (.-name e))))]
                             (path/join root (.-name e))))
                      (catch :default _ []))
            family (vec (mapcat config-paths subs))]
        (cond (seq family) family
              (file-exists? (path/join root "src")) [(path/join root "src")]
              :else [root])))))

(defn- walk [dir out]
  (doseq [^js e (try (fs/readdirSync dir #js {:withFileTypes true}) (catch :default _ #js []))
          :when (not (or (str/starts-with? (.-name e) ".") (skip (.-name e))))
          :let [p (path/join dir (.-name e))]]
    (cond (.isDirectory e) (walk p out)
          (re-find source-re (.-name e)) (.push out p)))
  out)

(defn fingerprint
  "Changes when a source file is added, removed or edited."
  [root]
  (let [h (crypto/createHash "sha1")]
    (doseq [d (source-paths root) f (walk d #js [])]
      (.update h (str f (try (.-mtimeMs (fs/statSync f)) (catch :default _ 0)))))
    (subs (.digest h "hex") 0 16)))

(def ^:private kinds {"defn" "fn" "defn-" "fn" "defmacro" "macro" "def" "def" "defonce" "def" "defmulti" "multi"
                      "defprotocol" "protocol" "defrecord" "record" "deftype" "type" "declare" "declare" "goog-define" "def"})

(defn- run-kondo [root paths]
  (js/Promise.
   (fn [resolve reject]
     (cp/execFile "clj-kondo"
                  (clj->js (concat ["--lint"] paths ["--parallel" "--cache" "false" "--config"
                                                     "{:output {:format :json} :analysis {:arglists true} :linters ^:replace {}}"]))
                  #js {:cwd root :maxBuffer (* 512 1024 1024) :timeout 300000}
                  (fn [_err stdout stderr]
                    (let [a (try (.-analysis (js/JSON.parse stdout)) (catch :default _ nil))]
                      (if a (resolve a) (reject (js/Error. (or (not-empty (str/trim (str stderr))) "clj-kondo gave no analysis"))))))))))

(defn- analyse [root fp]
  (.then
   (run-kondo root (source-paths root))
   (fn [^js a]
     (let [get-list #(or (aget a %) #js [])
           files (atom {}) docs (atom {})]
       (doseq [^js n (get-list "namespace-definitions")]
         (when-not (contains? @files (.-name n)) (swap! files assoc (.-name n) (path/resolve root (.-filename n))))
         (when (and (.-doc n) (not (get @docs (.-name n)))) (swap! docs assoc (.-name n) (.-doc n))))
       (let [files @files
             req (atom {}) ext (atom {}) uses (atom {}) calls (atom []) seen (atom #{}) vars (atom {}) var-files (atom {})]
         (doseq [^js u (get-list "namespace-usages")
                 :when (and (contains? files (.-from u)) (not= (.-from u) (.-to u)))]
           (swap! (if (contains? files (.-to u)) req ext) update (.-from u) (fnil conj #{}) (.-to u)))
         (doseq [^js u (get-list "var-usages")
                 :let [from (.-from u) to (.-to u)]
                 :when (and (contains? files from) (contains? files to))]
           (when (not= from to)
             (swap! uses update-in [from to (.-name u)] (fnil inc 0)))
           (when-let [fv (aget u "from-var")]
             (let [row (or (aget u "name-row") (.-row u)) col (or (aget u "name-col") (.-col u))
                   c {:from (str from "/" fv) :to (str to "/" (.-name u)) :row row :col col
                      :endCol (or (aget u "name-end-col") (+ col (count (str (.-name u)))))}
                   k [(:from c) (:to c) row col]]
               (when-not (or (= (:from c) (:to c)) (@seen k))
                 (swap! seen conj k)
                 (swap! calls conj c)))))
         (doseq [^js v (get-list "var-definitions")
                 :let [ns (.-ns v)]
                 :when (and (contains? files ns) (not (some #(= (:name %) (.-name v)) (get @vars ns))))]
           (swap! var-files assoc (str ns "/" (.-name v)) (path/resolve root (.-filename v)))
           (let [by (last (str/split (str (or (aget v "defined-by->lint-as") (aget v "defined-by") "")) #"/"))]
             (swap! vars update ns (fnil conj [])
                    {:name (.-name v) :args (vec (or (aget v "arglist-strs") #js [])) :doc (.-doc v)
                     :private (or (boolean (.-private v)) (= by "defn-")) :kind (get kinds by (if (seq by) by "def"))
                     :row (.-row v) :endRow (or (aget v "end-row") (.-row v))})))
         {:fp fp :root root
          :namespaces (vec (for [n (sort (keys files))]
                             {:name n :file (subs (get files n) (inc (count root))) :vars (count (get @vars n))
                              :requires (vec (sort (get @req n)))}))
          :docs @docs :uses @uses :files files
          :vars (into {} (for [[k l] @vars] [k (vec (sort-by :row l))]))
          :external (into {} (for [[k s] @ext] [k (vec (sort s))]))
          :calls @calls :var-files @var-files})))))

;; root -> {:done index :running promise}
(defonce ^:private roots (atom {}))

(defn index
  "Promise of the root's index: while a run goes, the last finished index answers."
  [root]
  (let [r (get @roots root)]
    (if (:running r)
      (or (some-> (:done r) js/Promise.resolve) (:running r))
      (let [fp (fingerprint root)]
        (if (= fp (get-in r [:done :fp]))
          (js/Promise.resolve (:done r))
          (let [run (analyse root fp)]
            (swap! roots assoc-in [root :running] run)
            (-> run
                (.then (fn [i] (swap! roots assoc-in [root :done] i)) (fn [_]))
                (.finally #(swap! roots update root dissoc :running)))
            (if-let [d (:done r)] (js/Promise.resolve d) run)))))))

(defn namespaces [root since]
  (.then (index root)
         (fn [i] (if (= since (:fp i)) {:same true}
                     {:fp (:fp i) :paths (mapv #(let [r (subs % (min (count %) (inc (count root))))] (if (seq r) r ".")) (source-paths root))
                      :namespaces (:namespaces i)}))))

(defn ns-detail [root ns]
  (.then (index root)
         (fn [i] (if-not (contains? (:files i) ns)
                   (throw (js/Error. "no such namespace"))
                   {:doc (get-in i [:docs ns]) :vars (get-in i [:vars ns] []) :external (get-in i [:external ns] [])}))))

(defn- lines-of [file] (try (str/split (fs/readFileSync file "utf8") #"\n" -1) (catch :default _ [])))

(defn var-source [root ns nm]
  (.then (index root)
         (fn [i]
           (let [v (some #(when (= (:name %) nm) %) (get-in i [:vars ns]))
                 file (get-in i [:files ns])]
             (if-not (and v file)
               (throw (js/Error. "no such var"))
               {:code (str/join "\n" (subvec (lines-of file) (dec (:row v)) (min (count (lines-of file)) (:endRow v))))
                :file (subs file (inc (count root))) :row (:row v) :lang (last (str/split file #"\."))})))))

(defn edge-uses
  "The vars namespaces under from use from namespaces under to (exact: that namespace only)."
  [root from from-exact to to-exact]
  (.then (index root)
         (fn [i]
           (let [under (fn [n p exact] (or (= n p) (and (not exact) (str/starts-with? n (str p ".")))))
                 found (reduce (fn [m [f by]]
                                 (if-not (under f from from-exact) m
                                         (reduce (fn [m [t names]]
                                                   (if-not (under t to to-exact) m
                                                           (reduce (fn [m [nm c]]
                                                                     (let [k (str t "/" (name nm))]
                                                                       (-> m (update-in [k :count] (fnil + 0) c)
                                                                           (assoc-in [k :ns] t) (assoc-in [k :name] (name nm))
                                                                           (update-in [k :from] (fnil conj #{}) f))))
                                                                   m names)))
                                                 m by)))
                               {} (:uses i))]
             {:used (vec (for [u (sort (fn [a b] (let [c (.localeCompare (:ns a) (:ns b))]
                                                      (cond (not (zero? c)) c
                                                            (not= (:count a) (:count b)) (- (:count b) (:count a))
                                                            :else (.localeCompare (:name a) (:name b)))))
                                     (vals found))]
                           (update u :from #(vec (sort %)))))}))))

;; ---- the calls form ----

(defn- derive* [i]
  (let [defs (into {} (for [[ns l] (:vars i) v l
                            :let [id (str ns "/" (:name v)) file (or (get-in i [:var-files id]) (get-in i [:files ns]))]
                            :when file]
                        [id {:id id :ns ns :name (:name v) :v v :file file}]))
        cs (filter #(and (defs (:from %)) (defs (:to %))) (:calls i))]
    {:defs defs
     :out (into {} (for [[k l] (group-by :from cs)] [k (vec (sort-by (juxt :row :col) l))]))
     :inc (group-by :to cs)
     ;; Read once per index: files' lines, each var's .sql links, who names each .sql file.
     :lines (atom {}) :sql (atom {}) :sql-in (atom nil)}))

(defonce ^:private derived (atom {}))
(defn- derive-of [i]
  (or (get @derived [(:root i) (:fp i)])
      (let [d (derive* i)] (reset! derived {[(:root i) (:fp i)] d}) d)))

(defn- file-lines [d file]
  (or (get @(:lines d) file)
      (let [l (lines-of file)] (swap! (:lines d) assoc file l) l)))

(defn- rel [root f] (if (str/starts-with? f (str root "/")) (subs f (inc (count root))) f))

(defn- folder-of [root file]
  (let [from (or (last (sort-by count (filter #(str/starts-with? file (str % "/")) (source-paths root)))) root)
        d (path/dirname (subs file (inc (count from))))]
    (if (= d ".") "" d)))

(def ^:private tokens-re #"(\"(?:\\.|[^\"\\])*\")|(;[^\n]*)|(?<![\w:])(:[A-Za-z_*!?<>=+\-][\w\-?!.*<>=+]*)(?![\w/:])")

(declare sql-of*)

(defn- sql-of
  "The .sql files a var names by a plain keyword whose name (- as _) is a file in its folder."
  [d root id]
  (if-let [had (get @(:sql d) id)]
    had
    (let [found (sql-of* d root id)] (swap! (:sql d) assoc id found) found)))

(defn- sql-of* [d root id]
  (if-let [def (get-in d [:defs id])]
    (let [dir (path/dirname (:file def))
          all (file-lines d (:file def))
          lines (subvec all (dec (get-in def [:v :row])) (min (count all) (get-in def [:v :endRow])))
          code (str/join "\n" lines)
          starts (vec (reductions (fn [s l] (+ s (count l) 1)) 0 lines))
          re (js/RegExp. (.-source tokens-re) "g")]
      (loop [out []]
        (if-let [m (.exec re code)]
          (let [kw (aget m 3)]
            (if-not kw
              (recur out)
              (let [file (path/join dir (str (str/replace (subs kw 1) "-" "_") ".sql"))]
                (if-not (file-exists? file)
                  (recur out)
                  (let [li (dec (count (take-while #(<= % (.-index m)) starts)))
                        col (inc (- (.-index m) (nth starts li)))]
                    (recur (conj out {:to (str "file:" (rel root file))
                                      :at {:row (+ (get-in def [:v :row]) li) :col col :endCol (+ col (count kw))}})))))))
          out)))
    []))

(defn- sql-callers [d root]
  (or @(:sql-in d)
      (let [has-sql (memoize (fn [dir] (try (boolean (some #(str/ends-with? % ".sql") (fs/readdirSync dir))) (catch :default _ false))))]
        (reset! (:sql-in d)
                (reduce (fn [m def]
                          (if-not (has-sql (path/dirname (:file def))) m
                                  (reduce (fn [m s] (update m (:to s) (fnil conj []) {:from (:id def) :at (:at s)})) m (sql-of d root (:id def)))))
                        {} (vals (:defs d)))))))

(defn- resolve-id [d root s]
  (let [s (str/trim (str s))]
    (cond
      (str/starts-with? s "file:")
      (if (and (str/ends-with? s ".sql") (not (str/includes? s "..")) (file-exists? (path/join root (subs s 5)))) s
          (throw (js/Error. (str "no such .sql file: " (subs s 5)))))
      (get-in d [:defs s]) s
      :else
      (let [slash (str/last-index-of s "/")]
        (when-not (and slash (pos? slash)) (throw (js/Error. (str "\"" s "\" is not a var: give it as ns/name"))))
        (let [ns (subs s 0 slash) nm (subs s (inc slash))
              hits (filter #(and (= (:name %) nm) (str/ends-with? (:ns %) (str "." ns))) (vals (:defs d)))]
          (case (count hits)
            1 (:id (first hits))
            0 (throw (js/Error. (str "no var " s " in this project")))
            (throw (js/Error. (str "\"" s "\" is more than one var: " (str/join ", " (map :id (take 5 hits))))))))))))

(defn- brief [d root id]
  (if (str/starts-with? id "file:")
    (let [f (subs id 5)] {:id id :kind "sql" :name (last (str/split f #"/")) :file f :folder (folder-of root (path/join root f)) :row 1})
    (let [def (get-in d [:defs id])]
      {:id id :kind "var" :ns (:ns def) :name (:name def) :file (rel root (:file def)) :folder (folder-of root (:file def))
       :row (get-in def [:v :row]) :private (get-in def [:v :private])})))

(defn- first-doc [d root id]
  (if (str/starts-with? id "file:")
    (let [first-line (first (file-lines d (path/join root (subs id 5))))]
      (when (str/starts-with? (str first-line) "--") (str/replace first-line #"^--\s*" "")))
    (some-> (get-in d [:defs id :v :doc]) (str/split #"\n") first str/trim not-empty)))

(defn- calls-of [d root id] (if (str/starts-with? id "file:") [] (concat (get-in d [:out id]) (sql-of d root id))))
(defn- callers-of [d root id] (if (str/starts-with? id "file:") (get (sql-callers d root) id []) (get-in d [:inc id] [])))

(defn calls-fp [root since]
  (.then (index root) (fn [i] (if (= since (:fp i)) {:same true} {:fp (:fp i)}))))

(defn calls-var
  "One var (or .sql file): its code, doc, what it calls (with where) and who calls it (with the line)."
  [root s]
  (.then (index root)
         (fn [i]
           (let [d (derive-of i)
                 id (resolve-id d root s)
                 me (brief d root id)
                 def (get-in d [:defs id])
                 abs (or (:file def) (path/join root (:file me)))
                 all (file-lines d abs)
                 lines (if def (subvec all (dec (get-in def [:v :row])) (min (count all) (get-in def [:v :endRow])))
                           (if (= "" (peek all)) (pop all) all))
                 callees (reduce (fn [m c] (update m (:to c) #(update (or % (assoc (brief d root (:to c)) :doc (first-doc d root (:to c)) :at []))
                                                                      :at conj (or (:at c) (select-keys c [:row :col :endCol])))))
                                 (array-map) (calls-of d root id))
                 callers (reduce (fn [m c]
                                   (if-let [from (get-in d [:defs (:from c)])]
                                     (let [row (or (get-in c [:at :row]) (:row c))]
                                       (update m (:from c) #(let [e (or % (assoc (brief d root (:from c)) :lines []))]
                                                              (if (some (fn [l] (= (:row l) row)) (:lines e)) e
                                                                  (update e :lines conj {:row row :text (str/trim (str (nth (file-lines d (:file from)) (dec row) "")))})))))
                                     m))
                                 {} (callers-of d root id))]
             (merge me
                    {:fp (:fp i) :endRow (if def (get-in def [:v :endRow]) (count lines)) :lang (last (str/split abs #"\."))
                     :varKind (get-in def [:v :kind]) :args (get-in def [:v :args] []) :doc (if def (get-in def [:v :doc]) (first-doc d root id))
                     :code (str/join "\n" lines)
                     :callees (vec (vals callees))
                     :callers (vec (sort #(.localeCompare (:id %1) (:id %2)) (vals callers)))})))))

(def ^:private per-step 30)

(defn calls-map
  "The neighbourhood of a var: down steps of calls and up steps of callers (callers negative), and
  every call between two of them."
  [root s up down]
  (.then (index root)
         (fn [i]
           (let [d (derive-of i)
                 start (resolve-id d root s)
                 dist (atom {start 0}) cut (atom {})
                 walk (fn [steps sign next]
                        (loop [k 1 front [start]]
                          (when (and (<= k steps) (seq front))
                            (let [found (vec (distinct (for [id front n (next id) :when (not (contains? @dist n))] n)))
                                  kept (subvec found 0 (min per-step (count found)))]
                              (when (> (count found) (count kept)) (swap! cut assoc (* sign k) (- (count found) (count kept))))
                              (doseq [n kept] (swap! dist assoc n (* sign k)))
                              (recur (inc k) kept)))))]
             (walk (max 0 (min 8 down)) 1 (fn [id] (map :to (calls-of d root id))))
             (walk (max 0 (min 8 up)) -1 (fn [id] (map :from (callers-of d root id))))
             {:fp (:fp i) :start start
              :nodes (vec (for [[id n] @dist] (assoc (brief d root id) :dist n)))
              :edges (vec (distinct (for [id (keys @dist) c (calls-of d root id) :when (contains? @dist (:to c))] [id (:to c)])))
              :cut @cut}))))
