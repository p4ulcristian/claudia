(ns claudia.web.views.pure.chat
  "The chat column's decisions on plain data: which picture a turn shows, the count on the Chats
  button, what the messages column holds, where it scrolls, what the composer sends."
  (:require [clojure.string :as str]
            [claudia.web.views.pure.steps :as steps]
            [claudia.web.views.pure.words :as words]))

(defn- index-where [pred xs]
  (first (keep-indexed (fn [i x] (when (pred x) i)) xs)))

(defn turn-picture
  "The index of the picture a turn (its user message's id) shows: its own, else the last one drawn
  before the next user message; nil when there is none."
  [pictures msgs msg-id]
  (let [at (:at (some #(when (= msg-id (:id %)) %) msgs) 0)
        next-at (some #(when (and (= "user" (:role %)) (> (:at % 0) at)) (:at %)) msgs)]
    (or (index-where #(= msg-id (:turn %)) pictures)
        (last (keep-indexed (fn [i p] (when (or (nil? next-at) (< (:at p 0) next-at)) i)) pictures)))))

(defn others-count
  "For the Chats button: the other chats (not the open one, not off) waiting on the person, and those
  finished unseen (none in a read-only view)."
  [rows open read-only?]
  (let [others (remove #(or (= open [(:server %) (:id %)]) (:off %)) rows)]
    {:waiting (count (filter :waiting others))
     :done (if read-only? 0 (count (filter #(and (not (:waiting %)) (:unseen %)) others)))}))

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

(defn turn-links
  "The link under a user message: picture, 2 files, or both."
  [picture? files]
  (str/join " · " (remove nil? [(when picture? "picture")
                                (when (pos? files) (str files (if (= 1 files) " file" " files")))])))

(defn feed
  "What the messages column shows of a chat's state: its messages, those sent while Claude worked and
  not taken in yet (queued, shown below), the rest as blocks (steps folded), and the user message the
  running turn started from."
  [{:keys [messages running]}]
  (let [msgs (vec messages)
        queued (filterv :queued msgs)]
    {:msgs msgs
     :queued queued
     :blocks (steps/blocks (if (seq queued) (filterv (complement :queued) msgs) msgs))
     :last-user (when running (:id (last (filter #(and (= "user" (:role %)) (not (:queued %))) msgs))))}))

(defn user-marks
  "What a user message in the feed shows: whether its picture is the one viewed, whether its turn
  drew one, how many files it changed, whether it can be forked from."
  [{:keys [id]} {:keys [viewing picture-turns change-turns read-only? last-user]}]
  {:viewing? (= viewing id)
   :picture? (contains? picture-turns id)
   :files (get change-turns id 0)
   :forkable? (and (not read-only?) (not (str/starts-with? (str id) "answer-")) (not= id last-user))})

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
