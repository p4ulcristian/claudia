(ns claudia.slave.move
  "Moving a chat to another server (README decision 80): the runner's half. The hub drives it, as
  the chat's own person (X-Claudia-Via own; the hub refuses these paths from a page), on the source
  and the target runner through their fronts:

  POST /api/chat-move/check   {chat, folder}       target: {folder} where it will work, or why not
  POST /api/chat-move/hold    {chat}               source: held (nothing goes in, Claude Code closed): {check}
  GET  /api/chat-move/export?chat=                 source: the copy, lines of JSON (claudia.slave.move.rules)
  POST /api/chat-move/import?chat=  (the copy)     target: kept aside, read back: {check}
  POST /api/chat-move/commit  {chat, folder, from, to}  target: the chat is here, with the move's note
  POST /api/chat-move/drop    {chat}               target: the copy kept aside goes
  POST /api/chat-move/release {chat, to?}          source: to: archived as moved there; none: as before"
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.node.fs :as nfs]
            [claudia.node.http :as io]
            [claudia.shared.chat-move :as cm]
            [claudia.shared.protocol :as p]
            [claudia.slave.chats :as chats]
            [claudia.slave.chats.answers :as answers]
            [claudia.slave.history :as history]
            [claudia.slave.move.rules :as rules]
            [claudia.slave.search :as search]
            [claudia.slave.store :as store]))

(def ^:private hold-ms "How long a source holds a chat (and a target its copy) for one move." (* 15 60000))
(def ^:private settle-ms "How long a closed Claude Code gets to finish writing its session file." 1500)

(defonce ^:private data-dir (atom nil))

(defn set-root!
  "Copies are kept aside in <data>/moves/<chat> until they are committed or dropped; any left from
  before a restart go now."
  [dir]
  (reset! data-dir dir)
  (fs/rmSync (path/join dir "moves") #js {:recursive true :force true}))

(defn- changed! [] ((:changed @chats/out)))
(defn- staging [id] (path/join @data-dir "moves" id))
(defn- projects-dir [] (history/projects-dir chats/cwd))

;; ---- the files of a session and their check ----

(defn- walk
  "The files under base/rel (paths relative to base), folders followed, links left out."
  [base rel]
  (vec (mapcat (fn [^js d]
                 (let [r (str rel "/" (.-name d))]
                   (cond (.isDirectory d) (walk base r)
                         (.isFile d) [r]
                         :else [])))
               (fs/readdirSync (path/join base rel) #js {:withFileTypes true}))))

(defn- session-files
  "The files of chat id's session under dir (its project folder): <id>.jsonl and all under <id>/."
  [dir id]
  (into (if (fs/existsSync (path/join dir (str id ".jsonl"))) [(str id ".jsonl")] [])
        (when (nfs/dir? (path/join dir id)) (walk dir id))))

(defn- file-sum [dir rel]
  (let [b (fs/readFileSync (path/join dir rel))]
    {:path rel :size (.-length b) :sha256 (.digest (.update (crypto/createHash "sha256") b) "hex")}))

(defn- check-of
  "The check (claudia.shared.chat-move/check-problem) of chat id's files under dir, with its counts
  of pictures and file changes."
  [dir id pictures changes]
  (merge (cm/messages-check (rules/heads (fs/readFileSync (path/join dir (str id ".jsonl")) "utf8")))
         {:files (vec (sort-by :path (map #(file-sum dir %) (session-files dir id))))
          :pictures pictures :changes changes}))

;; ---- the source ----

(defn- source-problem
  "Why chat id cannot leave this server now, or nil."
  [id]
  (cond (nil? (store/chat id)) "No such chat."
        (not (fs/existsSync (history/session-file chats/cwd id))) "This chat has no conversation to move yet."
        :else (or (chats/move-problem id)
                  (some #(when-not (cm/rel-ok? id %) (str "A file of this chat the move cannot carry: " %))
                        (session-files (projects-dir) id)))))

(defn- hold
  "Promise of the source's answer to hold: {check} once its Claude Code has closed, or why not."
  [{id :chat}]
  (if-let [why (source-problem id)]
    (js/Promise.resolve {:status 409 :error why})
    (do (chats/hold! id (+ (js/Date.now) hold-ms))
        (changed!)
        (js/Promise. (fn [done]
                       (js/setTimeout
                        #(done (try (let [{:keys [pictures changes]} (store/export-rows id)]
                                      {:check (check-of (projects-dir) id (count pictures) (count changes))})
                                    (catch :default e
                                      (chats/unhold! id)
                                      {:status 500 :error (str "The chat could not be read: " (.-message e))})))
                        settle-ms))))))

(defn- put!
  "Promise that one record is written to res (resolved once res takes more), rejected when res
  closed."
  [^js res record]
  (js/Promise.
   (fn [ok fail]
     (if (.write res (str (p/write-json record) "\n"))
       (ok nil)
       (do (.once res "drain" #(ok nil))
           (.once res "close" #(fail (js/Error. "The hub went away."))))))))

(defn- put-file!
  "Promise that file rel (under dir) is written to res in pieces (an empty file as one empty piece)."
  [res dir rel]
  (let [fd (fs/openSync (path/join dir rel) "r")
        buf (js/Buffer.alloc rules/chunk-bytes)
        step (fn step [first?]
               (let [n (fs/readSync fd buf 0 rules/chunk-bytes nil)]
                 (if (and (zero? n) (not first?))
                   (js/Promise.resolve nil)
                   (.then (put! res {:type "file" :path rel :data (.toString (.subarray buf 0 n) "base64")})
                          #(when (pos? n) (step false))))))]
    (.finally (step true) #(fs/closeSync fd))))

(defn- chat-record
  "The copy's first line: the chat's row, the kv values that go along (as JSON text) and the note of
  an earlier move Claude has not heard yet (pending)."
  [id {:keys [row kv]}]
  {:type "chat" :id id :row row :kv (rules/kv-of kv) :pending (store/kv id "serverMove")})

(defn- export!
  "Writes the copy of held chat id to res: the chat, its pictures, its file changes, its files, end."
  [id ^js res]
  (let [rows (store/export-rows id)
        dir (projects-dir)
        records (concat [(chat-record id rows)]
                        (map #(assoc % :type "picture") (:pictures rows))
                        (map #(assoc % :type "change") (:changes rows)))]
    (.writeHead res 200 #js {"content-type" "application/x-ndjson"})
    (-> (reduce (fn [chain r] (.then chain #(put! res r))) (js/Promise.resolve nil) records)
        (.then #(reduce (fn [chain rel] (.then chain (fn [] (put-file! res dir rel)))) (js/Promise.resolve nil) (session-files dir id)))
        (.then #(put! res {:type "end"}))
        (.then #(.end res) (fn [e] (js/console.error (str "move " (subs id 0 8) ": " (.-message e))) (.destroy res))))))

(defn- release
  "The source's end of a move: to (a server name) = the chat now lives there, archived here and
  marked as moved (an import of it later may replace this copy); none = the move was given up."
  [{id :chat to :to}]
  (when (and (string? to) (store/chat id) (chats/moving? id))
    (store/update! id {:archived true})
    (store/kv! id "movedTo" {:server to :at (js/Date.now)}))
  (chats/unhold! id)
  (when (store/chat id) (chats/session-follows-row! id))
  (changed!)
  {:ok true})

;; ---- the target ----

(defn- target-problem
  "Why chat id cannot come to this server, or nil: a chat with its id is here, unless it is the copy
  an earlier move away left behind (archived, marked as moved, not written to since)."
  [id]
  (let [r (store/chat id)]
    (when (and r (not (and (:archived r) (store/kv id "movedTo") (nil? (chats/move-problem id)))))
      "This server has this chat already.")))

(defn- folder-here
  "{:folder} the move's folder p resolved on this server (nil: none, the working folder), or why not."
  [p]
  (let [folder (rules/resolve-folder chats/cwd (os/homedir) (str p))]
    (if (and folder (not (nfs/dir? folder)))
      {:status 404 :error (str "No folder at " folder " on this server.")}
      {:folder folder})))

(defn- check-target
  "The target's answer before anything is copied: where the chat will work, or why it cannot come."
  [{id :chat folder :folder}]
  (if-let [why (target-problem id)]
    {:status 409 :error why}
    (folder-here folder)))

(defn- drop-copy!
  "The copy kept aside for chat id goes."
  [id]
  (fs/rmSync (staging id) #js {:recursive true :force true}))

(defonce ^:private expiries (atom {}))

(defn- expire-copy!
  "The copy kept aside for chat id goes after hold-ms unless committed or dropped first (a copy kept
  again starts the wait again)."
  [id]
  (some-> (get @expiries id) js/clearTimeout)
  (swap! expiries assoc id (js/setTimeout #(do (swap! expiries dissoc id) (drop-copy! id)) hold-ms)))

(defn- keep-record!
  "One checked record of chat id's copy into its staging folder: a file's piece appended to that
  file, anything else as a line of rows.ndjson. seen: {type count} so far, returned counted."
  [id seen line r]
  (let [dir (staging id)
        kind (:type r)]
    (if (= kind "file")
      (let [f (path/join dir "files" (:path r))]
        (fs/mkdirSync (path/dirname f) #js {:recursive true})
        (fs/appendFileSync f (js/Buffer.from (:data r) "base64")))
      (fs/appendFileSync (path/join dir "rows.ndjson") (str line "\n")))
    (update seen kind (fnil inc 0))))

(defn- take-lines
  "The copy's next lines checked and kept: seen counted on, or {:error} for the first bad one."
  [id seen lines]
  (reduce (fn [acc line]
            (let [r (try (p/read-json line) (catch :default _ nil))
                  why (if (map? r) (rules/record-problem id r) "a line that is no JSON object")]
              (if why (reduced {:error why}) (keep-record! id acc line r))))
          seen lines))

(defn- staged-records
  "The records kept aside in chat id's rows.ndjson."
  [id]
  (mapv p/read-json (remove str/blank? (str/split-lines (fs/readFileSync (path/join (staging id) "rows.ndjson") "utf8")))))

(defn- read-back
  "The check of chat id's copy kept aside, read back from the disk."
  [id]
  (let [n (rules/counts (staged-records id))]
    (check-of (path/join (staging id) "files") id (get n "picture" 0) (get n "change" 0))))

(defn- finish-import
  "The copy came whole (seen: its records counted) or not: the answer, its read-back check."
  [id seen]
  (if-let [why (or (:error seen) (rules/copy-problem seen))]
    (do (drop-copy! id) {:status 400 :error (str "The copy is broken: " why)})
    (try {:check (read-back id)}
         (catch :default e (drop-copy! id) {:status 500 :error (str "The copy could not be read back: " (.-message e))}))))

(defn- import!
  "Promise of the target's answer to the copy streaming in (req): kept aside in <data>/moves/<id>,
  line by line (a line over rules/max-line breaks it), then read back."
  [id ^js req]
  (drop-copy! id)
  (fs/mkdirSync (path/join (staging id) "files") #js {:recursive true})
  (expire-copy! id)
  (.setEncoding req "utf8")
  (js/Promise.
   (fn [done]
     (let [st (atom {:carry "" :seen {}})]
       (.on req "data" (fn [piece]
                         (let [[lines carry] (rules/split-lines (:carry @st) piece)
                               seen (if (:error (:seen @st)) (:seen @st) (take-lines id (:seen @st) lines))]
                           (reset! st {:carry carry :seen (if (> (count carry) rules/max-line) {:error "a line too long"} seen)}))))
       (.on req "end" #(done (finish-import id (:seen @st))))
       (.on req "error" #(done (finish-import id {:error (.-message %)})))))))

(defn- set-aside!
  "The files a copy left behind by an earlier move away has here go into staging/replaced (deleted
  with the staging folder once the new copy is in)."
  [id]
  (let [dir (projects-dir)
        aside (path/join (staging id) "replaced")]
    (doseq [rel (session-files dir id)
            :let [to (path/join aside rel)]]
      (fs/mkdirSync (path/dirname to) #js {:recursive true})
      (fs/renameSync (path/join dir rel) to))))

(defn- place!
  "Moves file rel of chat id's copy from staging into the project folder of this runner's working
  folder (its own slug); across file systems by a copy."
  [id rel]
  (let [from (path/join (staging id) "files" rel)
        to (rules/target-file (projects-dir) id rel)]
    (fs/mkdirSync (path/dirname to) #js {:recursive true})
    (try (fs/renameSync from to)
         (catch :default e
           (if (= "EXDEV" (.-code e)) (do (fs/copyFileSync from to) (fs/unlinkSync from)) (throw e))))))

(defn- restore-aside!
  "A failed take-in: whatever was placed goes, the files set aside come back."
  [id]
  (let [dir (projects-dir)
        aside (path/join (staging id) "replaced")]
    (doseq [rel (session-files dir id)] (fs/rmSync (path/join dir rel) #js {:force true}))
    (when (nfs/dir? aside)
      (doseq [rel (session-files aside id)]
        (fs/mkdirSync (path/dirname (path/join dir rel)) #js {:recursive true})
        (fs/renameSync (path/join aside rel) (path/join dir rel))))))

(defn- take-in!
  "The copy's files into place and its rows (rows) in; on a failure the files are as before."
  [id rows]
  (set-aside! id)
  (try (doseq [rel (session-files (path/join (staging id) "files") id)] (place! id rel))
       (store/import-chat! rows)
       (catch :default e (restore-aside! id) (throw e))))

(defn- note-of
  "The kv value of the move's note: its words (claudia.slave.chats.answers/server-moved-note), the
  servers and when."
  [pending from to folder]
  (let [from-now (rules/note-from pending from)]
    {:text (answers/server-moved-note from-now to folder chats/cwd) :from from-now :to to :at (js/Date.now)}))

(defn- imported-rows
  "The rows of chat id as this runner keeps them, from the copy's records: its row in folder, the kv
  values that go along (JSON text) with the move's note, its pictures and file changes."
  [id records {:keys [folder from to]}]
  (let [chat (some #(when (= "chat" (:type %)) %) records)]
    {:row (assoc (rules/import-row (:row chat) (set store/chat-columns) folder) :id id)
     :kv (assoc (update-keys (:kv chat) name) "serverMove" (p/write-json (note-of (:pending chat) from to folder)))
     :pictures (filterv #(= "picture" (:type %)) records)
     :changes (filterv #(= "change" (:type %)) records)}))

(defn- commit
  "The target takes the chat in: its files into place, its rows in one transaction, the note for
  Claude's next message; then it is in the chat list."
  [{id :chat folder :folder from :from to :to}]
  (let [here (folder-here folder)]
    (cond (not (fs/existsSync (path/join (staging id) "rows.ndjson"))) {:status 409 :error "No copy of this chat is kept here."}
          (target-problem id) {:status 409 :error (target-problem id)}
          (:error here) here
          (not (and (string? from) (string? to))) {:status 400 :error "from and to: server names"}
          :else (do (take-in! id (imported-rows id (staged-records id) (assoc here :from from :to to)))
                    (drop-copy! id)
                    (search/update! (history/session-file chats/cwd id) id)
                    (changed!)
                    {:ok true :folder (:folder here)}))))

;; ---- the calls ----

(def ^:private routes
  {["POST" "/api/chat-move/check"] :check
   ["POST" "/api/chat-move/hold"] :hold
   ["GET" "/api/chat-move/export"] :export
   ["POST" "/api/chat-move/import"] :import
   ["POST" "/api/chat-move/commit"] :commit
   ["POST" "/api/chat-move/drop"] :drop
   ["POST" "/api/chat-move/release"] :release})

(defn route
  "Which move call a request is (method, url without its query), or nil."
  [method url]
  (get routes [method url]))

(defn- with-body
  "Promise of f's answer (data or a promise) to the call's JSON body, refused without a chat id."
  [^js req f]
  (.then (io/read-json req 65536)
         #(if (cm/chat-id? (:chat %)) (f %) {:status 400 :error "chat: a chat id"})))

(defn- answer
  "Promise of the answer to move call op."
  [op ^js req id ^js res]
  (case op
    :check (with-body req check-target)
    :hold (with-body req hold)
    :commit (with-body req commit)
    :drop (with-body req #(do (drop-copy! (:chat %)) {:ok true}))
    :release (with-body req release)
    :import (cond (not (cm/chat-id? id)) (js/Promise.resolve {:status 400 :error "chat: a chat id"})
                  (target-problem id) (js/Promise.resolve {:status 409 :error (target-problem id)})
                  :else (import! id req))
    :export (if (and (cm/chat-id? id) (chats/moving? id))
              (do (export! id res) (js/Promise.resolve nil))
              (js/Promise.resolve {:status 409 :error "This chat is not held for a move."}))))

(defn- reply! [^js res answered]
  (-> (js/Promise.resolve answered)
      (.then (fn [v] (when (and v (not (.-headersSent res)))
                       (io/send-json res (or (:status v) 200) (dissoc v :status)))))
      (.catch (fn [e] (when-not (.-headersSent res) (io/send-json res 500 {:error (.-message e)}))))))

(defn handle
  "Answers a move call (true), else false. who: the caller ({:via}); only the chat's own person."
  [^js req ^js res url who]
  (if-let [op (route (.-method req) url)]
    (do (if (= "own" (:via who))
          (let [id (.get (js/URLSearchParams. (or (second (str/split (.-url req) #"\?" 2)) "")) "chat")]
            (reply! res (try (answer op req id res) (catch :default e (js/Promise.reject e)))))
          (io/send-json res 403 {:error "Only the chat's own person moves it."}))
        true)
    false))
