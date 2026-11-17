(ns claudia.web.views.pure.dictated
  "What the mic put into a draft, and what the person changed in it before sending. Each clip's
  words come as a piece {:text :raw :shown}: text cleaned up by the listen service, raw as heard,
  shown which of the two the draft holds (:text first; a tap on the composer's chip swaps). When
  the draft goes, a piece whose words went out with exactly one word changed, to one that looks
  like a name or a term, gives that word: the page offers to add it to My words."
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

(def ^:private edge-marks (js/RegExp. "^[\\p{P}\\p{S}]+|[\\p{P}\\p{S}]+$" "gu"))
(def ^:private a-letter (js/RegExp. "\\p{L}" "u"))

(defn- bare
  "A word without the punctuation round it."
  [w]
  (.replace (str w) edge-marks ""))

(defn- tokens [s] (vec (remove str/blank? (str/split (str/trim (str s)) #"\s+"))))

(defn- sentence-start?
  "Whether the word at i in toks starts a sentence (the first, or after a word ending in . ! ? :)."
  [toks i]
  (or (zero? i) (boolean (re-find #"[.!?:]$" (nth toks (dec i))))))

(defn- upper? [c] (and (not= c (str/lower-case c)) (= c (str/upper-case c))))

(defn term?
  "Whether the word at i in toks (bare: w) looks like a name or a term: a capital after its first
  letter (GitHub, IR), a digit, - _ or . inside, or a capital first letter not at a sentence's start."
  [w toks i]
  (and (<= 2 (count w) 60)
       (.test a-letter w)
       (boolean (or (re-find #"\d" w)
                    (re-find #"\w[-_.]\w" w)
                    (some upper? (rest (seq w)))
                    (and (upper? (subs w 0 1)) (not (sentence-start? toks i)))))))

(defn- window-diffs
  "For each place piece's words d could sit in the sent words s: [start [index-in-d ...]] where they differ."
  [d s]
  (for [i (range (inc (- (count s) (count d))))]
    [i (filterv #(not= (nth d %) (nth s (+ i %))) (range (count d)))]))

(defn- one-change
  "[at old fixed] when the piece's words d sit in s exactly once with one word changed (and nowhere
  unchanged), else nil. A one-word piece only counts when it was sent alone."
  [d s]
  (let [ws (window-diffs d s)
        ones (distinct (for [[i js] ws :when (= 1 (count js))] [(+ i (first js)) (nth d (first js))]))]
    (when (and (seq d) (>= (count s) (count d))
               (not-any? #(empty? (second %)) ws)
               (= 1 (count ones))
               (or (> (count d) 1) (= 1 (count s))))
      (let [[at old] (first ones)] [at old (nth s at)]))))

(defn corrected-word
  "The word the person put in place of one of piece's words before sending text, when it is the
  only change, not just a change of case, and looks like a name or a term; else nil."
  [piece text]
  (let [toks (tokens text)
        [at old fixed] (one-change (mapv bare (tokens (shown-text piece))) (mapv bare toks))]
    (when (and fixed (not= (str/lower-case old) (str/lower-case fixed)) (term? fixed toks at))
      fixed)))

(defn offered-word
  "The first word corrected in any of a draft's pieces when text went out, or nil."
  [pieces text]
  (some #(corrected-word % text) pieces))

(defn has-word?
  "Whether words (My words) hold w, case ignored."
  [words w]
  (boolean (some #(= (str/lower-case %) (str/lower-case w)) words)))

(defn with-word
  "words with w (trimmed) at the end, unless it is blank or already there."
  [words w]
  (let [t (str/trim (str w))]
    (if (or (str/blank? t) (has-word? words t)) (vec words) (conj (vec words) t))))
