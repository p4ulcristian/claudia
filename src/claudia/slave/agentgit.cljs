(ns claudia.slave.agentgit
  "An agent that ran in a git worktree of its own: its branch's commits since it was made, and their
  patches (one commit, or the whole change since it branched, uncommitted work too while the folder
  is there). Git only reads here."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            [clojure.string :as str]))

(def ^:private hash-re #"^[0-9a-f]{40}$")
(def ^:private branch-re #"^(?!-)(?!.*\.\.)[\w./-]{1,200}$")
(def ^:private max-patch (* 400 1024))

(defn- git [root args]
  (js/Promise.
   (fn [resolve]
     (cp/execFile "git" (clj->js (into ["-C" root] args))
                  #js {:maxBuffer (* 16 1024 1024) :timeout 30000
                       :env (js/Object.assign #js {} (.-env js/process) #js {:GIT_OPTIONAL_LOCKS "0"})}
                  (fn [err stdout] (resolve (if err "" (str stdout))))))))

(defn- dir? [p] (try (.isDirectory (fs/statSync p)) (catch :default _ false)))

(defn branch-work
  "Promise of {:root :branch :base :commits [{:hash :subject :when}] :worktree (folder still there)}, or nil."
  [worktree branch]
  (if-not (and (string? worktree) (string? branch) (re-matches branch-re branch))
    (js/Promise.resolve nil)
    (let [here (dir? worktree)]
      (-> (if here (git worktree ["rev-parse" "--path-format=absolute" "--git-common-dir"]) (js/Promise.resolve ""))
          (.then
           (fn [common]
             (let [common (str/trim common)
                   repo (if (str/ends-with? common "/.git")
                          (subs common 0 (- (count common) 5))
                          (second (re-matches #"^(.*)/\.claude/worktrees/[^/]+/?$" worktree)))
                   root (if here worktree repo)]
               (if-not (and root (dir? root))
                 nil
                 (-> (git root ["rev-parse" "--verify" "--quiet" (str "refs/heads/" branch)])
                     (.then
                      (fn [tip]
                        (let [tip (str/trim tip)]
                          (when (re-matches hash-re tip)
                            (-> (git root ["reflog" "show" "--format=%H" (str "refs/heads/" branch) "--"])
                                (.then (fn [log]
                                         (let [made (last (str/split-lines (str/trim log)))]
                                           (if (and made (re-matches hash-re made))
                                             ;; Where the branch was made, if it is still under its tip.
                                             (-> (git root ["merge-base" made tip])
                                                 (.then (fn [mb] (if (= (str/trim mb) made) made nil))))
                                             nil))))
                                (.then (fn [base]
                                         (or base
                                             (when repo
                                               (-> (git repo ["merge-base" tip "HEAD"])
                                                   (.then #(let [mb (str/trim %)] (when (re-matches hash-re mb) mb))))))))
                                (.then (fn [base]
                                         (-> (if base
                                               (git root ["log" "--format=%H%x1f%s%x1f%ar%x1e" "-n" "200" (str base ".." tip)])
                                               (js/Promise.resolve ""))
                                             (.then (fn [log]
                                                      {:root root :branch branch :base base :worktree here
                                                       :commits (vec (for [rec (str/split log #"\x1e")
                                                                           :let [rec (str/trim rec)]
                                                                           :when (seq rec)
                                                                           :let [[h s w] (str/split rec #"\x1f")]]
                                                                       {:hash h :subject (or s "") :when (or w "")}))})))))))))))))))))))

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
