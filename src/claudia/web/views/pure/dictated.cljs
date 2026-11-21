(ns claudia.web.views.pure.dictated
  "What the mic put into a draft, and the person's word list. Each clip's words come as a piece
  {:text :raw :shown}: text cleaned up by the listen service, raw as heard, shown which of the two
  the draft holds (:text first; a tap on the composer's chip swaps). A word Claude offers in the
  chat (README decision 95) shows its card only when card-shown? says so."
  (:require [clojure.string :as str]))

(def ^:private kept-pieces "The most pieces one draft remembers." 8)

(defn added
  "pieces (a draft's, oldest first) with heard ({:text :raw}) at the end, shown as the clean text;
  pieces unchanged when the text is blank. Only the last kept-pieces stay."
  [pieces {:keys [text raw]}]
  (let [t (str/trim (str text))]
    (if (str/blank? t)
      (vec pieces)
      (vec (take-last kept-pieces (conj (vec pieces) {:text t :raw (str/trim (str (or raw t))) :shown :text}))))))

(defn shown-text "The words of piece as the draft holds them." [{:keys [shown] :as piece}] (get piece shown))

(defn swappable
  "The last piece whose raw and clean words differ and whose shown words are still in draft, or nil."
  [pieces draft]
  (last (filter #(and (not= (:text %) (:raw %)) (str/includes? (str draft) (shown-text %))) pieces)))

(defn- replace-last [s from to]
  (let [i (.lastIndexOf s from)]
    (if (neg? i) s (str (subs s 0 i) to (subs s (+ i (count from)))))))

(defn swapped
  "[draft' pieces'] with piece's shown words in draft (the last time they occur) swapped for the
  other version, raw for clean or back."
  [draft pieces piece]
  (let [other (if (= :text (:shown piece)) :raw :text)]
    [(replace-last (str draft) (shown-text piece) (get piece other))
     (mapv #(if (identical? % piece) (assoc % :shown other) %) pieces)]))

(defn has-word?
  "Whether words (My words) hold w, case ignored."
  [words w]
  (boolean (some #(= (str/lower-case %) (str/lower-case w)) words)))

(defn with-word
  "words with w (trimmed) at the end, unless it is blank or already there."
  [words w]
  (let [t (str/trim (str w))]
    (if (or (str/blank? t) (has-word? words t)) (vec words) (conj (vec words) t))))

(defn card-shown?
  "Whether the chat shows the card for an offered word ({:word}): the person's offers are on, the
  word is not blank, not in their words and not one they said No to (case ignored)."
  [{:keys [word]} words {:keys [declined offers]}]
  (boolean (and offers (not (str/blank? word)) (not (has-word? words word)) (not (has-word? declined word)))))

(defn without-word
  "words without w (case ignored)."
  [words w]
  (vec (remove #(= (str/lower-case %) (str/lower-case (str w))) words)))
