(ns claudia.slave.activity
  "The runner's activity lines (README decision 59): one per tool call and tool result it sees in a
  session's stream, one per message a person sends. The lines go to the runner's journal
  (claudia-runner@<profile>), which no tool process can write to: tools run under the host's unit."
  (:require ["node:os" :as os]
            [clojure.string :as str]
            [claudia.node.env :as env]
            [claudia.shared.activity :as activity]
            [claudia.slave.history :as history]))

(def ^:private input-keys
  "Which field of a tool's input says what it did, by tool name; others: the whole input as JSON."
  {"Bash" :command "Read" :file_path "Write" :file_path "Edit" :file_path "MultiEdit" :file_path
   "NotebookEdit" :notebook_path "WebFetch" :url "WebSearch" :query "Grep" :pattern "Glob" :pattern
   "Agent" :description "Task" :description "Skill" :skill})

(defn input-of
  "A tool call's input, short: the Bash command, the file path, the URL, the pattern (a known tool's
  one field), else the input as JSON; cut to activity/input-max characters."
  [tool input]
  (let [k (get input-keys tool)
        v (when (and k (map? input)) (get input k))]
    (activity/cut (if (some? v) v (js/JSON.stringify (clj->js input))))))

(defn tool-entries
  "The tool calls of an assistant message m (the SDK's) in chat as activity entries; self:
  {:profile :agentUser}. A sub-agent's call names the agent's call (:agent)."
  [chat self m]
  (for [b (get-in m [:message :content])
        :when (and (map? b) (= "tool_use" (:type b)))]
    (merge {:kind "tool" :chat chat} self
           {:tool (:name b) :id (:id b) :agent (:parent_tool_use_id m) :input (input-of (:name b) (:input b))})))

(defn exit-of
  "A Bash result's exit code: N from Claude Code's \"Exit code N\" on a failed one, 0 on one that
  succeeded, nil for another tool or when the text says none."
  [tool ok text]
  (when (= "Bash" tool)
    (if ok 0 (some-> (re-find #"(?m)^Exit code (\d+)" (str text)) second js/parseInt))))

(defn result-entries
  "The tool results of a user message m (the SDK's) in chat as activity entries; tools: the tool
  name by call id (from tool-entries)."
  [chat tools m]
  (for [b (get-in m [:message :content])
        :when (and (map? b) (= "tool_result" (:type b)))
        :let [tool (get tools (:tool_use_id b))
              ok (not (:is_error b))]]
    {:kind "result" :chat chat :tool tool :id (:tool_use_id b) :ok ok
     :exit (exit-of tool ok (history/result-text (:content b)))}))

(defn message-entry
  "A message a person sent into chat: profile the chat's, who {:by (their email, as the front
  said) :via}."
  [chat profile {:keys [by via]}]
  {:kind "message" :chat chat :profile profile :by by :via via})

;; ---- the edges ----

(def ^:private self
  {:profile (env/env "RUNNER_PROFILE" "") :agentUser (.-username (os/userInfo))})

;; Tool call id -> tool name, until its result comes (a result names only the call).
(defonce ^:private open-calls (atom {}))

(defn- log! [entry] (js/console.log (activity/line entry)))

(defn note-sdk!
  "Logs the tool calls and results of the SDK's message m in chat."
  [chat m]
  (case (:type m)
    "assistant" (let [es (tool-entries chat self m)]
                  (swap! open-calls into (map (juxt :id :tool)) es)
                  (run! log! es))
    "user" (let [es (result-entries chat @open-calls m)]
             (swap! open-calls #(apply dissoc % (map :id es)))
             (run! log! es))
    nil))

(defn note-message!
  "Logs a message a person (who: {:by :via}, from the front's headers) sent into chat."
  [chat profile who]
  (log! (message-entry chat profile who)))

(defn sender
  "The sender the front named on a request's headers (X-Claudia-By, X-Claudia-Via), {:by :via}."
  [^js headers]
  {:by (some-> (aget headers "x-claudia-by") str/trim not-empty)
   :via (some-> (aget headers "x-claudia-via") str/trim not-empty)})
