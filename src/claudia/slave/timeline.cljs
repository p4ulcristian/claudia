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


(defn- hunk
  "A hunk (JS) as JSON-ready data with the given lines."
  [^js hk lines]
  {:oldStart (.-oldStart hk) :oldLines (.-oldLines hk)
   :newStart (.-newStart hk) :newLines (.-newLines hk) :lines lines})

(defn cut-hunks
  "Hunks (JS) as JSON-ready data, their lines cut so they hold at most patch-lines in all: [hunks
  cut?]."
  [hunks]
  (loop [hs (seq hunks) left patch-lines out []]
    (if (and hs (pos? left))
      (let [^js hk (first hs)]
        (recur (next hs) (- left (.-length (.-lines hk))) (conj out (hunk hk (vec (take left (.-lines hk)))))))
      [out (neg? left)])))

(defn- created-patch
  "A created file's patch: all its lines added."
  [file content]
  (let [all (str/split (str/replace content #"\n$" "") #"\n" -1)]
    {:file file :created true :cut (> (count all) patch-lines)
     :hunks [{:oldStart 0 :oldLines 0 :newStart 1 :newLines (count all)
              :lines (mapv #(str "+" %) (take patch-lines all))}]}))

(defn- patch-of
  "A file edit as Claude Code saved it with the result, as JSON-ready data; nil when it holds none."
  [^js r]
  (when (and r (string? (.-filePath r)))
    (cond
      (and (= "create" (.-type r)) (string? (.-content r)))
      (created-patch (.-filePath r) (.-content r))
      (array? (.-structuredPatch r))
      (let [[hunks cut] (cut-hunks (.-structuredPatch r))]
        {:file (.-filePath r) :created false :hunks hunks :cut cut}))))

(defn- edit-pairs
  "An edit call's input (JS) as [replaced new] text pairs."
  [tool ^js i]
  (cond (and (= tool "MultiEdit") (array? (.-edits i)))
        (for [^js e (.-edits i)] [(str (.-old_string e)) (str (.-new_string e))])
        (= tool "NotebookEdit") [["" (str (or (.-new_source i) ""))]]
        :else [[(str (or (.-old_string i) "")) (str (or (.-new_string i) ""))]]))

(defn- input-patch
  "An edit's patch made from its call's input, for a file that keeps none of Claude Code's (a
  subagent's): the replaced text against the new, numbered within the text (where in the file it
  sits is not known); a Write is all its lines added."
  [tool ^js i]
  (let [file (str (or (some-> i .-file_path) (some-> i .-notebook_path) ""))
        nl #(if (str/ends-with? % "\n") % (str % "\n"))]
    (when (and (seq file) (edit-call tool))
      (if (= tool "Write")
        (patch-of #js {:type "create" :filePath file :content (str (.-content i))})
        {:file file :created false :cut false
         :hunks (vec (for [[a b] (edit-pairs tool i)
                           ^js hk (.-hunks (jsdiff/structuredPatch "a" "b" (nl a) (nl b) "" "" #js {:context 3}))]
                       (hunk hk (vec (take patch-lines (.-lines hk))))))}))))

(defn- line-counts
  "Diff lines as #js {:added :removed}."
  [lines]
  #js {:added (count (filter #(str/starts-with? % "+") lines))
       :removed (count (filter #(str/starts-with? % "-") lines))})

(defn- counts [patch]
  (when patch (line-counts (mapcat :lines (:hunks patch)))))

(defn- result-counts
  "Lines added and removed by a saved result, counted in full (not cut)."
  [^js r]
  (when (and r (string? (.-filePath r)))
    (cond (and (= "create" (.-type r)) (string? (.-content r)))
          #js {:added (count (str/split (str/replace (.-content r) #"\n$" "") #"\n" -1)) :removed 0}
          (array? (.-structuredPatch r))
          (line-counts (mapcat #(.-lines ^js %) (.-structuredPatch r))))))

;; ---- one file, read on from where the last read stopped ----
;; A file's Parsed is one mutable JS object, grown in place as lines arrive (its tool events are
;; shared between :events and :tools, and get their end, error and diff when the result comes).

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

(defn- take-text!
  "An assistant text block (the n-th of its line) as a text event."
  [^js p ^js d n ^js b at loc]
  (when (seq (str/trim (str (.-text b))))
    (let [id (str (.-uuid d) ":" n)]
      (.push (.-events p) #js {:k "text" :id id :at at :text (text/fit (str/trim (.-text b)) preview)})
      (loc! p id "use" loc))))

(defn- take-call!
  "A tool_use block as a tool event (once per call id)."
  [^js p ^js d ^js b at loc]
  (when-not (.has (.-tools p) (.-id b))
    (let [input (js->clj (.-input b) :keywordize-keys true)
          ev #js {:k "tool" :id (.-id b) :name (label (.-name b)) :title (title (.-name b) input) :start at}]
      (.set (.-tools p) (.-id b) ev)
      (.push (.-events p) ev)
      (loc! p (.-id b) "use" loc)
      ;; Only a subagent's lines: the chat's own results carry Claude Code's patch.
      (when (and (.-isSidechain d) (edit-call (.-name b)))
        (when-let [c (counts (input-patch (.-name b) (.-input b)))]
          (.set (.-edits p) (.-id b) c))))))

(defn- take-assistant! [^js p ^js d content at loc]
  (when (array? content)
    (doseq [[n ^js b] (map-indexed vector content)]
      (case (.-type b)
        "text" (take-text! p d n b at loc)
        "tool_use" (take-call! p d b at loc)
        nil))))

(defn- take-results!
  "A user line's tool results: each ends its call's event (with its diff counts)."
  [^js p ^js d results at loc]
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
    (loc! p (.-tool_use_id b) "result" loc)))

(defn- take-user-text!
  "A user line's text as a note (a task notice) or a user event."
  [^js p ^js d content at loc]
  (let [text (history/strip-reminders (text-of content))]
    (cond
      (not (seq text)) nil
      (history/task-notice text)
      (.push (.-events p) #js {:k "note" :id (.-uuid d) :at at :text (text/fit (history/task-notice text) preview)})
      ;; Slash-command bookkeeping and similar tagged lines are not something anyone wrote.
      (re-find #"^<[a-z-]+>" text) nil
      :else
      (do (.push (.-events p) #js {:k "user" :id (.-uuid d) :at at :text (text/fit text preview)})
          (loc! p (.-uuid d) "use" loc)))))

(defn- take-line
  "One line of a session file into steps."
  [^js p line loc]
  (when-let [^js d (try (js/JSON.parse line) (catch :default _ nil))]
    (let [at (ms (.-timestamp d))
          kind (.-type d)
          content (some-> d .-message .-content)]
      (when (and (#{"user" "assistant"} kind) (not (.-isMeta d)) (not (.-isCompactSummary d)) (js/isFinite at))
        (when (zero? (.-first p)) (set! (.-first p) at))
        (set! (.-last p) (max (.-last p) at))
        (if (= kind "assistant")
          (take-assistant! p d content at loc)
          (let [results (when (array? content) (filter #(= "tool_result" (.-type ^js %)) content))]
            (if (seq results)
              (take-results! p d results at loc)
              (take-user-text! p d content at loc))))))))

(defonce ^:private cache (atom {})) ; path -> {:p Parsed :used ms}

(defn- cached!
  "The file's Parsed to read on (a fresh one when the file is new or shrank), marked used; the
  cache keeps the max-files used last."
  [file size]
  (let [hit (get @cache file)
        ^js p (if (or (nil? hit) (< size (.-offset ^js (:p hit)))) (fresh) (:p hit))]
    (swap! cache assoc file {:p p :used (js/Date.now)})
    (when (> (count @cache) max-files)
      (swap! cache #(into {} (take max-files (sort-by (fn [[_ v]] (- (:used v))) %)))))
    p))

(defn- take-lines!
  "The whole lines of data (starting at file offset base) into p; the last, unfinished one is kept
  for the next read."
  [^js p data base file]
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
            (recur (inc nl)))))))

(defn- read-chunks
  "Reads the open file f on from p's offset to size, a chunk at a time; promise of p."
  [^js f ^js p size file]
  (if (>= (.-offset p) size)
    (js/Promise.resolve p)
    (let [len (min chunk-size (- size (.-offset p)))
          buf (js/Buffer.alloc len)]
      (-> (.read f buf 0 len (.-offset p))
          (.then (fn [^js r]
                   (let [n (.-bytesRead r)
                         rest? (pos? (.-length (.-rest p)))]
                     (if (zero? n)
                       p
                       (do (take-lines! p (if rest? (js/Buffer.concat #js [(.-rest p) (.subarray buf 0 n)]) (.subarray buf 0 n))
                                        (if rest? (.-restAt p) (.-offset p))
                                        file)
                           (set! (.-offset p) (+ (.-offset p) n))
                           (read-chunks f p size file))))))))))

(defn- read-on
  "Reads a file's new whole lines into its Parsed; promise of it, nil when there is no file."
  [file]
  (-> (fsp/stat file)
      (.then
       (fn [^js stat]
         (let [size (.-size stat)
               ^js p (cached! file size)]
           (if (<= size (.-offset p))
             p
             (-> (fsp/open file "r")
                 (.then (fn [^js f] (-> (read-chunks f p size file) (.finally #(.close f))))))))))
      (.catch (fn [_] nil))))

;; ---- where a chat's files are ----

(defonce ^:private dirs (atom {}))

(defn- file? [f] (-> (fsp/stat f) (.then (constantly true)) (.catch (constantly false))))

(defn- any-project-dir
  "The first projects folder holding <id>.jsonl, or nil."
  [id]
  (let [projects (history/projects-root)]
    (-> (fsp/readdir projects) (.catch (constantly #js []))
        (.then (fn [names]
                 (reduce (fn [acc n]
                           (.then acc (fn [found]
                                        (or found
                                            (let [d (path/join projects n)]
                                              (.then (file? (path/join d (str id ".jsonl")))
                                                     #(when % d)))))))
                         (js/Promise.resolve nil) names))))))

(defn- session-dir
  "The folder holding <id>.jsonl: the chat's working folder's, else any other project's."
  [cwd id]
  (if-let [d (get @dirs id)]
    (js/Promise.resolve d)
    (let [guess (path/dirname (history/session-file cwd id))]
      (-> (file? (path/join guess (str id ".jsonl")))
          (.then (fn [ok] (if ok guess (any-project-dir id))))
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
                         (.then (fn [info]
                                  (.then (read-on (path/join sub n))
                                         (fn [p] {:id aid :meta info :p p})))))))))))))

(defn- order
  "The chat first, then every agent right under the lane that started it, in start order."
  [lanes]
  (let [ids (set (map #(.-id ^js %) lanes))
        kids (fn [id] (sort-by #(.-start ^js %) (filter #(= id (.-parent ^js %)) lanes)))
        visit (fn visit [^js l] (cons l (mapcat visit (kids (.-id l)))))]
    (into-array (mapcat visit (filter #(or (nil? (.-parent ^js %)) (not (ids (.-parent ^js %)))) lanes)))))

(defn- call-holders
  "Tool call id -> the lane whose file holds the call (\"main\" or an agent's id). The first holder
  wins: a fork's file starts with the call that made it."
  [^js main agents]
  (let [holder (js/Map.)]
    (.forEach (.-tools main) (fn [_ k] (.set holder k "main")))
    (doseq [{aid :id ^js p :p} agents :when p]
      (.forEach (.-tools p) (fn [_ k] (when-not (.has holder k) (.set holder k aid)))))
    holder))

(defn- agent-lane
  "An agent's lane: under the lane holding the call that started it (else its parent agent, else
  the chat); running while that call is still open in a running chat."
  [^js holder ^js main by-id running now {aid :id ^js info :meta ^js p :p}]
  (let [call-id (.-toolUseId info)
        parent (or (when call-id (let [h (.get holder call-id)] (when (not= h aid) h)))
                   (.-parentAgentId info) "main")
        ^js holder-p (if (= parent "main") main (get by-id parent))
        ^js call (when (and call-id holder-p) (.get (.-tools holder-p) call-id))
        live (boolean (and running call (undefined? (.-end call))))]
    #js {:id aid :title (or (.-description info) "Agent") :kind (or (.-agentType info) "agent")
         :parent parent :toolUseId call-id :start (.-first p)
         :end (if live now (.-last p)) :running live :events (.-events p)}))

(defn- point-calls!
  "The Agent call that started a lane points at it."
  [lanes]
  (let [started (into {} (keep #(when (.-toolUseId ^js %) [(.-toolUseId ^js %) (.-id ^js %)])) lanes)]
    (doseq [^js l lanes ^js e (.-events l)]
      (when-let [a (and (= "tool" (.-k e)) (get started (.-id e)))]
        (set! (.-agent e) a)))))

(defn- lanes-of
  "{v now lanes} from the chat's Parsed and its agents' ({:id :meta :p})."
  [^js main agents running]
  (let [now (js/Date.now)
        holder (call-holders main agents)
        by-id (into {} (map (juxt :id :p)) agents)
        lanes (cons #js {:id "main" :title "Chat" :kind "main" :parent nil :toolUseId nil
                         :start (.-first main) :end (if running now (.-last main))
                         :running running :events (.-events main)}
                    (for [a agents :when (and (:p a) (pos? (.-first ^js (:p a))))]
                      (agent-lane holder main by-id running now a)))]
    (point-calls! lanes)
    #js {:v (str/join "." (concat [(.-offset main)] (map #(or (some-> ^js (:p %) .-offset) 0) agents)
                                  [(if running 1 0)]))
         :now now
         :lanes (order lanes)}))

(defn timeline
  "Promise of {v now lanes} (JSON-ready), or nil when the chat has no session file yet.
  running: the chat's turn runs now."
  [cwd id running]
  (-> (session-dir cwd id)
      (.then
       (fn [dir]
         (when dir
           (-> (js/Promise.all #js [(read-on (path/join dir (str id ".jsonl"))) (agent-files dir id)])
               (.then (fn [[^js main agents]] (when main (lanes-of main agents running))))))))))

;; ---- one step in full ----

(defn- read-loc [file ^js loc]
  (-> (fsp/open file "r")
      (.then (fn [^js f]
               (let [buf (js/Buffer.alloc (aget loc 1))]
                 (-> (.read f buf 0 (aget loc 1) (aget loc 0))
                     (.then #(js/JSON.parse (.toString buf "utf8")))
                     (.finally #(.close f))))))))

(defn- lane-file
  "The file a lane's steps are in: the chat's own, or an agent's; nil for no such lane."
  [dir id lane]
  (cond (nil? dir) nil
        (= lane "main") (path/join dir (str id ".jsonl"))
        (re-matches #"[A-Za-z0-9]+" lane) (path/join dir id "subagents" (str "agent-" lane ".jsonl"))))

(defn- tool-detail
  "A tool step in full: its call (in line's content) and its result line r."
  [^js tool step content ^js r]
  (let [^js call (some #(when (and (= "tool_use" (.-type ^js %)) (= step (.-id ^js %))) %) content)
        input (js/JSON.stringify (or (some-> call .-input) #js {}) nil 2)
        ^js b (when r (some #(when (and (= "tool_result" (.-type ^js %)) (= step (.-tool_use_id ^js %))) %)
                            (some-> r .-message .-content)))
        output (when r (history/result-text (js->clj (some-> b .-content) :keywordize-keys true)))
        patch (when (and r (.-diff tool))
                (or (patch-of (.-toolUseResult r)) (input-patch (str (.-name call)) (.-input call))))]
    {:k "tool" :name (.-name tool) :input (text/fit input full)
     :output (when output (text/fit output full)) :error (boolean (some-> b .-is_error))
     :truncated (or (> (count input) full) (> (count output) full))
     :patch patch}))

(defn- text-detail
  "A text step in full: an assistant line's n-th block (the step id's end), or a user line's text."
  [^js line content step]
  (let [text (if (= "assistant" (.-type line))
               (str (some-> (aget content (js/Number (last (str/split step #":")))) .-text))
               (history/strip-reminders (text-of content)))]
    {:k "text" :text (text/fit text full) :truncated (> (count text) full)}))

(defn- step-detail
  "Promise of one step of the file in full (its lines read again from their places), or nil."
  [file ^js p step]
  (let [^js loc (some-> p .-locs (.get step))]
    (when (and loc (.-use loc))
      (.then
       (js/Promise.all #js [(read-loc file (.-use loc)) (if (.-result loc) (read-loc file (.-result loc)) nil)])
       (fn [[^js line ^js r]]
         (let [content (some-> line .-message .-content)]
           (if-let [^js tool (.get (.-tools p) step)]
             (tool-detail tool step content r)
             (text-detail line content step))))))))

(defn detail
  "Promise of one step in full: {k tool, name, input, output, error, truncated, patch} or
  {k text, text, truncated}; nil when it is not there."
  [cwd id lane step]
  (-> (session-dir cwd id)
      (.then
       (fn [dir]
         (when-let [file (lane-file dir id lane)]
           (-> (read-on file)
               (.then (fn [p] (step-detail file p step)))))))))
