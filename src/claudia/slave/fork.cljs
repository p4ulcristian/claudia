(ns claudia.slave.fork
  "Forking a chat (the TypeScript Claudia's src/fork.ts): a new Claude Code session holding the chat
  up to the end of one turn, with Claudia's own rows for the turns it keeps (settings, the form row,
  pictures, file changes) copied over and a colour of its own. The fork works in the same folder:
  files on disk are not touched."
  (:require ["@anthropic-ai/claude-agent-sdk" :as sdk]
            ["node:fs/promises" :as fsp]
            [clojure.string :as str]
            [claudia.slave.history :as history]
            [claudia.slave.store :as store]))

(defn- blocks [m] (let [c (get-in m [:message :content])] (if (sequential? c) c [])))

(defn- starts-turn?
  "A user entry that starts a turn: something typed that follows a finished turn (a message sent
  while a turn ran follows a tool call or result, and belongs to that turn)."
  [chain i]
  (let [m (nth chain i)
        content (get-in m [:message :content])
        typed (or (string? content) (some #(#{"text" "image"} (:type %)) (blocks m)))
        prev (when (pos? i) (nth chain (dec i)))]
    (and (= "user" (:type m))
         typed
         (not (some #(= "tool_result" (:type %)) (blocks m)))
         (cond (nil? prev) true
               (= "assistant" (:type prev)) (not= "tool_use" (get-in prev [:message :stop_reason]))
               (= "user" (:type prev)) (not (some #(= "tool_result" (:type %)) (blocks prev)))
               :else true))))

(defn- entries
  "The session file's entries (JS objects)."
  [cwd id]
  (-> (fsp/readFile (history/session-file cwd id) "utf8")
      (.then (fn [text] (vec (keep #(try (js/JSON.parse %) (catch :default _ nil)) (remove str/blank? (str/split-lines text))))))))

(defn sent-mid-turn
  "A message sent while a turn ran isn't saved as a user entry: Claude Code saves a queued_command
  attachment holding the sent uuid as source_uuid (src/chat.ts sentMidTurn). The sent uuid, or nil."
  [^js e]
  (let [^js a (when (and e (= "attachment" (.-type e))) (.-attachment e))]
    (when (and a (= "queued_command" (.-type a)) (not (.-isMeta a)) (not (.-forwardedIntent a))
               (or (string? (.-prompt a)) (array? (.-prompt a))))
      (if (and (string? (.-source_uuid a)) (seq (.-source_uuid a))) (.-source_uuid a) (.-uuid e)))))

(defn fork!
  "Promise of the new chat's id. at: the uuid of a user message whose turn is the last kept; nil =
  up to the last finished turn. A running turn is never kept: its entries are still being written."
  [cwd id at running? colour]
  (-> (js/Promise.all #js [(sdk/getSessionMessages id #js {:dir cwd}) (entries cwd id)])
      (.then
       (fn [[msgs old]]
         (let [chain (vec (remove :parent_tool_use_id (js->clj msgs :keywordize-keys true)))
               _ (when (empty? chain) (throw (js/Error. "This chat has nothing to fork yet.")))
               starts (filterv #(starts-turn? chain %) (range (count chain)))
               cut (cond
                     at (let [i (first (keep-indexed #(when (= at (:uuid %2)) %1) chain))]
                          (when-not i (throw (js/Error. "That message isn't saved in the chat yet.")))
                          (let [c (first (filter #(> % i) starts))]
                            (when (and (nil? c) running?) (throw (js/Error. "That turn is still running: fork it once it's done.")))
                            c))
                     running? (let [c (peek starts)]
                                (when-not (and c (pos? c)) (throw (js/Error. "No finished turn to fork yet.")))
                                c))
               by-uuid (into {} (keep (fn [^js e] (when (.-uuid e) [(.-uuid e) e]))) old)
               ;; The kept turn's last entry is what the next turn's message hangs on (it can be an
               ;; entry the chat doesn't show); the shown message before it if that isn't found.
               up-to (when cut
                       (or (not-empty (some-> ^js (get by-uuid (:uuid (nth chain cut))) .-parentUuid))
                           (:uuid (nth chain (dec cut)))))]
           (-> (sdk/forkSession id (clj->js (cond-> {:dir cwd} up-to (assoc :upToMessageId up-to))))
               (.then (fn [^js res]
                        (let [fork (.-sessionId res)]
                          (-> (entries cwd fork)
                              (.then (fn [copied]
                                       ;; Each copied entry names the one it came from: the kept turns'
                                       ;; uuids, old -> new; a message sent mid-turn by its attachment's.
                                       (let [turns (reduce (fn [m ^js e]
                                                             (let [from (some-> e .-forkedFrom .-messageUuid)]
                                                               (if (and from (.-uuid e))
                                                                 (let [m (assoc m from (.-uuid e))
                                                                       was (sent-mid-turn (get by-uuid from))
                                                                       now (sent-mid-turn e)]
                                                                   (if (and was now) (assoc m was now) m))
                                                                 m)))
                                                           {} copied)
                                             r (store/chat id)
                                             t (js/Date.now)]
                                         (store/add! {:id fork :title (str (:title r) " (fork)") :at t
                                                      :folder (:folder r) :color colour})
                                         (store/update! fork {:ended t :seen t})
                                         (when-let [s (store/kv id "settings")] (store/kv! fork "settings" s))
                                         (store/save-settings! fork (dissoc (store/settings id) :forms-seen))
                                         (store/copy-turns! id fork turns)
                                         ;; The fork's own picture: the last one of the turns it keeps.
                                         (if-let [last (peek (store/pictures fork))]
                                           (store/save-scene! fork (store/picture fork (:turn last)))
                                           ;; A chat older than the picture history has only its last picture.
                                           (when (and (empty? (store/pictures id)) (store/scene id))
                                             (store/save-scene! fork (store/scene id))))
                                         fork)))))))))))))
