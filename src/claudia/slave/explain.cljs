(ns claudia.slave.explain
  "\"Explain\" on a step: Haiku, on the side, tells in plain words what one tool call does and, while
  it runs, what it waits on. It sees only that step and the request around it (read from the chat's
  session file); the chat, its context and Claude's turn are not touched. Answers are kept per step."
  (:require ["@anthropic-ai/claude-agent-sdk" :as sdk]
            [claudia.shared.text :as text]
            ["node:crypto" :as crypto]
            ["node:fs/promises" :as fsp]
            [clojure.string :as str]
            [claudia.slave.history :as history]))

(def ^:private system
  "You explain one step an AI coding agent (Claude Code) took, to the person it works for, who does not read code.
Answer in 2 or 3 short, plain English sentences: what this step does, and why it matters for what they asked.
If it is still running: say what it is waiting on and whether taking this long is normal for this kind of step.
If it failed: say what went wrong in plain words.
No code, no lists, no headings; name a file or command only when it helps. Every claim must follow from the step as given; don't guess beyond it.")

(defonce ^:private done (atom {}))

(defn- on-user-block
  "One block of a user entry: the step's result, or the text asked before the step."
  [step acc ^js b]
  (cond
    (and (= "tool_result" (.-type b)) (= step (.-tool_use_id b)))
    (assoc acc :result (history/result-text (js->clj (.-content b) :keywordize-keys true))
           :error (boolean (.-is_error b)) :finished true)
    (and (= "text" (.-type b)) (not (:name acc)))
    (assoc acc :asked (history/strip-reminders (.-text b)))
    :else acc))

(defn- on-assistant-block
  "One block of an assistant entry (^js e): the step's call, or what the agent said before it."
  [step ^js e acc ^js b]
  (cond
    (and (= "tool_use" (.-type b)) (= step (.-id b)))
    (assoc acc :name (.-name b) :input (js/JSON.stringify (.-input b) nil 2)
           :title (history/tool-title (.-name b) (js->clj (.-input b) :keywordize-keys true))
           :at (js/Date.parse (.-timestamp e)))
    (and (= "text" (.-type b)) (not (:name acc))) (assoc acc :before (.-text b))
    :else acc))

(defn- on-entry
  "One session file line (parsed, or nil) folded into what is known of the step."
  [step acc ^js e]
  (let [content (some-> e .-message .-content)
        bs (if (array? content) (vec content) [])]
    (cond
      (or (nil? e) (.-isSidechain e)) acc
      (and (= "user" (.-type e)) (string? content) (not (:name acc)))
      (assoc acc :asked (history/strip-reminders content))
      (= "user" (.-type e)) (reduce (partial on-user-block step) acc bs)
      (and (= "assistant" (.-type e)) (not (:name acc))) (reduce (partial on-assistant-block step e) acc bs)
      :else acc)))

(defn step-in
  "The tool call step (its tool_use id) in a session file's text: {:name :input :title :result
  :error :finished :asked :before :at}, or nil."
  [text step]
  (let [acc (reduce (fn [acc line] (on-entry step acc (try (js/JSON.parse line) (catch :default _ nil))))
                    {:asked nil :before nil}
                    (str/split-lines text))]
    (when (:name acc) acc)))

(defn- step-of
  "The tool call step in the chat's session file, or nil."
  [cwd chat step]
  (.then (fsp/readFile (history/session-file cwd chat) "utf8") #(step-in % step)))

(defn- token-env [token]
  (let [env (.-env js/process)]
    (clj->js (assoc (into {} (for [k (js-keys env)] [k (aget env k)])) "CLAUDE_CODE_OAUTH_TOKEN" token))))

(defn live-step
  "A step of the running turn (its Msg) in the shape step-in gives, or nil."
  [live]
  (when live
    {:name (:tool live) :title (:title live) :input (:detail live) :at (:at live)
     :result (get-in live [:result :text]) :error (boolean (get-in live [:result :error]))
     :finished (some? (:result live))}))

(defn prompt-of
  "What Haiku is asked about step s at time now (ms)."
  [s now]
  (let [running (not (:finished s))
        seconds (when (and running (:at s)) (js/Math.round (/ (- now (:at s)) 1000)))]
    (str/join "\n\n"
              (filter seq
                      [(when (seq (:asked s)) (str "What they asked:\n" (text/cut (:asked s) 1500 "\n…(cut)")))
                       (when (seq (:before s)) (str "What the agent said just before this step:\n" (text/cut (:before s) 1000 "\n…(cut)")))
                       (str "The step: " (history/tool-label (:name s)) " " (:title s) "\nIts input:\n" (text/cut (:input s) 4000 "\n…(cut)"))
                       (if running
                         (str "It is still running" (when seconds (str ", for " seconds " seconds so far")) ".")
                         (str "Its output" (when (:error s) " (it failed)") ":\n"
                              (or (not-empty (text/cut (:result s) 4000 "\n…(cut)")) "(none)")))]))))

(defn message-text
  "The text blocks of an SDK message, joined; \"\" for any message but an assistant's."
  [^js m]
  (if (= "assistant" (.-type m))
    (apply str (for [^js b (or (some-> m .-message .-content) #js [])
                     :when (= "text" (.-type b))]
                 (.-text b)))
    ""))

(defn- read-all
  "Promise of the text of every message the iterator it gives up to the result."
  [^js it]
  (.then (.next it)
         (fn [^js r]
           (let [^js m (.-value r)]
             (if (or (.-done r) (= "result" (.-type m)))
               ""
               (let [t (message-text m)] (.then (read-all it) #(str t %))))))))

(defn- ask-haiku
  "Promise of Haiku's answer to prompt (\"No explanation came back.\" when it is empty)."
  [prompt token]
  (let [^js q (sdk/query #js {:prompt prompt
                              :options #js {:model "haiku" :systemPrompt system :tools #js []
                                            :settingSources #js [] :maxTurns 1 :persistSession false
                                            :env (token-env token)}})]
    (-> (read-all (.call (aget q js/Symbol.asyncIterator) q))
        (.finally #(try (.close q) (catch :default _)))
        (.then #(or (not-empty (str/trim %)) "No explanation came back.")))))

(defn remember
  "The answers kept with answer t under k (started afresh past 500)."
  [kept k t]
  (assoc (if (> (count kept) 500) {} kept) k t))

(defn explain
  "Promise of the plain-words text for one step of a chat. live: the step's Msg of the running turn,
  for a step the session file doesn't hold yet."
  [cwd chat step token live]
  (-> (step-of cwd chat step)
      (.catch (fn [_] nil))
      (.then
       (fn [found]
         (let [s (or found (live-step live))]
           (when-not s (throw (js/Error. "That step isn't saved in the chat yet.")))
           (let [prompt (prompt-of s (js/Date.now))
                 ;; A finished step's answer stays the same; a running one is asked again each time.
                 k (when (:finished s) (.digest (.update (crypto/createHash "sha256") prompt) "hex"))]
             (if-let [hit (and k (get @done k))]
               hit
               (.then (ask-haiku prompt token)
                      (fn [t] (when k (swap! done remember k t)) t)))))))))
