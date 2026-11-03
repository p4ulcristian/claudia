(ns claudia.slave.changes
  "Files Claude changed, per turn: a copy of each file before Claude's Edit/Write tools first touch
  it (a PreToolUse hook), and a git snapshot of the chat's folder repo at the turn's start, written
  through a separate index file (the repo's own index, branches and stash are never touched), so
  shell edits count too. At the turn's end every touched file is compared with its copy and saved."
  (:require ["diff" :as jsdiff]
            [claudia.node.crypto :refer [sha256-hex]]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:fs/promises" :as fsp]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.slave.store :as store]))

(def edit-tools "Write|Edit|MultiEdit|NotebookEdit")
(def ^:private max-bytes 1000000)

;; Set by the runner: its data folder (the snapshot index files live in <data>/index).
(defonce data-dir (atom nil))

;; chat -> {:turn id :before {path {:text :skipped}} :trees {root (promise of tree or nil)}}
(defonce ^:private state (atom {}))

(defn line-counts
  "Lines added and removed from before to after."
  [before after]
  (reduce (fn [acc ^js part]
            (cond (.-added part) (update acc :added + (or (.-count part) 0))
                  (.-removed part) (update acc :removed + (or (.-count part) 0))
                  :else acc))
          {:added 0 :removed 0}
          (jsdiff/diffLines (or before "") (or after ""))))

(defn- binary? [^js buf] (not= -1 (.indexOf buf 0)))

(defn- read-state
  "A file as it is now: {:text} (nil text = it doesn't exist); big or binary files are not kept."
  [file]
  (-> (fsp/stat file)
      (.then (fn [^js st]
               (cond
                 (not (.isFile st)) {:text nil}
                 (> (.-size st) max-bytes) {:text nil :skipped "too large to show"}
                 :else (-> (fsp/readFile file)
                           (.then (fn [buf] (if (binary? buf)
                                              {:text nil :skipped "binary file"}
                                              {:text (.toString buf "utf8")})))))))
      (.catch (fn [_] {:text nil}))))

(defn- git
  "Promise of git's stdout as text, or nil when it failed."
  ([root args] (git root args nil))
  ([root args env]
   (js/Promise.
    (fn [done]
      (cp/execFile "git" (clj->js (into ["-C" root] args))
                   #js {:env (if env (js/Object.assign #js {} (.-env js/process) (clj->js env)) (.-env js/process))
                        :maxBuffer (* 64 1024 1024) :encoding "buffer" :timeout 60000}
                   (fn [err stdout] (done (when-not err stdout))))))))

(defn- text [buf] (some-> buf (.toString "utf8")))

(defn- repo-root [dir]
  (-> (git dir ["rev-parse" "--show-toplevel"]) (.then #(not-empty (str/trim (str (text %)))))))

(defn- index-file [chat root]
  (let [h (subs (sha256-hex root) 0 12)]
    (path/join (or @data-dir "/tmp") "index" (str chat "-" h))))

(defn- snapshot-tree
  "The folder's files as a git tree, through our own index (started from a copy of the real one, so
  git hashes only what changed)."
  [root index]
  (-> (git root ["rev-parse" "--path-format=absolute" "--git-path" "index"])
      (.then (fn [real]
               (let [real (str/trim (str (text real)))]
                 (fs/mkdirSync (path/dirname index) #js {:recursive true})
                 (if (and (seq real) (fs/existsSync real))
                   (fs/copyFileSync real index)
                   (fs/writeFileSync index ""))
                 (git root ["add" "-A"] {"GIT_INDEX_FILE" index}))))
      (.then (fn [ok] (when ok (git root ["write-tree"] {"GIT_INDEX_FILE" index}))))
      (.then #(not-empty (str/trim (str (text %)))))
      (.catch (fn [_] nil))))

(defn- tree-changes [root a b]
  (-> (git root ["diff-tree" "-r" "--no-renames" "--name-only" "-z" a b])
      (.then (fn [out] (->> (str/split (str (text out)) #"\x00") (remove str/blank?) (take 200)
                            (map (fn [rel] {:rel rel :full (path/join root rel)})))))))

(defn- blob-state [root tree rel]
  (-> (git root ["cat-file" "-s" (str tree ":" rel)])
      (.then (fn [size]
               (let [n (js/parseInt (str/trim (str (text size))) 10)]
                 (cond (js/isNaN n) {:text nil}
                       (> n max-bytes) {:text nil :skipped "too large to show"}
                       :else (-> (git root ["cat-file" "blob" (str tree ":" rel)])
                                 (.then (fn [buf] (cond (nil? buf) {:text nil}
                                                        (binary? buf) {:text nil :skipped "binary file"}
                                                        :else {:text (text buf)}))))))))))

(defn- snapshot! [chat folder]
  (when (seq folder)
    (-> (repo-root folder)
        (.then (fn [root]
                 (when (and root (not (get-in @state [chat :trees root])))
                   (swap! state assoc-in [chat :trees root] (snapshot-tree root (index-file chat root)))))))))

(defn begin-turn!
  "A turn starts: its id, and a snapshot of the chat's folder repo."
  [chat turn folder]
  (swap! state update chat (fn [s] (assoc (or s {}) :turn turn)))
  (snapshot! chat folder))

(defn folder-set!
  "The working folder changed mid-turn: its repo is snapshotted too."
  [chat folder running?]
  (when running? (snapshot! chat folder)))

(defn before-edit!
  "PreToolUse of an edit tool: a copy of the file before Claude first touches it in this turn."
  [chat input]
  (let [p (or (:file_path input) (:notebook_path input))]
    (if (and (string? p) (str/starts-with? p "/") (not (contains? (get-in @state [chat :before]) p)))
      (-> (read-state p)
          (.then (fn [s]
                   (when-not (contains? (get-in @state [chat :before]) p)
                     (swap! state assoc-in [chat :before p] s))
                   {:continue true})))
      (js/Promise.resolve {:continue true}))))

(defn- add-blobs
  "Promise of before with the snapshot tree's copy of every changed file it does not hold yet."
  [before root tree files]
  (reduce (fn [acc {:keys [rel full]}]
            (.then acc (fn [m]
                         (if (contains? m full)
                           m
                           (.then (blob-state root tree rel) #(assoc m full %))))))
          (js/Promise.resolve before) files))

(defn- with-tree-befores
  "Promise of before (path -> copy) with the files that changed in root since the turn's snapshot
  (tree-p, a promise of its tree), as they were then."
  [chat before root tree-p]
  (-> (js/Promise.all #js [tree-p (snapshot-tree root (index-file chat root))])
      (.then (fn [[a b]]
               (if (or (nil? a) (nil? b) (= a b))
                 before
                 (.then (tree-changes root a b) #(add-blobs before root a %)))))
      (.catch (fn [_] before))))

(defn same-state?
  "Whether a file's copy before and its state after are the same."
  [before after]
  (and (= (:text after) (:text before)) (= (:skipped after) (:skipped before))))

(defn change-row
  "The change row of path p in turn: b its copy before, a its state after, at = now (ms)."
  [turn p b a at]
  (merge {:turn (or turn "") :path p :before (:text b) :after (:text a)
          :status (cond (and (nil? (:text b)) (nil? (:skipped b))) "added"
                        (and (nil? (:text a)) (nil? (:skipped a))) "deleted"
                        :else "modified")
          :skipped (or (:skipped a) (:skipped b)) :at at}
         (line-counts (:text b) (:text a))))

(defn- save-changes!
  "Promise of the change rows saved for every touched file (path -> copy) that differs now."
  [chat turn touched]
  (reduce (fn [acc [p b]]
            (.then acc (fn [rows]
                         (.then (read-state p)
                                (fn [a]
                                  (if (same-state? b a)
                                    rows
                                    (conj rows (store/add-change! chat (change-row turn p b a (js/Date.now))))))))))
          (js/Promise.resolve []) touched))

(defn finish!
  "The turn ended: compares every touched file with its copy; promise of the new change rows."
  [chat]
  (let [{:keys [turn before trees]} (get @state chat)]
    (swap! state assoc chat {})
    (-> (reduce (fn [acc [root tree-p]] (.then acc #(with-tree-befores chat % root tree-p)))
                (js/Promise.resolve (or before {})) trees)
        (.then #(save-changes! chat turn %))
        (.catch (fn [e] (js/console.error "changes:" (str (or (.-message e) e))) [])))))
