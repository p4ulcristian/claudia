(ns claudia.slave.git
  "The git form's reads: a repo's commit graph (lanes, refs, HEAD, uncommitted work) and one
  commit's files and patch. Read only: nothing here writes to a repo."
  (:require ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            [clojure.string :as str]))

(defn- git
  "Promise of git's stdout; allow-fail: an error exit gives what it printed (or \"\")."
  ([root args] (git root args false))
  ([root args allow-fail]
   (js/Promise.
    (fn [resolve reject]
      (cp/execFile "git" (clj->js (into ["-C" root] args))
                   #js {:maxBuffer (* 256 1024 1024) :timeout 30000
                        :env (js/Object.assign #js {} (.-env js/process) #js {:GIT_OPTIONAL_LOCKS "0"})}
                   (fn [err stdout stderr]
                     (cond (not err) (resolve (str stdout))
                           allow-fail (resolve (str stdout))
                           :else (reject (js/Error. (or (not-empty (str/trim (str stderr))) (str "git " (first args) " failed")))))))))))

(def ^:private F "\u001f")
(def ^:private R "\u001e")
(def rev-re #"^[\w./@^~-]+$")

(defn- all [& ps] (.then (js/Promise.all (into-array ps)) vec))

(defn- tracking
  "Each local branch: {:upstream :gone :ahead :behind}."
  [root]
  (.then (git root ["for-each-ref" "--format=%(refname:short)\t%(upstream:short)\t%(upstream:track)" "refs/heads"] true)
         (fn [out]
           (into {} (for [line (str/split-lines out) :when (seq (str/trim line))
                          :let [[nm up track] (str/split line #"\t")]]
                      [nm {:upstream (or up "") :gone (= track "[gone]")
                           :ahead (js/Number (or (second (re-find #"ahead (\d+)" (str track))) 0))
                           :behind (js/Number (or (second (re-find #"behind (\d+)" (str track))) 0))}])))))

(defn- parse-refs
  "\"HEAD -> main, origin/main, tag: v1\" as refs; origin/HEAD dropped, a remote twin of a local
  branch on the same commit folded into the local chip."
  [s tr]
  (let [parsed (for [r (map str/trim (str/split (or s "") #",")) :when (seq r) :when (not (str/ends-with? r "/HEAD"))]
                 (cond (str/starts-with? r "HEAD -> ") {:type "head" :name (subs r 8)}
                       (= r "HEAD") {:type "detached" :name "HEAD"}
                       (str/starts-with? r "tag: ") {:type "tag" :name (subs r 5)}
                       (and (str/includes? r "/") (not (get tr r))) {:type "remote" :name r}
                       :else {:type "branch" :name r}))
        local-here (set (map :name (filter #(#{"branch" "head"} (:type %)) parsed)))]
    (vec (for [r parsed
               :when (not (and (= "remote" (:type r)) (local-here (subs (:name r) (inc (str/index-of (:name r) "/"))))))]
           (if (#{"branch" "head"} (:type r))
             (let [t (get tr (:name r))]
               (assoc r :tracked (boolean (seq (:upstream t))) :gone (boolean (:gone t)) :ahead (or (:ahead t) 0) :behind (or (:behind t) 0)))
             r)))))

(defn- roster
  "Every branch once: a local one (\"both\" when it tracks a live upstream, else \"local\"), plus
  each remote branch no local branch tracks (\"remote\"); locals first."
  [root tr current]
  (.then (git root ["for-each-ref" "--format=%(refname)" "refs/remotes"] true)
         (fn [out]
           (let [ups (set (keep #(not-empty (:upstream %)) (vals tr)))
                 locals (for [[nm t] tr :let [remote (and (seq (:upstream t)) (not (:gone t)))]]
                          (assoc t :name nm :local true :remote (boolean remote) :kind (if remote "both" "local")
                                 :tracked (boolean (seq (:upstream t))) :current (= nm current)))
                 remotes (for [ref (str/split-lines out)
                               :let [nm (str/replace (str/trim ref) #"^refs/remotes/" "")]
                               :when (and (seq nm) (not (str/ends-with? nm "/HEAD")) (not (ups nm)))]
                           {:name nm :local false :remote true :kind "remote" :tracked false :gone false :ahead 0 :behind 0})]
             (vec (concat (sort-by :name locals) (sort-by :name remotes)))))))

(defn- working-copy [root]
  (.then (git root ["status" "--porcelain=v1" "--untracked-files=all"] true)
         (fn [out]
           (let [files (vec (for [line (str/split-lines out) :when (> (count line) 3)
                                  :let [x (subs line 0 1) y (subs line 1 2)
                                        p (last (str/split (subs line 3) #" -> "))]]
                              {:path p :x x :y y
                               :status (if (= "??" (str x y)) "?" (let [s (str/trim (if (not= y " ") y x))] (if (seq s) s "M")))}))]
             {:staged (count (filter #(and (not= "?" (:status %)) (not= " " (:x %))) files))
              :unstaged (count (filter #(and (not= "?" (:status %)) (not= " " (:y %))) files))
              :untracked (count (filter #(= "?" (:status %)) files))
              :total (count files)
              :files (mapv #(select-keys % [:path :status]) files)}))))

(defn- lanes
  "Each commit's lane (:col), newest first, children before parents; only parents in view get one."
  [commits]
  (let [present (set (map :hash commits))]
    (loop [cs commits lanes [] out []]
      (if-let [c (first cs)]
        (let [free #(or (first (keep-indexed (fn [i x] (when (nil? x) i)) %)) (count %))
              col (or (first (keep-indexed #(when (= %2 (:hash c)) %1) lanes)) (free lanes))
              lanes (mapv #(if (= % (:hash c)) nil %) (if (>= col (count lanes)) (conj lanes nil) lanes))
              parents (filter present (:parents c))
              lanes (if (empty? parents)
                      (assoc lanes col nil)
                      (reduce (fn [ls p] (if (some #{p} ls) ls (let [i (free ls)] (assoc (if (>= i (count ls)) (conj ls nil) ls) i p))))
                              (assoc lanes col (first parents))
                              (rest parents)))]
          (recur (rest cs) lanes (conj out (assoc c :col col))))
        out))))

(defn- colour-trunks
  "A commit on a branch's first-parent trunk gets that tip's colour key; merged-in history stays nil."
  [commits]
  (let [idx (into {} (map-indexed (fn [i c] [(:hash c) i])) commits)
        tip? (fn [types c] (some #(types (:type %)) (:refs c)))
        tips (concat (filter #(tip? #{"branch" "head"} %) commits)
                     (filter #(and (not (tip? #{"branch" "head"} %)) (tip? #{"remote"} %)) commits))]
    (first
     (reduce (fn [[cs n] tip]
               (loop [cs cs i (idx (:hash tip))]
                 (if (and i (nil? (:colorKey (nth cs i))))
                   (recur (assoc-in cs [i :colorKey] n) (some-> (first (:parents (nth cs i))) idx))
                   [cs (inc n)])))
             [(mapv #(assoc % :colorKey nil) commits) 0]
             tips))))

(defn fingerprint
  "A digest of everything the graph shows: refs, HEAD, the working tree."
  [root]
  (.then (all (git root ["for-each-ref" "--format=%(refname)%(objectname)"] true)
              (git root ["rev-parse" "HEAD"] true)
              (git root ["symbolic-ref" "--quiet" "HEAD"] true)
              (git root ["status" "--porcelain=v1" "--untracked-files=all"] true))
         (fn [parts] (-> (crypto/createHash "sha1") (.update (str/join "\u0000" parts)) (.digest "hex") (subs 0 16)))))

(defn graph
  "{:same true} when fp is still the repo's, else {:fp :commits :head :currentBranch :dirty :more}."
  [root {:keys [fp limit branches]}]
  (let [limit (max 1 (min 2000 (or (some-> limit js/parseInt (#(when-not (js/isNaN %) %))) 100)))]
    (-> (git root ["rev-parse" "--git-dir"])
        (.catch (fn [_] (throw (js/Error. "not a git repository"))))
        (.then #(fingerprint root))
        (.then
         (fn [now]
           (if (= now fp)
             {:same true}
             (-> (all (git root ["rev-parse" "HEAD"] true)
                      (git root ["symbolic-ref" "--quiet" "--short" "HEAD"] true)
                      (tracking root)
                      (working-copy root))
                 (.then (fn [[head current tr dirty]]
                          (.then (roster root tr (str/trim current)) #(vector head current tr dirty %))))
                 (.then
                  (fn [[head current tr dirty branches-list]]
                    (let [head (str/trim head)
                          wanted (when (seq branches) (filterv #(re-matches rev-re %) (str/split branches #",")))
                          revs (if (seq wanted) wanted ["--branches" "--tags" "--remotes" "HEAD"])
                          fmt (str (str/join F ["%H" "%P" "%an" "%ar" "%D" "%s" "%at"]) R)]
                      (if-not (seq head)
                        {:fp now :commits [] :head "" :currentBranch (str/trim current) :dirty dirty :more false :branches branches-list}
                        (.then (git root (concat ["log"] revs ["--topo-order" (str "--max-count=" (inc limit)) (str "--pretty=format:" fmt) "--"]))
                               (fn [out]
                                 (let [cs (vec (for [rec (map str/trim (str/split out (re-pattern R))) :when (seq rec)
                                                     :let [[h ps author when refs subject at] (str/split rec (re-pattern F))]]
                                                 {:hash h :parents (filterv seq (str/split (or ps "") #" ")) :author author :when when
                                                  :time (js/Number at) :refs (parse-refs refs tr) :subject subject}))
                                       more (> (count cs) limit)
                                       cs (if more (subvec cs 0 limit) cs)]
                                   {:fp now :commits (colour-trunks (lanes cs)) :head head :currentBranch (str/trim current)
                                    :detached (not (seq (str/trim current))) :dirty dirty :more more :branches branches-list}))))))))))))))

;; ---- one commit, or WORKING (the uncommitted changes) ----

(def ^:private budget (* 1536 1024))

(defn- name-status [out]
  (vec (for [line (str/split-lines out) :when (seq line)
             :let [[st a b] (str/split line #"\t")]]
         (if (#{"R" "C"} (subs st 0 1)) {:path b :oldPath a :status "R"} {:path a :oldPath nil :status (subs st 0 1)}))))

(defn- numstat [out]
  (into {} (for [line (str/split-lines out) :when (seq line)
                 :let [[add del & rest-parts] (str/split line #"\t")
                       raw (str/join "\t" rest-parts)
                       [_ pre _ new post] (re-matches #"^(.*)\{(.*) => (.*)\}(.*)$" raw)
                       p (if pre (str/replace (str pre new post) "//" "/") (or (second (re-matches #"^.* => (.*)$" raw)) raw))]]
             [p {:binary (= add "-") :additions (if (= add "-") 0 (js/Number add)) :deletions (if (= del "-") 0 (js/Number del))}])))

(defn- finish [meta files diff]
  (let [stats {:files (count files) :additions (reduce + (map :additions files)) :deletions (reduce + (map :deletions files))}]
    (if (<= (count diff) budget)
      {:meta meta :files files :diff diff :stats stats}
      (let [secs (str/split diff #"(?m)^(?=diff --git )")
            [kept _] (reduce (fn [[k size] s] (if (> (+ size (count s)) budget) [k size] [(conj k s) (+ size (count s))])) [[] 0] secs)
            paths (set (keep #(second (or (re-find #"(?m)^\+\+\+ (.+)$" %) (re-find #"(?m)^--- (.+)$" %))) kept))]
        {:meta meta :files (mapv #(if (paths (:path %)) % (assoc % :omitted true)) files)
         :diff (str/join kept) :truncated true :stats stats}))))

(defn commit-detail
  "{:meta :files :diff :stats} for a commit (or \"WORKING\"); with file, {:diff} of that one file."
  [root rev file]
  (cond
    (not (re-matches rev-re rev)) (js/Promise.reject (js/Error. "bad commit"))
    (and file (or (str/includes? file "\u0000") (str/starts-with? file "-"))) (js/Promise.reject (js/Error. "bad path"))
    file (.then (if (= rev "WORKING")
                  (git root ["diff" "HEAD" "--no-color" "--no-prefix" "--patch" "-M" "--" file] true)
                  (git root ["show" "--format=" "--diff-merges=first-parent" "--no-color" "--no-prefix" "--patch" "-M" "--end-of-options" rev "--" file]))
                (fn [d] {:diff d}))
    (= rev "WORKING")
    (.then (all (git root ["diff" "HEAD" "--no-color" "--numstat" "-M"] true)
                (git root ["diff" "HEAD" "--no-color" "--name-status" "-M"] true)
                (git root ["ls-files" "--others" "--exclude-standard"] true)
                (git root ["diff" "HEAD" "--no-color" "--no-prefix" "--patch" "-M"] true))
           (fn [[ns nst untracked diff]]
             (let [counts (numstat ns)
                   files (into (mapv #(merge % (get counts (:path %) {:binary false :additions 0 :deletions 0})) (name-status nst))
                               (for [f (str/split-lines untracked) :when (seq f)]
                                 {:path f :oldPath nil :status "?" :binary false :additions 0 :deletions 0}))]
               (finish {:hash "WORKING" :when "now" :subject "Uncommitted changes" :body "" :parents []} files diff))))
    :else
    (.then (git root ["rev-parse" "--verify" "--end-of-options" (str rev "^{commit}")])
           (fn [full]
             (let [full (str/trim full)]
               (.then (all (git root ["show" "-s" (str "--format=" (str/join F ["%H" "%an" "%ae" "%aI" "%ar" "%P" "%s" "%b"])) full])
                           (git root ["show" "--format=" "--diff-merges=first-parent" "--numstat" "-M" full])
                           (git root ["show" "--format=" "--diff-merges=first-parent" "--name-status" "-M" full])
                           (git root ["show" "--format=" "--diff-merges=first-parent" "--no-color" "--no-prefix" "--patch" "-M" full]))
                      (fn [[meta ns nst diff]]
                        (let [[h author email date when parents subject body] (str/split meta (re-pattern F))
                              counts (numstat ns)
                              files (mapv #(merge % (get counts (:path %) {:binary false :additions 0 :deletions 0})) (name-status nst))]
                          (finish {:hash h :author author :email email :date date :when when
                                   :parents (filterv seq (str/split (or parents "") #" ")) :subject subject :body (str/trim (or body ""))}
                                  files diff)))))))))
