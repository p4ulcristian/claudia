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

(defn- step-of
  "The tool call `step` (its tool_use id) in the session file: {:name :input :result :error :asked
  :before :at}, or nil."
  [cwd chat step]
  (-> (fsp/readFile (history/session-file cwd chat) "utf8")
      (.then
       (fn [text]
         (loop [[line & more] (str/split-lines text)
                acc {:asked nil :before nil}]
           (if (nil? line)
             (when (:name acc) acc)
             (let [^js e (try (js/JSON.parse line) (catch :default _ nil))
                   content (some-> e .-message .-content)
                   bs (if (array? content) (vec content) [])]
               (recur more
                      (cond
                        (or (nil? e) (.-isSidechain e)) acc
                        (and (= "user" (.-type e)) (string? content) (not (:name acc)))
                        (assoc acc :asked (history/strip-reminders content))
                        (= "user" (.-type e))
                        (reduce (fn [a ^js b]
                                  (cond
                                    (and (= "tool_result" (.-type b)) (= step (.-tool_use_id b)))
                                    (assoc a :result (history/result-text (js->clj (.-content b) :keywordize-keys true))
                                           :error (boolean (.-is_error b)) :finished true)
                                    (and (= "text" (.-type b)) (not (:name a)))
                                    (assoc a :asked (history/strip-reminders (.-text b)))
                                    :else a))
                                acc bs)
                        (and (= "assistant" (.-type e)) (not (:name acc)))
                        (reduce (fn [a ^js b]
                                  (cond
                                    (and (= "tool_use" (.-type b)) (= step (.-id b)))
                                    (assoc a :name (.-name b) :input (js/JSON.stringify (.-input b) nil 2)
                                           :title (history/tool-title (.-name b) (js->clj (.-input b) :keywordize-keys true))
                                           :at (js/Date.parse (.-timestamp e)))
                                    (and (= "text" (.-type b)) (not (:name a))) (assoc a :before (.-text b))
                                    :else a))
                                acc bs)
                        :else acc)))))))))

(defn- token-env [token]
  (let [env (.-env js/process)]
    (clj->js (assoc (into {} (for [k (js-keys env)] [k (aget env k)])) "CLAUDE_CODE_OAUTH_TOKEN" token))))

(defn explain
  "Promise of the plain-words text for one step of a chat. live: the step's Msg of the running turn,
  for a step the session file doesn't hold yet."
  [cwd chat step token live]
  (-> (step-of cwd chat step)
      (.catch (fn [_] nil))
      (.then
       (fn [s]
         (let [s (or s (when live
                         {:name (:tool live) :title (:title live) :input (:detail live) :at (:at live)
                          :result (get-in live [:result :text]) :error (boolean (get-in live [:result :error]))
                          :finished (some? (:result live))}))]
         (when-not s (throw (js/Error. "That step isn't saved in the chat yet.")))
         (let [running (not (:finished s))
               seconds (when (and running (:at s)) (js/Math.round (/ (- (js/Date.now) (:at s)) 1000)))
               prompt (str/join "\n\n"
                                (filter seq
                                        [(when (seq (:asked s)) (str "What they asked:\n" (text/cut (:asked s) 1500 "\n…(cut)")))
                                         (when (seq (:before s)) (str "What the agent said just before this step:\n" (text/cut (:before s) 1000 "\n…(cut)")))
                                         (str "The step: " (history/tool-label (:name s)) " " (:title s) "\nIts input:\n" (text/cut (:input s) 4000 "\n…(cut)"))
                                         (if running
                                           (str "It is still running" (when seconds (str ", for " seconds " seconds so far")) ".")
                                           (str "Its output" (when (:error s) " (it failed)") ":\n"
                                                (or (not-empty (text/cut (:result s) 4000 "\n…(cut)")) "(none)")))]))
               ;; A finished step's answer stays the same; a running one is asked again each time.
               k (when-not running (.digest (.update (crypto/createHash "sha256") prompt) "hex"))]
           (if-let [hit (and k (get @done k))]
             hit
             (let [^js q (sdk/query #js {:prompt prompt
                                         :options #js {:model "haiku" :systemPrompt system :tools #js []
                                                       :settingSources #js [] :maxTurns 1 :persistSession false
                                                       :env (token-env token)}})
                   text (atom "")
                   it (.call (aget q js/Symbol.asyncIterator) q)]
               (-> (js/Promise.
                    (fn [resolve reject]
                      (letfn [(step! []
                                (-> (.next it)
                                    (.then (fn [^js r]
                                             (let [^js m (.-value r)]
                                               (cond
                                                 (.-done r) (resolve nil)
                                                 (= "result" (.-type m)) (resolve nil)
                                                 :else
                                                 (do (when (= "assistant" (.-type m))
                                                       (doseq [^js b (or (some-> m .-message .-content) #js [])]
                                                         (when (= "text" (.-type b)) (swap! text str (.-text b)))))
                                                     (step!))))))
                                    (.catch reject)))]
                        (step!))))
                   (.finally #(try (.close q) (catch :default _)))
                   (.then (fn []
                            (let [t (or (not-empty (str/trim @text)) "No explanation came back.")]
                              (when k
                                (swap! done (fn [d] (assoc (if (> (count d) 500) {} d) k t))))
                              t))))))))))))
