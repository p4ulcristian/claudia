(ns claudia.slave.search
  "Full-text search over what was said in the chats: an SQLite FTS5 index (its own file,
  `search.db`, deletable any time) of the user's and Claude's text in the Claude Code session files
  (tool calls and results left out). Each file is read on from where the index left it, so a search
  reads only what was written since; the runner builds the index in the background when it starts."
  (:require ["node:sqlite" :refer [DatabaseSync]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.slave.history :as history]))

(defonce ^:private db (atom nil))
(def ^:private index-version
  "Raised when said reads more of the session files: an older index is emptied and built again
  (2: messages sent mid-turn, slash commands as typed)."
  2)

(defn open! [dir]
  (let [d (DatabaseSync. (path/join dir "search.db"))]
    (.exec d "PRAGMA journal_mode = WAL;
              CREATE VIRTUAL TABLE IF NOT EXISTS msgs USING fts5(text, session UNINDEXED, uuid UNINDEXED, role UNINDEXED, tokenize = \"unicode61 remove_diacritics 2\");
              CREATE TABLE IF NOT EXISTS files (session TEXT PRIMARY KEY, offset INTEGER NOT NULL);")
    (when (< (.-user_version (.get (.prepare d "PRAGMA user_version"))) index-version)
      (.exec d (str "DELETE FROM msgs; DELETE FROM files; PRAGMA user_version = " index-version ";")))
    (reset! db d)))

(defn- stmt ^js [sql] (.prepare ^js @db sql))

(defn- command-text
  "A slash command kept as tagged user text (src/chat.ts commandParts): \"/name args\" (\"\" for its
  output alone), or nil when the text is no command."
  [text]
  (let [nm (some-> (re-find #"<command-name>([\s\S]*?)</command-name>" text) second str/trim)
        args (some-> (re-find #"<command-args>([\s\S]*?)</command-args>" text) second str/trim)
        out (re-find #"<local-command-std(?:out|err)>([\s\S]*?)</local-command-std(?:out|err)>" text)]
    (when (or (seq nm) out (re-find #"<command-message>" text))
      (if (seq nm)
        (str (if (str/starts-with? nm "/") nm (str "/" nm)) (when (seq args) (str " " args)))
        ""))))

(defn- sent-mid-turn
  "A message sent mid-turn: its queued_command attachment's sent uuid, or nil."
  [^js e]
  (let [^js a (when (= "attachment" (.-type e)) (.-attachment e))]
    (when (and a (= "queued_command" (.-type a)) (not (.-isMeta a)) (not (.-forwardedIntent a))
               (or (string? (.-prompt a)) (array? (.-prompt a))))
      (if (and (string? (.-source_uuid a)) (seq (.-source_uuid a))) (.-source_uuid a) (.-uuid e)))))

(defn- said
  "The text a session line adds to the chat as the user or Claude sees it, or nil (src/search.ts
  said): task notices and command output are notes, not something said."
  [line]
  (when (or (str/includes? line "\"type\":\"user\"") (str/includes? line "\"type\":\"assistant\"")
            (str/includes? line "\"queued_command\""))
    (when-let [^js e (try (js/JSON.parse line) (catch :default _ nil))]
      (when (and (.-uuid e) (not (.-isMeta e)) (not (.-isSidechain e)))
        (let [mid (sent-mid-turn e)
              type (if mid "user" (.-type e))
              uuid (or mid (.-uuid e))
              content (if mid (.. e -attachment -prompt) (some-> e .-message .-content))]
          (when (#{"user" "assistant"} type)
            (let [blocks (cond (string? content) [#js {:type "text" :text content}]
                               (array? content) (vec content)
                               :else [])
                  text (->> blocks
                            (keep #(when (and % (= "text" (.-type ^js %)) (string? (.-text ^js %)))
                                     (history/strip-reminders (.-text ^js %))))
                            (filter seq)
                            (str/join "\n"))
                  user? (= "user" type)
                  text (if (and user? (not (history/task-notice text)))
                         (or (command-text text) text)
                         text)]
              (when (and (seq (str/trim text)) (not (and user? (history/task-notice text))))
                {:uuid uuid :role (if user? "user" "claude") :text text}))))))))

(defn update!
  "Brings one session's index up to date (all of it again when its file shrank)."
  [file session]
  (when @db
    (when-let [size (try (.-size (fs/statSync file)) (catch :default _ nil))]
      (let [from (or (some-> (.get (stmt "SELECT offset FROM files WHERE session = ?") session) .-offset) 0)
            from (if (< size from)
                   (do (.run (stmt "DELETE FROM msgs WHERE session = ?") session) 0)
                   from)]
        (when (> size from)
          (let [buf (js/Buffer.alloc (- size from))
                fd (fs/openSync file "r")]
            (try (fs/readSync fd buf 0 (.-length buf) from) (finally (fs/closeSync fd)))
            ;; Only whole lines: a line still being written waits for the next update.
            (let [end (inc (.lastIndexOf buf 10))]
              (when (pos? end)
                (let [add (stmt "INSERT INTO msgs (text, session, uuid, role) VALUES (?, ?, ?, ?)")]
                  (.exec ^js @db "BEGIN")
                  (try
                    (doseq [l (str/split (.toString buf "utf8" 0 end) #"\n")
                            :let [s (said l)]
                            :when s]
                      (.run add (:text s) session (:uuid s) (:role s)))
                    (.run (stmt "INSERT INTO files (session, offset) VALUES (?, ?) ON CONFLICT (session) DO UPDATE SET offset = excluded.offset")
                          session (+ from end))
                    (.exec ^js @db "COMMIT")
                    (catch :default e (.exec ^js @db "ROLLBACK") (throw e))))))))))))

(defn warm!
  "Every session given ([file id] pairs, newest first), one per tick so the runner stays responsive."
  [pairs]
  (when-let [[[file id] & more] (seq pairs)]
    (try (update! file id) (catch :default e (js/console.error "search index:" (.-message e))))
    (js/setTimeout #(warm! more) 0)))

(defn- words [text]
  (vec (or (.match (str/lower-case (str text)) (js/RegExp. "[\\p{L}\\p{N}_]+" "gu")) #js [])))

(defn search
  "chats: [{:id :title :file}] the person can see. Answers [{id hits [{uuid role snippet}] count title}]:
  chats whose title holds every word first, then by the best hit."
  [text chats]
  (doseq [{:keys [id file]} chats] (try (update! file id) (catch :default _)))
  (let [ws (words (subs (str text) 0 (min 200 (count (str text)))))
        by-id (into {} (map (juxt :id identity)) chats)]
    (if (empty? ws)
      []
      (let [q (str/join " " (map #(str "\"" % "\"*") ws))
            rows (.all (stmt "SELECT session, uuid, role, snippet(msgs, 0, char(1), char(2), '…', 16) AS snip, bm25(msgs) AS rank
                              FROM msgs WHERE msgs MATCH ? ORDER BY rank LIMIT 3000") q)
            found (reduce (fn [m ^js r]
                            (let [s (.-session r)]
                              (if-not (by-id s)
                                m
                                (update m s (fn [c]
                                              (let [c (or c {:best (.-rank r) :hits [] :count 0})]
                                                (cond-> (update c :count inc)
                                                  (< (count (:hits c)) 3)
                                                  (update :hits conj {:uuid (.-uuid r) :role (.-role r)
                                                                      :snippet (str/replace (.-snip r) #"\s+" " ")}))))))))
                          {} rows)
            titled? (fn [id] (let [t (str/lower-case (str (:title (by-id id))))] (every? #(str/includes? t %) ws)))
            found (reduce (fn [m {:keys [id]}] (if (and (not (m id)) (titled? id)) (assoc m id {:best 0 :hits [] :count 0}) m))
                          found chats)]
        (->> found
             (map (fn [[id c]] {:id id :hits (:hits c) :count (:count c) :title (titled? id) :best (:best c)}))
             (sort-by (juxt #(if (:title %) 0 1) :best))
             (take 60)
             (mapv #(dissoc % :best)))))))
