(ns claudia.slave.claudia
  "Claudia's own chat (README decision 48): one chat per profile, opened from her face, whose Claude
  manages the person's chats on every server they have a profile on: lists, starts, writes to,
  archives and stops them, only when asked. Her tools go to the hub (POST /hub/server/claudia, this
  server's hub key), which carries them out on each server as the person (claudia.hub.claudia).
  She runs nothing in the background (CLAUDE_CODE_DISABLE_BACKGROUND_TASKS in her session).

  Only a runner with CLAUDIA_HUB_URL, CLAUDIA_SERVER_NAME, CLAUDIA_HUB_KEY_FILE (this server's hub
  key, as the front keeps it) and CLAUDIA_OWNER_EMAIL has her; elsewhere the face opens nothing."
  (:require [claudia.slave.hub-call :as hub-call]
            [claudia.slave.store :as store]))

(defn config
  "Her settings from env (a JS object): the hub link (claudia.slave.hub-call/settings) and :email,
  or nil when one is missing."
  [^js env]
  (let [link (hub-call/settings env)
        email (.-CLAUDIA_OWNER_EMAIL env)]
    (when (and link (seq email)) (assoc link :email email))))

(defn on? [] (some? (config (.-env js/process))))

(defn chat-id "Her chat's id here, or nil before she was first opened." [] (store/kv "" "claudia-chat"))

(defn her? "Whether chat id is her chat." [id] (and (some? id) (= id (chat-id))))

(defn ensure-chat!
  "Her chat's id, made the first time (a row titled Claudia, no message yet)."
  []
  (or (when-let [id (chat-id)] (when (store/chat id) id))
      (let [id (str (random-uuid))]
        (store/add! {:id id :title "Claudia" :folder nil :color "violet" :at (js/Date.now)})
        (store/kv! "" "claudia-chat" id)
        id)))

(def prompt
  "Her part of the system prompt."
  "# You are Claudia herself

This is Claudia's own chat: the person opened it from Claudia's face. Here you manage their chats
(Claudia calls them agents) on every server they have a profile on, with the claudia tools:
list_chats, start_chat, send_to_chat, archive_chat, stop_chat.

- Act only when the person asks, and only on what they asked for. Never start, archive or stop
  anything on your own, and never keep working after you answered.
- Archiving can be undone (archive_chat with archived false), so archive without asking first.
  Stopping a running turn cannot be undone: stop_chat asks the person itself; do not ask again.
- Before acting on a chat, find it with list_chats; name chats by their title, never by id.
- You run nothing in the background: no background shells, no background agents, no wake-ups.
- Draw what you did (the chats touched, before and after) in the picture.")

(def ^:private chat-ref
  {:server {:type "string" :description "The server the chat is on (list_chats says)"}
   :chat {:type "string" :description "The chat's id (list_chats says)"}})

(defn- tool-spec [description props required]
  {:description description
   :inputSchema {:type "object" :properties props :required required :additionalProperties false}})

(def mcp
  "Her tools (the MCP server claudia)."
  {:list_chats (tool-spec "Every chat of the person on every server: server, id, title, project, archived, running." {} [])
   :start_chat (tool-spec "Starts a new chat on a server with its first message (it starts working on it at once)."
                     {:server {:type "string" :description "The server to start it on"}
                      :text {:type "string" :description "The first message"}
                      :folder {:type "string" :description "The folder it works in (optional)"}}
                     ["server" "text"])
   :send_to_chat (tool-spec "Sends a message into a chat (queued if it is working)."
                       (assoc chat-ref :text {:type "string" :description "The message"}) ["server" "chat" "text"])
   :archive_chat (tool-spec "Archives a chat (archived false brings it back). Can be undone."
                       (assoc chat-ref :archived {:type "boolean" :description "false: bring it back (default true)"})
                       ["server" "chat"])
   :stop_chat (tool-spec "Stops a chat's running turn. Cannot be undone: the person is asked first." chat-ref ["server" "chat"])})

(def allowed (mapv #(str "mcp__claudia__" (name %)) (keys mcp)))

(def disallowed
  "Tools that would keep her working after her answer: wake-ups, schedules, watchers, workflows."
  ["Monitor" "ScheduleWakeup" "CronCreate" "CronDelete" "RemoteTrigger" "Workflow"])

(defn hub-op
  "The hub operation a tool call is: [op args]."
  [tool args]
  (case tool
    "list_chats" ["chats" {}]
    "start_chat" ["new" (select-keys args [:server :text :folder])]
    "send_to_chat" ["send" (select-keys args [:server :chat :text])]
    "archive_chat" ["archive" (assoc (select-keys args [:server :chat]) :archived (not (false? (:archived args))))]
    "stop_chat" ["stop" (select-keys args [:server :chat])]))

(defn call-hub!
  "Promise of the hub's answer (text) to her tool call."
  [tool args]
  (let [{:keys [email]} (config (.-env js/process))
        [op hub-args] (hub-op tool args)]
    (-> (hub-call/post! "/hub/server/claudia" {:as email :op op :args hub-args})
        (.then #(select-keys % [:ok :text])))))
