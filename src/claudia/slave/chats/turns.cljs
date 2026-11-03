(ns claudia.slave.chats.turns
  "A running turn as data: the SDK's messages as Msgs and notes, how a turn ended, a sent message
  and what Claude Code did with it, the chat's context use."
  (:require [clojure.string :as str]
            [claudia.shared.text :as text]
            [claudia.slave.chats.titles :as titles]
            [claudia.slave.history :as history]))

(defn turn-outcome
  "How a result message m ended the turn: {:error (nil when it succeeded) :row (the chat's columns:
  the result's uuid as one id per turn, else fallback-id; when, how, and the reply's start)}."
  [m stopped now fallback-id]
  (let [ok (and (= "success" (:subtype m)) (not (:is_error m)))
        error (when-not ok
                (if (= "success" (:subtype m))
                  (str (:result m))
                  (or (some->> (:errors m) seq (str/join "\n")) (:subtype m))))
        reply (if ok (titles/plain-start (:result m) 200) "")]
    {:error error
     :row (cond-> {:ended now :last_turn (or (:uuid m) fallback-id)
                   :stopped stopped :failed (boolean (and (not stopped) error)) :reply reply}
            ok (assoc :preview reply))}))

(defn user-text
  "A user message's content as its text (its text blocks joined)."
  [content]
  (if (string? content)
    content
    (str/join "\n" (keep #(when (= "text" (:type %)) (:text %)) (when (sequential? content) content)))))

(defn tool-results
  "A user message's tool_result blocks."
  [content]
  (filter #(= "tool_result" (:type %)) (when (sequential? content) content)))

(defn with-result
  "A tool step's Msg with its result block b."
  [msg b]
  (cond-> (assoc msg :result {:error (boolean (:is_error b))
                              :text (text/fit (history/result-text (:content b)) 4000)})
    (:is_error b) (assoc :error true)))

(defn compact-note
  "A compact_boundary message as the chat's note."
  [m now]
  {:id (:uuid m) :role "note" :at now
   :text (str "Context compacted ("
              (js/Math.round (/ (or (get-in m [:compact_metadata :pre_tokens]) 0) 1000))
              "k tokens before).")})

(defn note-of
  "A system message's content as a note (a slash command's output, a warning); its id made from
  prefix and now when it has no uuid."
  [m prefix now]
  {:id (or (:uuid m) (str prefix now)) :role "note" :text (str (:content m)) :at now})

(defn task-notice?
  "Whether a system message is a background task's end the session file keeps as a notice."
  [m]
  (boolean (and (= "task_notification" (:subtype m)) (not (:ambient m)) (not (:skip_transcript m))
                (seq (:summary m)))))

(defn sent-msg
  "A message sent into the chat as its Msg; queued: sent while a turn runs."
  [sent-id message images queued now]
  (cond-> {:id sent-id :role "user" :text message :at now}
    (seq images) (assoc :images (count images))
    queued (assoc :queued true)))

(defn with-sent
  "The chat's live state with a sent Msg: the turn's name, kept aside until the session file has
  it; a message sent between turns starts a new turn."
  [s msg]
  (cond-> (-> s (assoc :turn-id (:id msg)) (assoc-in [:unsaved (:id msg)] msg))
    (not (:queued msg)) (assoc :turn [])))

(defn taken-in
  "The chat's live state once Claude Code took a queued message in: no longer queued, at the end
  of the turn."
  [s msg]
  (-> s
      (assoc-in [:unsaved (:id msg)] msg)
      (update :turn (fn [t] (conj (vec (remove #(= (:id msg) (:id %)) t)) msg)))))

(defn dropped
  "The chat's live state once Claude Code dropped the queued message sent-id."
  [s sent-id]
  (-> s
      (update :unsaved dissoc sent-id)
      (update :turn (fn [t] (vec (remove #(= sent-id (:id %)) t))))))

(defn context-of
  "getContextUsage's answer as the chat's context use, or nil when it holds no count."
  [c]
  (when (number? (:totalTokens c))
    {:used (:totalTokens c) :max (:maxTokens c)
     :percent (js/Math.round (or (:percentage c) 0)) :model (:model c)}))
