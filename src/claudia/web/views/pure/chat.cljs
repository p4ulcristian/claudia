(ns claudia.web.views.pure.chat
  "The chat column's decisions on plain data: which reply closed the turn that drew a picture, what
  the messages column holds, where it scrolls, what the composer sends."
  (:require [clojure.string :as str]
            [claudia.web.views.pure.steps :as steps]
            [claudia.web.views.pure.words :as words]))

(defn msg-anchor
  "What a message's element is found by (data-at): its id, a Claude text's without its \":n\" (search
  hits name the API message)."
  [id]
  (first (str/split (str id) #":")))

(defn- opens-turn?
  "Whether a message starts a turn: one the person sent (an answer to Claude's question stays in
  its turn)."
  [{:keys [role id]}]
  (and (= "user" role) (not (str/starts-with? (str id) "answer-"))))

(defn- close-turn [{:keys [open reply] :as acc}]
  (cond-> acc (and open reply) (update :out assoc open reply)))

(defn- turn-step [acc m]
  (cond
    (opens-turn? m) (assoc (close-turn acc) :open (:id m) :reply nil)
    (and (= "assistant" (:role m)) (not (:aside m))) (assoc acc :reply (:id m))
    :else acc))

(defn turn-replies
  "Each turn's closing reply: {id of the message that started it -> id of Claude's last text before
  the next turn}; the last turn has none while it runs (running?)."
  [msgs running?]
  (let [acc (reduce turn-step {:out {}} msgs)]
    (:out (if running? acc (close-turn acc)))))

(defn reply-steps
  "Which reply wears which picture's step number (1-based, the slider's numbers): {reply id -> n}.
  A picture whose turn has no reply among msgs (imported, not loaded yet, still running) has none."
  [pictures msgs running?]
  (let [replies (turn-replies msgs running?)]
    (into {} (keep-indexed (fn [i p] (when-let [r (get replies (:turn p))] [r (inc i)]))) pictures)))

(defn step-reply
  "The id of the reply that closed the turn which drew picture i, or nil."
  [pictures msgs running? i]
  (get (turn-replies msgs running?) (:turn (get pictures i))))

(defn seek-goal
  "Where the chat goes when the person picks picture i of the bar (README decision 99): the newest
  sends it back to its end ([:end]), an older one to the reply that closed its turn ([:at anchor]);
  nil when that turn has no reply on the page or there is no picture i."
  [pictures msgs running? i]
  (cond
    (not (< -1 i (count pictures))) nil
    (= i (dec (count pictures))) [:end]
    :else (some->> (step-reply pictures msgs running? i) msg-anchor (vector :at))))

(defn server-state
  "What the header shows of a server's connection (status): :off when it is closed, :offline when
  the device has no network, nil when it is open, :reconnecting for anything else."
  [status]
  (cond (= :closed status) :off
        (= :offline status) :offline
        (not= :open status) :reconnecting))

(defn link-state
  "What the strip over the composer shows: :back (\"Connected\", for a moment after a reconnect),
  nil while connected, :offline (no network), :reconnecting (it was up) or :connecting (not yet)."
  [[status back? offline? was-up?]]
  (cond (= :open status) (when back? :back)
        offline? :offline
        was-up? :reconnecting
        :else :connecting))

(def link-text
  {:back "Connected"
   :offline "No network · messages wait and go when it is back"
   :reconnecting "Reconnecting… · messages wait and go when it is back"
   :connecting "Connecting…"})

(defn new-title
  "The typed title, trimmed, when it is a new one; else nil."
  [typed title]
  (let [t (str/trim typed)]
    (when (and (seq t) (not= t title)) t)))

(defn elapsed-text
  "How long since since (ms) at now: 42s, 3m 5s."
  [since now]
  (let [s (js/Math.floor (/ (- now since) 1000))]
    (if (< s 60) (str s "s") (str (quot s 60) "m " (mod s 60) "s"))))

(defn feed
  "What the messages column shows of a chat's state: its messages, those sent while Claude worked and
  not taken in yet (queued, shown below), the rest as blocks (steps folded), and the user message the
  running turn started from."
  [{:keys [messages running]}]
  (let [msgs (vec messages)
        queued (filterv :queued msgs)
        shown (if (seq queued) (filterv (complement :queued) msgs) msgs)]
    {:msgs msgs
     :queued queued
     :blocks (steps/blocks shown (steps/folded-ids shown running))
     :last-user (when running (:id (last (filter #(and (= "user" (:role %)) (not (:queued %))) msgs))))}))

(defn user-marks
  "What a user message in the feed allows: whether it can be forked from (its menu, README decision
  98)."
  [{:keys [id]} {:keys [read-only? last-user]}]
  {:forkable? (and (not read-only?) (not (str/starts-with? (str id) "answer-")) (not= id last-user))})

(defn working-label
  "The working line while a turn runs: waiting on the person, else Claude's note."
  [{:keys [asks note]}]
  (cond (some #(= "permit" (:kind %)) asks) "Waiting for your OK"
        (seq asks) "Waiting for your answer"
        :else (or note "Working")))

(defn at-bottom?
  "Whether a scroller is within 80 px of its end."
  [scroll-height scroll-top client-height]
  (< (- scroll-height scroll-top client-height) 80))

(defn sent?
  "Whether the outbox grew from before to now (counts): the person just sent a message."
  [before now]
  (> now (or before 0)))

(defn wants-older?
  "Near the top, with earlier messages still to read and none loading."
  [state scroll-top]
  (boolean (and (< scroll-top 600) (:more state) (not (:loading-older state)))))

(defn kept-top
  "Older messages came in above (the first message changed in the same chat, the person scrolled
  up): the scrollTop that keeps what was on screen where it was; nil otherwise. anchor is
  [chat first-id scrollHeight] as last seen."
  [[was-ck was-first was-height] ck first-id stuck? scroll-top scroll-height]
  (when (and was-first (= was-ck ck) (not= was-first first-id) (not stuck?))
    (+ scroll-top (- scroll-height was-height))))

(defn typed-answer
  "A message typed while Claude asks a question answers it: each question gets the text. nil when it
  is no answer (no question asked, nothing typed, files attached)."
  [ask text attached]
  (when (and ask (= "question" (:kind ask)) (seq text) (empty? attached))
    (into {} (map (fn [q] [(:question q) text]) (:questions ask)))))

(defn sendable?
  "Whether the composer can send: text or an attachment, and no attachment still on its way."
  [text attached]
  (boolean (and (or (not (str/blank? text)) (seq attached))
                (not (some :busy attached)))))

(defn placeholder
  "The composer's placeholder."
  [asking running]
  (cond asking "Answer on the right, or type an answer here…"
        running "Add to this turn…"
        :else "Message Claude…"))

(defn read-only-note
  "Under another person's chat: whose it is."
  [view]
  (let [v (str view)]
    (str (if (seq v) (str (words/capital v) "'s chat") "Someone else's chat") " · read only")))

(defn chat-plan
  "The saved plan ({:key :name}) a chat runs on: its pick when that is saved on its server, else the
  server's default (default-key); nil when neither is among plans."
  [plans picked default-key]
  (let [by-key (into {} (map (juxt :key identity)) plans)]
    (or (by-key picked) (by-key default-key))))
