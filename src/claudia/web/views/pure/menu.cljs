(ns claudia.web.views.pure.menu
  "Where a menu opened at the mouse sits, and the rail's subscription button, as plain data."
  (:require [clojure.string :as str]))

(defn menu-pos
  "A menu opened at x y kept 6 px inside the page's visible layer (l: its left top width height),
  the menu being w by h."
  [x y {:keys [left top width height]} w h]
  {:left (max 6 (min (- x left) (- width w 6)))
   :top (max 6 (min (- y top) (- height h 6)))})

(defn initials
  "\"Paul\" -> \"P\", \"Ananda Mishung\" -> \"AM\"."
  [person]
  (str/upper-case (apply str (take 2 (map first (str/split (str/trim (str person)) #"\s+"))))))

(defn plan-tip
  "The subscription button's hover text."
  [account multi? label saved]
  (str (if account (str account "'s Claude subscription") "Claude subscription")
       (when multi? (str " on " label))
       (if (> (count saved) 1) " · click to switch" " · click to add a token")))

(defn k-tokens
  "Tokens in thousands: 155k (0 for none)."
  [n]
  (str (js/Math.round (/ (or n 0) 1000)) "k"))
