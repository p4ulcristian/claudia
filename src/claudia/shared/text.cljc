(ns claudia.shared.text
  "Shortening text for lists, logs and prompts: the same rules in every program."
  (:require [clojure.string :as str]))

(defn cut
  "s (as text) kept to its first n characters, with marker after them when it was longer."
  [s n marker]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) marker) s)))

(defn fit
  "s (as text) in at most n characters: a longer one keeps n - 1 and ends in an ellipsis."
  [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn one-line
  "s (as text) on one line: every run of whitespace one space, trimmed."
  [s]
  (str/trim (str/replace (str s) #"\s+" " ")))
