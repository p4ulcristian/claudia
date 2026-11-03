(ns claudia.slave.timeline
  "The timeline: what the chat and every subagent did, when, from Claude Code's session files
  (<session>.jsonl and <session>/subagents/agent-<id>.jsonl + .meta.json). Claude Code appends to
  them as it goes, so each file is read once and then only from where the last read stopped; only a
  compact line per step is kept, and a step's full input, output and diff are read again from its
  place in the file when it is opened. Served as GET /api/chats/:id/timeline and
  /api/chats/:id/timeline/:lane/:step (PROTOCOL.md)."
  (:require ["node:fs/promises" :as fsp]
            [claudia.shared.text :as text]
            ["node:path" :as path]
            ["diff" :as jsdiff]
            [clojure.string :as str]
            [claudia.slave.history :as history]))

(def ^:private preview 300)
(def ^:private full 1000000)       ; larger inputs and outputs are cut here
(def ^:private patch-lines 5000)
(def ^:private chunk-size (* 4 1024 1024))
(def ^:private max-files 64)

(defn- ms [t] (if (string? t) (js/Date.parse t) js/NaN))

(def ^:private edit-call #{"Write" "Edit" "MultiEdit" "NotebookEdit"})

(defn- label
  "A tool's short name: mcp__picture__draw -> draw."
  [tool]
  (if (str/starts-with? tool "mcp__") (last (str/split tool #"__")) tool))

(defn- title
  "What a step works on: the one-line summary the chat shows, without the tool's name."
  [tool input]
  (let [s (history/tool-summary tool input nil)]
    (cond (str/starts-with? s (str tool " ")) (subs s (inc (count tool)))
          (str/starts-with? s (str (label tool) ": ")) (subs s (+ 2 (count (label tool))))
          (= s (label tool)) ""
          :else s)))

;; ---- patches: Claude Code's own (the result's structuredPatch), or made from a call's input ----

(defn- patch-of
  "A file edit as Claude Code saved it with the result, as JSON-ready data; nil when it holds none."
  [^js r]
  (when (and r (string? (.-filePath r)))
    (cond
      (and (= "create" (.-type r)) (string? (.-content r)))
      (let [all (str/split (str/replace (.-content r) #"\n$" "") #"\n" -1)]
        {:file (.-filePath r) :created true :cut (> (count all) patch-lines)
         :hunks [{:oldStart 0 :oldLines 0 :newStart 1 :newLines (count all)
                  :lines (mapv #(str "+" %) (take patch-lines all))}]})
      (array? (.-structuredPatch r))
      (let [left (atom patch-lines)
            hunks (vec (for [^js hk (.-structuredPatch r)
                             :while (pos? @left)
                             :let [lines (vec (take @left (.-lines hk)))]]
                         (do (swap! left - (.-length (.-lines hk)))
                             {:oldStart (.-oldStart hk) :oldLines (.-oldLines hk)
                              :newStart (.-newStart hk) :newLines (.-newLines hk) :lines lines})))]
        {:file (.-filePath r) :created false :hunks hunks :cut (neg? @left)}))))

(defn- input-patch
  "An edit's patch made from its call's input, for a file that keeps none of Claude Code's (a
  subagent's): the replaced text against the new, numbered within the text (where in the file it
  sits is not known); a Write is all its lines added."
  [tool ^js i]
  (let [file (str (or (some-> i .-file_path) (some-> i .-notebook_path) ""))]
    (when (and (seq file) (edit-call tool))
      (if (= tool "Write")
        (patch-of #js {:type "create" :filePath file :content (str (.-content i))})
        (let [nl #(if (str/ends-with? % "\n") % (str % "\n"))
              pairs (cond (and (= tool "MultiEdit") (array? (.-edits i)))
                          (for [^js e (.-edits i)] [(str (.-old_string e)) (str (.-new_string e))])
                          (= tool "NotebookEdit") [["" (str (or (.-new_source i) ""))]]
                          :else [[(str (or (.-old_string i) "")) (str (or (.-new_string i) ""))]])]
          {:file file :created false :cut false
           :hunks (vec (for [[a b] pairs
                             ^js hk (.-hunks (jsdiff/structuredPatch "a" "b" (nl a) (nl b) "" "" #js {:context 3}))]
                         {:oldStart (.-oldStart hk) :oldLines (.-oldLines hk)
                          :newStart (.-newStart hk) :newLines (.-newLines hk)
                          :lines (vec (take patch-lines (.-lines hk)))}))})))))

(defn- counts [patch]
  (when patch
    (let [lines (mapcat :lines (:hunks patch))]
      #js {:added (count (filter #(str/starts-with? % "+") lines))
           :removed (count (filter #(str/starts-with? % "-") lines))})))

(defn- result-counts
  "Lines added and removed by a saved result, counted in full (not cut)."
  [^js r]
  (when (and r (string? (.-filePath r)))
    (cond (and (= "create" (.-type r)) (string? (.-content r)))
          #js {:added (count (str/split (str/replace (.-content r) #"\n$" "") #"\n" -1)) :removed 0}
          (array? (.-structuredPatch r))
          (let [lines (mapcat #(.-lines ^js %) (.-structuredPatch r))]
            #js {:added (count (filter #(str/starts-with? % "+") lines))
                 :removed (count (filter #(str/starts-with? % "-") lines))}))))

;; ---- one file, read on from where the last read stopped ----

(defn- fresh []
  #js {:offset 0 :rest (js/Buffer.alloc 0) :restAt 0 :first 0 :last 0
       :events #js [] :tools (js/Map.) :locs (js/Map.) :edits (js/Map.)})

(defn- loc! [^js p id k loc]
  (let [l (or (.get (.-locs p) id) #js {})]
    (aset l k loc)
    (.set (.-locs p) id l)))

(defn- text-of [content]
  (cond (string? content) content
        (array? content) (str/join "\n" (keep #(when (= "text" (.-type ^js %)) (.-text ^js %)) content))
        :else ""))

(defn- take-line
  "One line of a session file into steps."
  [^js p line loc]
  (when-let [^js d (try (js/JSON.parse line) (catch :default _ nil))]
    (let [at (ms (.-timestamp d))
          kind (.-type d)]
      (when (and (#{"user" "assistant"} kind) (not (.-isMeta d)) (not (.-isCompactSummary d)) (js/isFinite at))
        (when (zero? (.-first p)) (set! (.-first p) at))
        (set! (.-last p) (max (.-last p) at))
        (let [content (some-> d .-message .-content)]
          (if (= kind "assistant")
            (when (array? content)
              (doseq [[n ^js b] (map-indexed vector content)]
                (case (.-type b)
                  "text" (when (seq (str/trim (str (.-text b))))
                           (let [id (str (.-uuid d) ":" n)]
                             (.push (.-events p) #js {:k "text" :id id :at at :text (text/fit (str/trim (.-text b)) preview)})
                             (loc! p id "use" loc)))
                  "tool_use" (when-not (.has (.-tools p) (.-id b))
                               (let [input (js->clj (.-input b) :keywordize-keys true)
                                     ev #js {:k "tool" :id (.-id b) :name (label (.-name b))
                                             :title (title (.-name b) input) :start at}]
                                 (.set (.-tools p) (.-id b) ev)
                                 (.push (.-events p) ev)
                                 (loc! p (.-id b) "use" loc)
                                 ;; Only a subagent's lines: the chat's own results carry Claude Code's patch.
                                 (when (and (.-isSidechain d) (edit-call (.-name b)))
                                   (when-let [c (counts (input-patch (.-name b) (.-input b)))]
                                     (.set (.-edits p) (.-id b) c)))))
                  nil)))
            (let [results (when (array? content) (filter #(= "tool_result" (.-type ^js %)) content))]
              (if (seq results)
                (doseq [^js b results
                        :let [^js ev (.get (.-tools p) (.-tool_use_id b))]
                        :when ev]
                  (set! (.-end ev) at)
                  (set! (.-error ev) (boolean (.-is_error b)))
                  ;; The saved result (with a file edit's patch) belongs to the line's only tool result.
                  (when-not (.-is_error b)
                    (when-let [c (or (when (= 1 (count results)) (result-counts (.-toolUseResult d)))
                                     (.get (.-edits p) (.-tool_use_id b)))]
                      (set! (.-diff ev) c)))
                  (.delete (.-edits p) (.-tool_use_id b))
                  (loc! p (.-tool_use_id b) "result" loc))
                (let [text (history/strip-reminders (text-of content))]
                  (cond
                    (not (seq text)) nil
                    (history/task-notice text)
                    (.push (.-events p) #js {:k "note" :id (.-uuid d) :at at :text (text/fit (history/task-notice text) preview)})
                    ;; Slash-command bookkeeping and similar tagged lines are not something anyone wrote.
                    (re-find #"^<[a-z-]+>" text) nil
                    :else
                    (do (.push (.-events p) #js {:k "user" :id (.-uuid d) :at at :text (text/fit text preview)})
                        (loc! p (.-uuid d) "use" loc))))))))))))

(defonce ^:private cache (atom {})) ; path -> {:p Parsed :used ms}

(defn- read-on
  "Reads a file's new whole lines into its Parsed; promise of it, nil when there is no file."
  [file]
  (-> (fsp/stat file)
      (.then
       (fn [^js st]
         (let [size (.-size st)
               hit (get @cache file)
               ^js p (if (or (nil? hit) (< size (.-offset ^js (:p hit)))) (fresh) (:p hit))]
           (swap! cache assoc file {:p p :used (js/Date.now)})
           (when (> (count @cache) max-files)
             (swap! cache #(into {} (take max-files (sort-by (fn [[_ v]] (- (:used v))) %)))))
           (if (<= size (.-offset p))
             p
             (-> (fsp/open file "r")
                 (.then
                  (fn [^js f]
                    (letfn [(step []
                              (if (>= (.-offset p) size)
                                (js/Promise.resolve p)
                                (let [len (min chunk-size (- size (.-offset p)))
                                      buf (js/Buffer.alloc len)]
                                  (-> (.read f buf 0 len (.-offset p))
                                      (.then (fn [^js r]
                                               (let [n (.-bytesRead r)]
                                                 (if (zero? n)
                                                   p
                                                   (let [data (if (pos? (.-length (.-rest p)))
                                                                (js/Buffer.concat #js [(.-rest p) (.subarray buf 0 n)])
                                                                (.subarray buf 0 n))
                                                         base (if (pos? (.-length (.-rest p))) (.-restAt p) (.-offset p))]
                                                     (loop [from 0]
                                                       (let [nl (.indexOf data 10 from)]
                                                         (if (neg? nl)
                                                           ;; The last line may still be being written: kept for next time.
                                                           (do (set! (.-rest p) (js/Buffer.from (.subarray data from)))
                                                               (set! (.-restAt p) (+ base from)))
                                                           (do (when (> nl from)
                                                                 ;; A line this reader does not understand is skipped, not the file.
                                                                 (try (take-line p (.toString data "utf8" from nl) #js [(+ base from) (- nl from)])
                                                                      (catch :default e
                                                                        (when-not (.-warned p)
                                                                          (set! (.-warned p) true)
                                                                          (js/console.warn "timeline:" file (.-message e))))))
                                                               (recur (inc nl))))))
                                                     (set! (.-offset p) (+ (.-offset p) n))
                                                     (step))))))))))]
                      (-> (step) (.finally #(.close f)))))))))))
      (.catch (fn [_] nil))))

;; ---- where a chat's files are ----

(def ^:private projects
  (path/join (or (.. js/process -env -CLAUDE_CONFIG_DIR) (path/join (.homedir (js/require "node:os")) ".claude")) "projects"))

(defonce ^:private dirs (atom {}))

(defn- file? [f] (-> (fsp/stat f) (.then (constantly true)) (.catch (constantly false))))

(defn- session-dir
  "The folder holding <id>.jsonl: the chat's working folder's, else any other project's."
  [cwd id]
  (if-let [d (get @dirs id)]
    (js/Promise.resolve d)
    (let [guess (path/dirname (history/session-file cwd id))]
      (-> (file? (path/join guess (str id ".jsonl")))
          (.then (fn [ok]
                   (if ok
                     guess
                     (-> (fsp/readdir projects) (.catch (constantly #js []))
                         (.then (fn [names]
                                  (reduce (fn [acc n]
                                            (.then acc (fn [found]
                                                         (or found
                                                             (let [d (path/join projects n)]
                                                               (.then (file? (path/join d (str id ".jsonl")))
                                                                      #(when % d)))))))
                                          (js/Promise.resolve nil) names)))))))
          (.then (fn [d] (when d (swap! dirs assoc id d)) d))))))

(defn- agent-files [dir id]
  (let [sub (path/join dir id "subagents")]
    (-> (fsp/readdir sub) (.catch (constantly #js []))
        (.then (fn [names]
                 (js/Promise.all
                  (into-array
                   (for [n names
                         :let [[_ aid] (re-matches #"agent-([A-Za-z0-9]+)\.jsonl" n)]
                         :when aid]
                     (-> (fsp/readFile (path/join sub (str "agent-" aid ".meta.json")) "utf8")
                         (.then #(js/JSON.parse %))
                         (.catch (constantly #js {}))
                         (.then (fn [meta]
                                  (.then (read-on (path/join sub n))
                                         (fn [p] {:id aid :meta meta :p p})))))))))))))

(defn- order
  "The chat first, then every agent right under the lane that started it, in start order."
  [lanes]
  (let [ids (set (map #(.-id ^js %) lanes))
        kids (fn [id] (sort-by #(.-start ^js %) (filter #(= id (.-parent ^js %)) lanes)))
        visit (fn visit [^js l] (cons l (mapcat visit (kids (.-id l)))))]
    (into-array (mapcat visit (filter #(or (nil? (.-parent ^js %)) (not (ids (.-parent ^js %)))) lanes)))))

(defn timeline
  "Promise of {v now lanes} (JSON-ready), or nil when the chat has no session file yet.
  running: the chat's turn runs now."
  [cwd id running]
  (-> (session-dir cwd id)
      (.then
       (fn [dir]
         (when dir
           (-> (js/Promise.all #js [(read-on (path/join dir (str id ".jsonl"))) (agent-files dir id)])
               (.then
                (fn [[^js main agents]]
                  (when main
                    (let [now (js/Date.now)
                          holder (js/Map.)
                          _ (do (.forEach (.-tools main) (fn [_ k] (.set holder k "main")))
                                (doseq [{aid :id ^js p :p} agents :when p]
                                  ;; The first holder wins: a fork's file starts with the call that made it.
                                  (.forEach (.-tools p) (fn [_ k] (when-not (.has holder k) (.set holder k aid))))))
                          by-id (into {} (map (juxt :id :p)) agents)
                          lanes (cons #js {:id "main" :title "Chat" :kind "main" :parent nil :toolUseId nil
                                           :start (.-first main) :end (if running now (.-last main))
                                           :running running :events (.-events main)}
                                      (for [{aid :id ^js meta :meta ^js p :p} agents
                                            :when (and p (pos? (.-first p)))
                                            :let [use (.-toolUseId meta)
                                                  parent (or (when use (let [h (.get holder use)] (when (not= h aid) h)))
                                                             (.-parentAgentId meta) "main")
                                                  ^js holder-p (if (= parent "main") main (get by-id parent))
                                                  ^js call (when (and use holder-p) (.get (.-tools holder-p) use))
                                                  ;; Running: its Agent call is still open while the chat works.
                                                  live (boolean (and running call (undefined? (.-end call))))]]
                                        #js {:id aid :title (or (.-description meta) "Agent") :kind (or (.-agentType meta) "agent")
                                             :parent parent :toolUseId use :start (.-first p)
                                             :end (if live now (.-last p)) :running live :events (.-events p)}))
                          started (into {} (keep #(when (.-toolUseId ^js %) [(.-toolUseId ^js %) (.-id ^js %)])) lanes)]
                      ;; The Agent call that started a lane points at it.
                      (doseq [^js l lanes ^js e (.-events l)]
                        (when-let [a (and (= "tool" (.-k e)) (get started (.-id e)))]
                          (set! (.-agent e) a)))
                      #js {:v (str/join "." (concat [(.-offset main)] (map #(or (some-> ^js (:p %) .-offset) 0) agents)
                                                    [(if running 1 0)]))
                           :now now
                           :lanes (order lanes)}))))))))))

;; ---- one step in full ----

(defn- read-loc [file ^js loc]
  (-> (fsp/open file "r")
      (.then (fn [^js f]
               (let [buf (js/Buffer.alloc (aget loc 1))]
                 (-> (.read f buf 0 (aget loc 1) (aget loc 0))
                     (.then #(js/JSON.parse (.toString buf "utf8")))
                     (.finally #(.close f))))))))

(defn detail
  "Promise of one step in full: {k tool, name, input, output, error, truncated, patch} or
  {k text, text, truncated}; nil when it is not there."
  [cwd id lane step]
  (-> (session-dir cwd id)
      (.then
       (fn [dir]
         (let [file (cond (nil? dir) nil
                          (= lane "main") (path/join dir (str id ".jsonl"))
                          (re-matches #"[A-Za-z0-9]+" lane) (path/join dir id "subagents" (str "agent-" lane ".jsonl")))]
           (when file
             (-> (read-on file)
                 (.then
                  (fn [^js p]
                    (let [^js loc (some-> p .-locs (.get step))]
                      (when (and loc (.-use loc))
                        (.then
                         (js/Promise.all #js [(read-loc file (.-use loc)) (if (.-result loc) (read-loc file (.-result loc)) nil)])
                         (fn [[^js line ^js r]]
                           (let [content (some-> line .-message .-content)]
                             (if-let [^js tool (.get (.-tools p) step)]
                               (let [^js use (some #(when (and (= "tool_use" (.-type ^js %)) (= step (.-id ^js %))) %) content)
                                     input (js/JSON.stringify (or (some-> use .-input) #js {}) nil 2)
                                     ^js b (when r (some #(when (and (= "tool_result" (.-type ^js %)) (= step (.-tool_use_id ^js %))) %)
                                                         (some-> r .-message .-content)))
                                     output (when r (history/result-text (js->clj (some-> b .-content) :keywordize-keys true)))
                                     patch (when (and r (.-diff tool))
                                             (or (patch-of (.-toolUseResult r)) (input-patch (str (.-name use)) (.-input use))))]
                                 {:k "tool" :name (.-name tool) :input (text/fit input full)
                                  :output (when output (text/fit output full)) :error (boolean (some-> b .-is_error))
                                  :truncated (or (> (count input) full) (> (count output) full))
                                  :patch patch})
                               (let [text (if (= "assistant" (.-type line))
                                            (str (some-> (aget content (js/Number (last (str/split step #":")))) .-text))
                                            (history/strip-reminders (text-of content)))]
                                 {:k "text" :text (text/fit text full) :truncated (> (count text) full)}))))))))))))))))
