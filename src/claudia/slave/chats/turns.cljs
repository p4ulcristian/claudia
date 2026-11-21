(ns claudia.slave.chats.turns
  "A running turn as data: the SDK's messages as Msgs and notes, how a turn ended, a sent message
  and what Claude Code did with it, the chat's context use."
  (:require [clojure.string :as str]
            [claudia.shared.text :as text]
            [claudia.slave.chats.titles :as titles]
            [claudia.slave.history :as history]
            [claudia.slave.tools :as tools]))

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

(defn stop-verdict
  "What the Stop hook answers for a turn whose final reply is reply (nil: none): a reply over the
  limit is handed back to Claude (sent: how often this turn's already was), up to
  tools/reply-sends times; else the turn ends."
  [reply sent]
  (let [n (count (str/trim (str reply)))]
    (if (and (> n tools/reply-limit) (< sent tools/reply-sends))
      {:decision "block" :reason (tools/too-long-reason n)}
      {:continue true})))

(defn last-replies
  "The texts Claude ended the turn's Msgs with (after its last step), newest first."
  [turn]
  (vec (take-while #(= "assistant" (:role %)) (rseq (vec turn)))))

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
  it; a message sent between turns starts a new turn, not begun until Claude Code says so."
  [s msg]
  (cond-> (-> s (assoc :turn-id (:id msg)) (assoc-in [:unsaved (:id msg)] msg))
    (not (:queued msg)) (assoc :turn [] :begun false)))

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

(def dropped-phases
  "command_lifecycle states that end a queued message without it running: removed (cancelled),
  the session ended with it queued (discarded), or the session would not take it (refused)."
  #{"cancelled" "discarded" "refused"})

(defn settled
  "The chat's live state once Claude Code took the sent message sent-id in or dropped it: a now
  press waiting on it and a removal asked for it are done."
  [s sent-id]
  (cond-> s
    (:unqueue s) (update :unqueue disj sent-id)
    (= sent-id (get-in s [:now :msg])) (dissoc :now)))

(defn now-stop?
  "Whether a now press stops the running turn at this point: its message is in Claude Code's queue
  (behind the ones queued before it) and the turn has begun (a stop before the session's first turn
  has begun would make that turn start stopped, with every queued message in it)."
  [s]
  (boolean (and (:running s) (:begun s) (get-in s [:now :queued]))))

(defn unqueue-error
  "What the person reads when removing a queued message failed with the error text e."
  [e]
  (if (str/includes? (str e) "no control method")
    "Removing a queued message needs this server's session host restarted: it runs older code."
    (str "The queued message could not be removed: " e)))

(defn context-of
  "getContextUsage's answer as the chat's context use, or nil when it holds no count."
  [c]
  (when (number? (:totalTokens c))
    {:used (:totalTokens c) :max (:maxTokens c)
     :percent (js/Math.round (or (:percentage c) 0)) :model (:model c)}))

(defn init-starts-running?
  "Whether a turn's init message m (running: the chat already counts a turn running) makes the
  chat's turn run from now: a top-level init of a turn not yet counted, also one Claude Code started
  by itself after a background task's end, whose first answer may be slow (README decision 105)."
  [m running]
  (and (nil? (:parent_tool_use_id m)) (not running)))

(defn stale-restart?
  "Whether a chat's session (s: its live state; work-running?: background work it started still
  runs) starts again now: held, no turn running, not settling (the last background task just ended
  and its notice may be starting a turn, README decision 105), and its plan changed, or it is stale
  (other tools or instructions) with no work left running."
  [{:keys [live running settling plan-changed stale]} work-running?]
  (boolean (and live (not running) (not settling)
                (or plan-changed (and stale (not work-running?))))))

