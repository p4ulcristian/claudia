(ns claudia.slave.agentgit
  "An agent that ran in a git worktree of its own: its branch's commits since it was made, and their
  patches (one commit, or the whole change since it branched, uncommitted work too while the folder
  is there). Git only reads here."
  (:require ["node:child_process" :as cp]
            [claudia.node.fs :refer [dir?]]
            [clojure.string :as str]))

(def ^:private hash-re #"^[0-9a-f]{40}$")
(def ^:private branch-re #"^(?!-)(?!.*\.\.)[\w./-]{1,200}$")
(def ^:private max-patch (* 400 1024))

(defn- git [root args]
  (js/Promise.
   (fn [done]
     (cp/execFile "git" (clj->js (into ["-C" root] args))
                  #js {:maxBuffer (* 16 1024 1024) :timeout 30000
                       :env (js/Object.assign #js {} (.-env js/process) #js {:GIT_OPTIONAL_LOCKS "0"})}
                  (fn [err stdout] (done (if err "" (str stdout))))))))

(defn a-hash
  "The trimmed text when it is a full commit hash, else nil."
  [text]
  (let [h (str/trim (str text))] (when (re-matches hash-re h) h)))

(defn repo-of
  "The main repo's folder from git's common dir (trimmed text) or, when that does not tell, from
  the worktree's path (<repo>/.claude/worktrees/<name>); nil when neither tells."
  [common worktree]
  (if (str/ends-with? common "/.git")
    (subs common 0 (- (count common) 5))
    (second (re-matches #"^(.*)/\.claude/worktrees/[^/]+/?$" worktree))))

(defn parse-commits
  "git log's %H%x1f%s%x1f%ar%x1e records as [{:hash :subject :when}]."
  [log]
  (vec (for [rec (str/split log #"\x1e")
             :let [rec (str/trim rec)]
             :when (seq rec)
             :let [[h s w] (str/split rec #"\x1f")]]
         {:hash h :subject (or s "") :when (or w "")})))

(defn- made-at
  "Promise of where the branch was made (its oldest reflog entry), if that is still under tip."
  [root branch tip]
  (-> (git root ["reflog" "show" "--format=%H" (str "refs/heads/" branch) "--"])
      (.then (fn [log]
               (let [made (last (str/split-lines (str/trim log)))]
                 (if (and made (re-matches hash-re made))
                   (-> (git root ["merge-base" made tip])
                       (.then (fn [mb] (if (= (str/trim mb) made) made nil))))
                   nil))))))

(defn- base-of
  "Promise of the branch's base: where it was made, else where it meets the repo's HEAD."
  [root repo branch tip]
  (.then (made-at root branch tip)
         (fn [base] (or base (when repo (.then (git repo ["merge-base" tip "HEAD"]) a-hash))))))

(defn- commits-since [root base tip]
  (.then (if base
           (git root ["log" "--format=%H%x1f%s%x1f%ar%x1e" "-n" "200" (str base ".." tip)])
           (js/Promise.resolve ""))
         parse-commits))

(defn- work-at
  "Promise of the branch's work in root (repo = the main repo, here = the worktree folder is there)."
  [root repo branch here]
  (.then (git root ["rev-parse" "--verify" "--quiet" (str "refs/heads/" branch)])
         (fn [tip-text]
           (when-let [tip (a-hash tip-text)]
             (.then (base-of root repo branch tip)
                    (fn [base]
                      (.then (commits-since root base tip)
                             (fn [commits]
                               {:root root :branch branch :base base :worktree here :commits commits}))))))))

(defn branch-work
  "Promise of {:root :branch :base :commits [{:hash :subject :when}] :worktree (folder still there)}, or nil."
  [worktree branch]
  (if-not (and (string? worktree) (string? branch) (re-matches branch-re branch))
    (js/Promise.resolve nil)
    (let [here (dir? worktree)]
      (.then (if here (git worktree ["rev-parse" "--path-format=absolute" "--git-common-dir"]) (js/Promise.resolve ""))
             (fn [common]
               (let [repo (repo-of (str/trim common) worktree)
                     root (if here worktree repo)]
                 (when (and root (dir? root))
                   (work-at root repo branch here))))))))

(defn- cap [text]
  (if (> (count text) max-patch) (str (subs text 0 max-patch) "\n… (cut: too long to show)") text))

(defn patch
  "Promise of a patch text: rev = one of the branch's commits, or \"BRANCHED\" (everything since it
  branched)."
  [{:keys [root branch base worktree commits]} rev]
  (cond
    (= rev "BRANCHED")
    (if-not base
      (js/Promise.resolve nil)
      (.then (if worktree
               (git root ["diff" "--no-color" "-M" base])
               (git root ["diff" "--no-color" "-M" base (str "refs/heads/" branch)]))
             cap))
    (some #(= rev (:hash %)) commits)
    (.then (git root ["show" "--no-color" "-M" "--format=%s%n" rev]) cap)
    :else (js/Promise.resolve nil)))
