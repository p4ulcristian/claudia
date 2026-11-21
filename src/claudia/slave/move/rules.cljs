(ns claudia.slave.move.rules
  "Moving a chat to another server (README decision 82), the runner's pure parts: what a chat's copy
  carries (its row, kv values, pictures and session files, as lines of JSON), where a
  copied file goes on the target, and the target's row for it."
  (:require ["node:path" :as path]
            [clojure.string :as str]
            [claudia.shared.chat-move :as cm]
            [claudia.slave.chats.answers :as answers]))

(def kept-kv
  "The chat's kv values a copy takes along: its settings (model, effort, plan, prompt), context use,
  last turn's name and its agents' list. Left behind: the session's versions (session), the forms
  its Claude heard of (formsSeen: the target's forms may differ), a folder move's note (moved: the
  server move's note says where it works now)."
  #{"settings" "context" "turn" "tasks"})

(def max-line
  "The longest line a copy may hold (a file goes in pieces of chunk-bytes, base64)."
  (* 8 1024 1024))

(def chunk-bytes "A file's bytes per line of a copy." (* 768 1024))

(defn import-row
  "The target's chats row for a copied row: only the columns the target has (allowed, keywords), in
  folder (nil: none), out of the archive, root off (the switch belongs to the source's front) and
  without its chat key (a new one is made when its session first starts there)."
  [row allowed folder]
  (-> (select-keys row allowed)
      (assoc :folder folder :archived 0 :root 0 :chat_key nil)))

(defn target-file
  "Where the copy's file rel goes in the target's project folder (projects-dir: Claude Code's folder
  for the target runner's working folder, its own slug), or nil when rel is no file of chat id."
  [projects-dir id rel]
  (when (cm/rel-ok? id rel) (path/join projects-dir rel)))

(defn split-lines
  "The text not yet split (carry) and a new piece: [the whole lines, the rest]."
  [carry piece]
  (let [s (str carry piece)
        i (str/last-index-of s "\n")]
    (if (nil? i) [[] s] [(str/split (subs s 0 i) #"\n" -1) (subs s (inc i))])))

(defn record-problem
  "What is wrong with one line of a copy of chat id (data), or nil. Records: chat {id row kv},
  picture {turn scene at}, file {path data (base64)}, end {}."
  [id r]
  (case (:type r)
    "chat" (when-not (and (= id (:id r)) (map? (:row r)) (map? (:kv r))) "the chat record is not this chat's")
    "picture" (when-not (and (string? (:turn r)) (string? (:scene r)) (number? (:at r))) "a picture without its turn, scene or time")
    "file" (when-not (and (cm/rel-ok? id (:path r)) (string? (:data r))) (str "not a file of this chat: " (:path r)))
    "end" nil
    (str "unknown record " (:type r))))

(defn copy-problem
  "What is wrong with a whole copy once its last line came: the records seen ({type count}), or nil."
  [seen]
  (cond (not= 1 (get seen "chat")) "the copy has no chat record"
        (not= 1 (get seen "end")) "the copy was cut off before its end"
        :else nil))

(defn kv-of
  "The kv values a copy takes along, from [[key value] …]."
  [pairs]
  (into {} (filter #(kept-kv (first %))) pairs))

(defn note-from
  "The server a moved chat's Claude last worked on: the source's own note's source when Claude has
  not heard of an earlier move yet (pending: that note's data), else from."
  [pending from]
  (or (:from pending) from))

(defn resolve-folder
  "The folder a move names on this server (p: absolute, ~/…, or relative to the working folder
  cwd; home: this user's home), as an absolute path; nil for a blank p (no project folder)."
  [cwd home p]
  (when-not (str/blank? p)
    (path/resolve cwd (answers/home-path (str/trim p) home))))

(defn heads
  "The type and uuid of each entry of a session file's text ([{:type :uuid}], in order); a line that
  is no JSON object counts as nothing."
  [text]
  (vec (for [line (str/split-lines (str text))
             :let [^js e (try (js/JSON.parse line) (catch :default _ nil))]
             :when (and e (= "object" (goog/typeOf e)))]
         {:type (.-type e) :uuid (.-uuid e)})))

(defn counts
  "How many records of each type the lines of a copy's rows hold ({type count})."
  [records]
  (frequencies (map :type records)))
