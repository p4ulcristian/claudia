(ns claudia.web.views.pure.words
  "Small words the views share: a capital first letter, a server's initial, the chat colours, a long
  path clipped to its end. Plain data only, so the tests run them on Node."
  (:require [clojure.string :as str]))

(def chat-colours
  "The colours a chat can have (passive ash aside)."
  ["burgundy" "red" "orange" "amber" "yellow" "lime" "green" "teal" "cyan"
   "sky" "blue" "indigo" "violet" "purple" "magenta" "pink" "brown"])

(def colours-with-ash
  "The chat colours and passive ash, last."
  (conj chat-colours "ash"))

(defn colour-var
  "The CSS variable of a chat colour (\"var(--c-teal)\"); nil for anything that is not one."
  [c]
  (when (some #{c} colours-with-ash) (str "var(--c-" c ")")))

(defn capital
  "The word with its first letter upper case: \"paul\" -> \"Paul\"."
  [s]
  (str (str/upper-case (subs s 0 1)) (subs s 1)))

(defn initial
  "A server's mark: the first letter of its label, upper case."
  [label]
  (str/upper-case (subs (str label) 0 1)))

(defn clip
  "Long paths keep their end, where the file and project names are."
  [p limit]
  (if (<= (count p) limit)
    p
    (loop [parts (vec (str/split p #"/")) out nil]
      (let [out (or out (peek parts))
            parts (if (= out (peek parts)) (pop parts) parts)]
        (if (and (seq parts) (<= (+ (count out) (count (peek parts)) 1) (- limit 2)))
          (recur (pop parts) (str (peek parts) "/" out))
          (str "…/" out))))))

(defn plural
  "n and the noun, an s after it unless n is 1: 1 file, 3 files."
  [n noun]
  (str n " " noun (when (not= 1 n) "s")))
