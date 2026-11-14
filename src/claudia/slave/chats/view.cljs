(ns claudia.slave.chats.view
  "What the page and the hub see of the chats, from the store's rows and the runner's live state:
  the chat list's rows, a new chat's colour, and a chat's messages with its live turn laid over."
  (:require [clojure.string :as str]))

(defn keeps-session?
  "An open chat keeps its Claude Code process, so other sessions can always message it: one in the
  list (not archived) and not passive (ash). Archived and passive chats let theirs go when idle."
  [row]
  (boolean (and row (not (:archived row)) (not= "ash" (:color row)))))

(defn- ask-preview
  "The open asks as the chat list's preview: a question's text, else a secret's or permit's line."
  [asks]
  (or (some #(get-in % [:questions 0 :question]) asks)
      (some #(case (:kind %)
               "secret" (str "Secret: " (:reason %))
               "permit" (str "Needs your permission: " (:tool %))
               nil)
            asks)))

(defn- ask-row
  "An open ask for the hub's alerts: its time as ago (t: now)."
  [{a :ask at :at} t]
  {:id (:id a) :kind (:kind a) :ago (- t (or at t))
   :text (case (:kind a)
           "secret" (:reason a)
           "permit" (str/join ": " (remove str/blank? [(:tool a) (:description a)]))
           (get-in a [:questions 0 :question]))})

(defn- last-turn
  "When and how the last turn ended, for the hub's pushes (ended: its time, t: now); nil before one."
  [r ended t]
  (when (pos? ended)
    {:id (or (:last-turn r) (str "ended-" ended)) :ago (- t ended)
     :stopped (:stopped r) :failed (:failed r) :reply (:reply r)}))

(defn- unseen?
  "Its last turn ended (not stopped) after the person last had it open, and the work it left
  running has settled."
  [r s working ended]
  (boolean (and (pos? ended) (not (:stopped r)) (not working) (not (:settling s))
                (> ended (or (:seen r) 0)))))

(defn row
  "One chat of the list (Row in PROTOCOL.md): r its store row, s its live state, agents how many of
  its tasks still run, t now. Times for the hub are ago (its clock may differ)."
  [r s agents t]
  (let [entries (vals (:asks s))
        asks (map :ask entries)
        working (or (boolean (:running s)) (pos? agents))
        ended (or (:ended r) 0)]
    {:id (:id r) :title (:title r) :folder (:folder r) :color (:color r)
     :running (boolean (:running s))
     :waiting (boolean (seq asks))
     :at (:at r)
     :started (:created r)
     :preview (or (ask-preview asks) (:preview r))
     ;; Work Claude started still runs: not done yet (README decision 25).
     :agents agents
     :settling (boolean (:settling s))
     :unseen (unseen? r s working ended)
     :archived (:archived r)
     ;; For the hub's phone alerts: when the last turn ended and how, and the open asks.
     :ended ended
     :lastTurn (last-turn r ended t)
     :runningFor (when (and working (:since s)) (- t (:since s)))
     :asks (mapv #(ask-row % t) entries)}))

(defn with-msg
  "The turn's Msgs with msg in: replacing the one with its id, else added (the newest 400 kept)."
  [turn msg]
  (let [t (vec turn)]
    (if-let [i (first (keep-indexed #(when (= (:id %2) (:id msg)) %1) t))]
      (assoc t i msg)
      (conj (if (> (count t) 400) (subvec t 1) t) msg))))

(defn unsaved-after
  "The sent messages still kept aside once the session file holds the ids in known."
  [unsaved known]
  (into {} (remove (fn [[k]] (known k))) unsaved))

(defn chat-msgs
  "A chat's messages: the session file's (hist) with this turn's live Msgs laid over them, then the
  turn's Msgs and the sent messages the file doesn't hold yet; the streaming text in its place.
  s: the chat's live state."
  [hist s]
  (let [known (set (map :id hist))
        turn (:turn s)
        streaming (:partial s)
        by-id (into {} (map (juxt :id identity)) turn)
        notes (set (keep #(when (= "note" (:role %)) (:text %)) hist))
        in-turn (set (map :id turn))
        msgs (-> (mapv #(get by-id (:id %) %) hist)
                 (into (remove #(or (known (:id %)) (and (= "note" (:role %)) (notes (:text %))))) turn)
                 (into (remove #(or (known (:id %)) (in-turn (:id %)))) (vals (:unsaved s))))]
    (if streaming (mapv #(if (= (:id %) (:id streaming)) streaming %) msgs) msgs)))
