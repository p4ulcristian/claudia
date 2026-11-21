(ns claudia.slave.chats.nudge
  "The empty-turn catch (README decision 104): a turn a background task's end started that ended
  with no real reply wakes Claude once, so the plan it told the user goes on."
  (:require [clojure.string :as str]))

(def stock-replies
  "The stock phrases Claude ends a turn with when it takes a message for one that needs no answer,
  lower case, without the closing full stop. Only the ones seen in real sessions."
  #{"no response requested"})

(defn empty-reply?
  "Whether a turn's final reply says nothing: blank, or only a stock phrase (stock-replies; any
  case, a closing full stop or brackets around it allowed)."
  [reply]
  (let [t (-> (str/lower-case (str/trim (str reply)))
              (str/replace #"^\((.*)\)$" "$1")
              str/trim
              (str/replace #"\.+$" "")
              str/trim)]
    (or (= "" t) (contains? stock-replies t))))

(defn notification-turn?
  "Whether the result message m ends a turn Claude Code started for a background task's end: the
  turn's origin (Claude Code stamps it on the result) is a task notification of no special kind (a
  scheduled task, a peer's message or an inbox delivery has a subkind)."
  [m]
  (let [o (:origin m)]
    (boolean (and (= "task-notification" (:kind o)) (nil? (:subkind o))))))

(defn notice-of
  "A background task's end (its task_notification message m) as the chat's waiting notice: the
  message's id, the task's name (nil: none known), how it ended, when it came (now)."
  [m task-name now]
  {:id (:uuid m) :task (not-empty task-name) :status (:status m) :at now})

(defn kept-notice
  "The notice still waiting once a turn that began at since ended: only one that came during the
  turn (it may start the next one); an older one was this turn's."
  [notice since]
  (when (and notice (> (:at notice) (or since 0))) notice))

(defn- quiet-turn?
  "Whether the result message m ends a turn that a background task's end started, that succeeded
  and said nothing, with no sent message waiting in Claude Code's queue."
  [m]
  (and (notification-turn? m) (= "success" (:subtype m)) (not (:is_error m))
       (empty-reply? (:result m)) (zero? (or (:queued_turn_count m) 0))))

(defn nudge?
  "Whether Claudia wakes Claude after the turn the result message ended. Never for a turn the person
  stopped, an archived or moving chat, while other background work of the chat still runs (its own
  end comes next), for a task that was stopped, for a notice that came after the turn began (not
  this turn's), or once any message went in after the notice (the person's, or this nudge)."
  [{:keys [result stopped archived moving working notice since sent-at]}]
  (boolean (and (quiet-turn? result) (not stopped) (not archived) (not moving)
                (zero? (or working 0))
                notice (contains? #{"completed" "failed"} (:status notice))
                (< (:at notice) (or since 0))
                (< (or sent-at 0) (:at notice)))))

(defn still-quiet?
  "Whether the chat is as quiet as when the nudge was decided (before: its live state then, now:
  its live state now): no turn runs and nothing was sent meanwhile."
  [before now]
  (and (not (:running now)) (= (:sent-at before) (:sent-at now))))

(defn nudge-text
  "What Claude is told; task: the finished task's name (nil: none known)."
  [task]
  (str "You ended your turn without a reply after "
       (if task (str "\"" task "\"") "background work")
       " finished. Continue the plan you told the user, or say in one line that nothing is left."))
