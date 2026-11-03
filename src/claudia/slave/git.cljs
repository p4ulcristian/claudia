(ns claudia.slave.git
  "The git form's reads: a repo's commit graph (lanes, refs, HEAD, uncommitted work) and one
  commit's files and patch. Read only: nothing here writes to a repo."
  (:require ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.shared.git-layout :as layout]
            [claudia.slave.files :as slave-files]))

(defn- locale< [a b] (.localeCompare (str a) (str b)))

(defn- git
  "Promise of git's stdout; allow-fail: an error exit gives what it printed (or \"\")."
  ([root args] (git root args false))
  ([root args allow-fail]
   (js/Promise.
    (fn [done fail]
      (cp/execFile "git" (clj->js (into ["-C" root] args))
                   #js {:maxBuffer (* 256 1024 1024) :timeout 30000
                        :env (js/Object.assign #js {} (.-env js/process) #js {:GIT_OPTIONAL_LOCKS "0"})}
                   (fn [err stdout stderr]
                     (cond (not err) (done (str stdout))
                           allow-fail (done (str stdout))
                           :else (fail (js/Error. (or (not-empty (str/trim (str stderr))) (str "git " (first args) " failed")))))))))))

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
             (vec (concat (sort-by :name locale< locals) (sort-by :name locale< remotes)))))))

(defn- working-copy
  "Uncommitted work: staged / unstaged / untracked counts and the files (one git status). A file
  both staged and changed again counts in both."
  [root]
  (.then (git root ["status" "--porcelain=v1" "--untracked-files=all"] true)
         (fn [out]
           (let [rows (for [line (str/split out #"\n") :when (seq line)
                            :let [x (subs line 0 1) y (subs line 1 (min 2 (count line)))
                                  p (last (str/split (subs line (min 3 (count line))) #" -> "))]]
                        (if (= "??" (str x y))
                          {:path p :status "?" :untracked true}
                          {:path p :status (let [s (str/trim (if (not= y " ") y x))] (if (seq s) s "M"))
                           :staged (not= x " ") :unstaged (not= y " ")}))
                 staged (count (filter :staged rows))
                 unstaged (count (filter :unstaged rows))
                 untracked (count (filter :untracked rows))]
             {:staged staged :unstaged unstaged :untracked untracked :total (+ staged unstaged untracked)
              :files (mapv #(select-keys % [:path :status]) rows)}))))

(defn- last-fetched
  "When the repo last fetched (FETCH_HEAD's time, ISO), or nil."
  [root]
  (.then (git root ["rev-parse" "--absolute-git-dir"] true)
         (fn [dir]
           (let [dir (str/trim dir)]
             (when (seq dir)
               (try (.toISOString (.-mtime (fs/statSync (path/join dir "FETCH_HEAD"))))
                    (catch :default _ nil)))))))

(defn fingerprint
  "A digest of everything the graph shows: refs, HEAD, the working tree."
  [root]
  (.then (all (git root ["for-each-ref" "--format=%(refname)%(objectname)"] true)
              (git root ["rev-parse" "HEAD"] true)
              (git root ["symbolic-ref" "--quiet" "HEAD"] true)
              (git root ["status" "--porcelain=v1" "--untracked-files=all"] true))
         (fn [parts] (-> (crypto/createHash "sha1") (.update (str/join "\u0000" parts)) (.digest "hex") (subs 0 16)))))

(defn log-revs
  "What the log walks: the branches asked for that still exist (a stale filter can't break the log;
  branches: comma-separated names), else every branch, tag and remote and HEAD."
  [branches branches-list]
  (let [known (set (map :name branches-list))
        wanted (when (seq branches) (filterv known (str/split branches #",")))]
    (if (seq wanted) wanted ["--branches" "--tags" "--remotes" "HEAD"])))

(def ^:private log-format (str (str/join F ["%H" "%P" "%an" "%ar" "%D" "%s" "%at"]) R))

(defn parse-log
  "git log's records (log-format) as commits; tr: the local branches' tracking, for the refs."
  [out tr]
  (vec (for [rec (map str/trim (str/split out (re-pattern R))) :when (seq rec)
             :let [[h ps author ago refs subject at] (str/split rec (re-pattern F))]]
         {:hash h :parents (filterv seq (str/split (or ps "") #" ")) :author author :when ago
          :time (js/Number at) :refs (parse-refs refs tr) :subject subject})))

(defn- graph-of
  "Promise of the graph at fingerprint now: the log of at most limit commits, laid out."
  [root now limit branches [head current tr dirty fetched branches-list]]
  (let [head (str/trim head)
        base {:fp now :head head :currentBranch (str/trim current) :dirty dirty :branches branches-list :fetched fetched}]
    (if-not (seq head)
      (merge base {:commits [] :head "" :detached false :more false})
      (.then (git root (concat ["log"] (log-revs branches branches-list)
                               ["--topo-order" (str "--max-count=" (inc limit)) (str "--pretty=format:" log-format) "--"]))
             (fn [out]
               (let [cs (parse-log out tr)
                     more (> (count cs) limit)]
                 (merge base {:commits (layout/layout-graph (if more (subvec cs 0 limit) cs))
                              :detached (not (seq (str/trim current))) :more more})))))))

(defn- repo-state
  "Promise of [head current-branch tracking working-copy last-fetched branches]."
  [root]
  (-> (all (git root ["rev-parse" "HEAD"] true)
           (git root ["symbolic-ref" "--quiet" "--short" "HEAD"] true)
           (tracking root)
           (working-copy root)
           (last-fetched root))
      (.then (fn [[head current tr dirty fetched]]
               (.then (roster root tr (str/trim current)) #(vector head current tr dirty fetched %))))))

(defn graph
  "{:same true} when fp is still the repo's, else {:fp :commits :head :currentBranch :dirty :more}."
  [root {:keys [fp limit branches]}]
  (let [limit (max 1 (min 2000 (or (some-> limit js/parseInt (#(when-not (js/isNaN %) %))) 100)))]
    (-> (git root ["rev-parse" "--git-dir"])
        (.catch (fn [_] (throw (js/Error. "not a git repository"))))
        (.then #(fingerprint root))
        (.then (fn [now]
                 (if (= now fp)
                   {:same true}
                   (.then (repo-state root) #(graph-of root now limit branches %))))))))

;; ---- one commit, or WORKING (the uncommitted changes) ----

(def ^:private max-lines
  "Beyond this many changed lines the patch isn't made at all (it can be hundreds of MB); the files
  load one at a time."
  20000)
(def ^:private budget
  "Beyond this many bytes of patch only the first files' patches come."
  (* 1.5 1024 1024))

(defn- too-big? [files] (> (reduce + (map #(+ (:additions %) (:deletions %)) files)) max-lines))

(defn- name-status
  "\"M\tpath\", \"A\tpath\", \"D\tpath\", \"R100\told\tnew\"."
  [out]
  (vec (for [line (str/split out #"\n") :when (seq line)
             :let [[st a b] (str/split line #"\t")]]
         (if (#{"R" "C"} (subs st 0 1)) {:path b :oldPath a :status "R"} {:path a :oldPath nil :status (subs st 0 1)}))))

(defn- numstat
  "\"12\t3\tpath\" per file (\"-\t-\tpath\" when binary; renames as \"old => new\" or \"dir/{a => b}/f\")."
  [out]
  (into {} (for [line (str/split out #"\n") :when (seq line)
                 :let [[add del & rest-parts] (str/split line #"\t")
                       raw (str/join "\t" rest-parts)
                       [_ pre _ renamed post] (re-matches #"^(.*)\{(.*) => (.*)\}(.*)$" raw)
                       p (if pre (str/replace (str pre renamed post) "//" "/") (or (second (re-matches #"^.* => (.*)$" raw)) raw))]]
             [p {:binary (= add "-") :additions (if (= add "-") 0 (js/Number add)) :deletions (if (= del "-") 0 (js/Number del))}])))

(defn- merge-files [counts statuses]
  (mapv #(merge % (get counts (:path %) {:binary false :additions 0 :deletions 0})) statuses))

(defn- finish
  "The detail with its stats; diff nil: too big to make, every file loads on its own."
  [info files diff]
  (let [stats {:files (count files) :additions (reduce + (map :additions files)) :deletions (reduce + (map :deletions files))}]
    (cond
      (nil? diff) {:meta info :files (mapv #(assoc % :omitted true) files) :diff "" :truncated true :stats stats}
      (<= (count diff) budget) {:meta info :files files :diff diff :stats stats}
      :else
      (let [secs (str/split diff #"(?m)^(?=diff --git )")
            [kept _] (reduce (fn [[k size] s] (if (> (+ size (count s)) budget) [k size] [(conj k s) (+ size (count s))])) [[] 0] secs)
            paths (set (keep #(let [m (second (or (re-find #"(?m)^\+\+\+ (.+)$" %) (re-find #"(?m)^--- (.+)$" %)))]
                                (when (not= m "/dev/null") m))
                             kept))]
        {:meta info :files (mapv #(if (paths (:path %)) % (assoc % :omitted true)) files)
         :diff (str/join kept) :truncated true :stats stats}))))

(defn- line-count
  "A file's line count, nil when it is binary."
  [full]
  (try (slave-files/line-count (fs/readFileSync full)) (catch :default _ 0)))

(defn- working-detail
  "Everything not committed: tracked changes against HEAD (staged and not) plus untracked files as
  additions."
  [root]
  (.then (all (git root ["diff" "HEAD" "--no-color" "--numstat" "-M"] true)
              (git root ["diff" "HEAD" "--no-color" "--name-status" "-M"] true)
              (git root ["ls-files" "--others" "--exclude-standard"] true)
              (git root ["rev-parse" "HEAD"] true))
         (fn [[counts nst untracked-raw head]]
           (let [untracked (filterv seq (str/split untracked-raw #"\n"))
                 files (into (merge-files (numstat counts) (name-status nst))
                             (for [f untracked :let [n (line-count (path/join root f))]]
                               {:path f :oldPath nil :status "?" :binary (nil? n) :additions (or n 0) :deletions 0}))
                 info {:hash "WORKING" :author "" :email "" :date (.toISOString (js/Date.)) :when "now"
                       :parents (if (seq (str/trim head)) [(str/trim head)] []) :subject "Uncommitted changes" :body ""}]
             (if (too-big? files)
               (finish info files nil)
               (-> (git root ["diff" "HEAD" "--no-color" "--no-prefix" "--patch" "-M"] true)
                   ;; Untracked files: each one's patch against /dev/null (git exits 1 on it), one by one.
                   (.then (fn [diff]
                            (reduce (fn [p f]
                                      (.then p (fn [acc]
                                                 (.then (git root ["diff" "--no-index" "--no-color" "--no-prefix" "/dev/null" f] true)
                                                        (fn [d] (if (seq d) (str acc (when (and (seq acc) (not (str/ends-with? acc "\n"))) "\n") d) acc))))))
                                    (js/Promise.resolve diff) untracked)))
                   (.then #(finish info files %))))))))

(defn commit-detail
  "{:meta :files :diff :stats} for a commit (or \"WORKING\"); with file, {:diff} of that one file."
  [root rev file]
  (cond
    (not (re-matches rev-re rev)) (js/Promise.reject (js/Error. "bad commit"))
    (and file (or (str/includes? file "\u0000") (str/starts-with? file "-"))) (js/Promise.reject (js/Error. "bad path"))
    file (.then (if (= rev "WORKING")
                  (.then (git root ["diff" "HEAD" "--no-color" "--no-prefix" "--patch" "-M" "--" file] true)
                         (fn [tracked] (if (seq tracked) tracked (git root ["diff" "--no-index" "--no-color" "--no-prefix" "/dev/null" file] true))))
                  (git root ["show" "--format=" "--diff-merges=first-parent" "--no-color" "--no-prefix" "--patch" "-M" "--end-of-options" rev "--" file]))
                (fn [d] {:diff d}))
    (= rev "WORKING") (working-detail root)
    :else
    (.then (git root ["rev-parse" "--verify" "--end-of-options" (str rev "^{commit}")])
           (fn [full]
             (let [full (str/trim full)]
               ;; Merges diff against their first parent (the combined diff git show prints is usually empty).
               (.then (all (git root ["show" "-s" (str "--format=" (str/join F ["%H" "%an" "%ae" "%aI" "%ar" "%P" "%s" "%b"])) full])
                           (git root ["show" "--format=" "--diff-merges=first-parent" "--numstat" "-M" full])
                           (git root ["show" "--format=" "--diff-merges=first-parent" "--name-status" "-M" full]))
                      (fn [[info counts nst]]
                        (let [[h author email date ago parent-list subject body] (str/split info (re-pattern F))
                              files (merge-files (numstat counts) (name-status nst))
                              m {:hash h :author author :email email :date date :when ago
                                 :parents (filterv seq (str/split (or parent-list "") #" ")) :subject subject :body (str/trim (or body ""))}]
                          (if (too-big? files)
                            (finish m files nil)
                            (.then (git root ["show" "--format=" "--diff-merges=first-parent" "--no-color" "--no-prefix" "--patch" "-M" full])
                                   #(finish m files %)))))))))))
