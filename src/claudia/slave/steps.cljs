(ns claudia.slave.steps
  "The picture history as Claude sees it (README decision 102): numbered steps. A step's number is its
  1-based place in the chat's picture list (claudia.slave.pictures/listing, oldest first), the
  number the page's bar shows; the last one is live. Pure: lists and scenes in, text and data out."
  (:require [clojure.string :as str]
            [claudia.shared.protocol :as p]))

(defn number-of
  "The step number of turn in listing, or nil when it is not there."
  [listing turn]
  (some (fn [[i pic]] (when (= turn (:turn pic)) (inc i))) (map-indexed vector listing)))

(defn turn-of
  "The turn of step n in listing, or nil when there is no such step."
  [listing n]
  (when (and (integer? n) (<= 1 n (count listing)))
    (:turn (nth listing (dec n)))))

(defn viewing-note
  "What Claude is told with a message the user wrote while looking at an older step (turn viewing),
  or nil: on the live step (or none named, or one no longer there) nothing is said."
  [listing viewing]
  (let [n (number-of listing viewing)
        total (count listing)]
    (when (and n (< n total))
      (str "The user is looking at step " n " of " total " (step " total " is live) while writing this."))))

(defn step-error
  "Why n names no step of listing, or nil when it does."
  [listing n]
  (cond (not (and (integer? n) (pos? n))) "A step number is a whole number from 1."
        (<= n (count listing)) nil
        (empty? listing) (str "There is no step " n ": this chat has no steps yet.")
        :else (str "There is no step " n ": this chat has steps 1 to " (count listing) ".")))

(defn from-turn
  "Where a draw call's new step starts (input's from and clear): {:turn} of step from, nil without
  from, or {:error} for a step that is not there or from with clear (they contradict)."
  [listing {:keys [from clear]}]
  (cond (nil? from) nil
        clear {:error "Give either from or clear, not both: from starts the step as a copy of an earlier one, clear starts it empty."}
        :else (if-let [why (step-error listing from)] {:error why} {:turn (turn-of listing from)})))

(defn changes
  "\"2 blocks changed, 1 removed\" for a draw call's input on base (the scene it started from) and the
  ids it changed (claudia.slave.scene/apply-draw), or nil when no block changed or left."
  [base {:keys [blocks removeBlocks]} changed]
  (let [touched (set changed)
        n (count (filter touched (distinct (keep :id blocks))))
        had (set (map :id (:blocks base)))
        gone (count (filter had (distinct removeBlocks)))]
    (when (pos? (+ n gone))
      (str/join ", " (remove nil? [(when (pos? n) (str n (if (= 1 n) " block" " blocks") " changed"))
                                   (when (pos? gone) (str gone " removed"))])))))

(defn- how
  "How the step began: fresh, a copy of step from, or the step before it changed."
  [n {:keys [clear from]}]
  (cond clear "fresh (clear)"
        from (str "copied from " from)
        (> n 1) (str "modified from " (dec n))
        :else "the first step"))

(defn head
  "The draw result's first line: the step it made or updated (again?: an earlier draw of this turn
  made it), how it began, and what changed."
  [n again? input change]
  (str/join " · " (remove nil? [(if again? (str "Updated step " n " (this turn's step)") (str "Step " n))
                                (how n input)
                                change])))

(defn- clock
  "A time (ms) as text for Claude: UTC to the minute."
  [at]
  (if (and (number? at) (pos? at))
    (str (subs (.toISOString (js/Date. at)) 0 16) "Z")
    "unknown time"))

(def ^:private listed "The most steps the list names (the newest ones)." 60)

(defn list-text
  "The steps tool with no number: one line per step (number, title, time; the last marked live),
  the newest `listed` when there are more."
  [listing]
  (let [total (count listing)
        shown (drop (max 0 (- total listed)) (map-indexed vector listing))
        line (fn [[i {:keys [title at]}]]
               (str (inc i) ". " (if (seq title) title "(no title)") " · " (clock at)
                    (when (= (inc i) total) " · live")))]
    (if (zero? total)
      "No steps yet: nothing has been drawn in this chat."
      (str total (if (= 1 total) " step" " steps") ", oldest first"
           (when (> total listed) (str " (the newest " listed " shown)"))
           ":\n" (str/join "\n" (map line shown))))))

(def scene-limit "The most characters of a step's scene the steps tool sends." 30000)

(defn- stub
  "A block without its data, saying how big that data is and how to read it."
  [n b size]
  (-> (select-keys b [:id :name :form :width])
      (assoc :data (str "(left out: " size " characters; read it with steps { number: " n ", block: \"" (:id b) "\" })"))))

(defn- fitted
  "The blocks whole while their JSON fits in limit characters, the rest as stubs."
  [n blocks limit]
  (:out (reduce (fn [{:keys [left out]} b]
                  (let [size (count (p/write-json b))]
                    (if (<= size left)
                      {:left (- left size) :out (conj out b)}
                      {:left left :out (conj out (stub n b (count (p/write-json (:data b)))))})))
                {:left limit :out []} blocks)))

(defn scene-text
  "The steps tool with a number (n of total): that step's scene as JSON, its blocks whole while they
  fit in limit characters (the rest without their data); block: only that block, whole."
  [n total scene block limit]
  (let [label (str "Step " n " of " total (if (= n total) " (live)" (str " (read only; step " total " is live)")))
        pick (when block (some #(when (= block (:id %)) %) (:blocks scene)))]
    (cond (and block (not pick)) (str "Step " n " has no block \"" block "\".")
          pick (str label ", block \"" block "\":\n" (p/write-json pick))
          :else (str label ":\n" (p/write-json {:title (or (:title scene) "")
                                                :blocks (fitted n (:blocks scene) limit)})))))
