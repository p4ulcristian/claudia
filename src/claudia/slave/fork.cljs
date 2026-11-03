(ns claudia.slave.fork
  "Forking a chat: a new Claude Code session holding the chat up to the end of one turn, with the
  chat's row (folder, picture) copied and a colour of its own. The fork works in the same folder:
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

(defn- parent-uuids
  "uuid -> parentUuid, from the session file."
  [cwd id]
  (-> (fsp/readFile (history/session-file cwd id) "utf8")
      (.then (fn [text]
               (reduce (fn [m line]
                         (try (let [^js e (js/JSON.parse line)]
                                (if (.-uuid e) (assoc m (.-uuid e) (.-parentUuid e)) m))
                              (catch :default _ m)))
                       {} (str/split-lines text))))))

(defn fork!
  "Promise of the new chat's id. at: the uuid of a user message whose turn is the last kept; nil =
  up to the last finished turn. A running turn is never kept: its entries are still being written."
  [cwd id at running? colour]
  (-> (js/Promise.all #js [(sdk/getSessionMessages id #js {:dir cwd}) (parent-uuids cwd id)])
      (.then
       (fn [[msgs parent-of]]
         (let [chain (vec (remove :parent_tool_use_id (js->clj msgs :keywordize-keys true)))
               starts (filterv #(starts-turn? chain %) (range (count chain)))
               _ (when (empty? chain) (throw (js/Error. "This chat has nothing to fork yet.")))
               cut (cond
                     at (let [i (first (keep-indexed #(when (= at (:uuid %2)) %1) chain))]
                          (when-not i (throw (js/Error. "That message isn't saved in the chat yet.")))
                          (let [c (first (filter #(> % i) starts))]
                            (when (and (nil? c) running?) (throw (js/Error. "That turn is still running: fork it once it's done.")))
                            c))
                     running? (let [c (peek starts)]
                                (when-not (and c (pos? c)) (throw (js/Error. "No finished turn to fork yet.")))
                                c))
               up-to (when cut
                       (or (not-empty (get parent-of (:uuid (nth chain cut))))
                           (:uuid (nth chain (dec cut)))))
               r (store/chat id)]
           (-> (sdk/forkSession id (clj->js (cond-> {:dir cwd :title (str (:title r) " (fork)")}
                                              up-to (assoc :upToMessageId up-to))))
               (.then (fn [^js res]
                        (let [fork (.-sessionId res)
                              now (js/Date.now)]
                          (store/add! {:id fork :title (str (:title r) " (fork)") :at now
                                       :folder (:folder r) :color colour})
                          (when-let [s (store/scene id)] (store/save-scene! fork s))
                          (store/update! fork {:preview (str (:preview r)) :ended now :seen now})
                          fork)))))))))
