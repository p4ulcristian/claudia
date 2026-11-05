(ns claudia.web.face.fx
  "The little things around Claudia's face (2026-11-05): thinking bubbles, !, ?, z z z, sparkles,
  hearts, notes, confetti, a rain cloud, words in a bubble. Each is SVG markup from plain numbers:
  p = how far along (0 to 1) for one-off effects, t = milliseconds for the ones that loop. They are
  drawn big, to read beside a 32-px button (16 units are about one pixel), to the right of the head."
  (:require [clojure.string :as str]
            [claudia.web.face.art :as art]))

(def ^:private pi js/Math.PI)
(defn- sin [v] (js/Math.sin v))
(defn- cos [v] (js/Math.cos v))
(defn- r1 [v] (.toFixed (js/Number v) 1))
(defn- fade [p] (r1 (- 1 p)))

(def ^:private star-d "M0 -30L8 -8L30 0L8 8L0 30L-8 8L-30 0L-8 -8Z")
(def ^:private heart-d "M0 14C-26 -6 -18 -30 0 -14C18 -30 26 -6 0 14Z")
(def ^:private note-d "M0 0a16 12 -20 1 0 1 0ZM14 -4V-70L40 -60V-44L14 -52")

(defn- at
  "A shape (path d) at x y, turned r degrees, scaled s, with its fill and opacity."
  [d [x y] r s fill o]
  (str "<path transform=\"translate(" (r1 x) " " (r1 y) ") rotate(" (r1 r) ") scale(" (r1 s) ")\" d=\"" d
       "\" fill=\"" fill "\" stroke=\"" art/ink "\" stroke-width=\"7\" opacity=\"" o "\"/>"))

(defn- text-at
  "Big hand-written text at x y (fill, outlined in ink)."
  [txt [x y] size fill o]
  (str "<text x=\"" (r1 x) "\" y=\"" (r1 y) "\" style=\"font:700 " (r1 size) "px var(--hand),sans-serif\" fill=\""
       fill "\" stroke=\"" art/ink "\" stroke-width=\"10\" paint-order=\"stroke\" opacity=\"" o "\">" txt "</text>"))

(defn- pop-in [p] (min 1 (* p 6)))

(defn dots
  "The thinking bubble: three dots taking turns to jump; fast? for many chats at once."
  [t fast?]
  (let [beat (if fast? 120 260)]
    (str "<g transform=\"translate(920 310)\"><rect x=\"-150\" y=\"-70\" width=\"300\" height=\"140\" rx=\"70\" fill=\""
         art/skin "\" stroke=\"" art/ink "\" stroke-width=\"14\"/>"
         (str/join (for [i (range 3)]
                     (str "<circle cx=\"" (- (* i 85) 85) "\" cy=\"" (r1 (* -26 (max 0 (sin (- (/ t beat) (* i 0.9))))))
                          "\" r=\"26\" fill=\"" art/ink "\"/>")))
         "</g>")))

(defn sweat [t]
  (let [p (mod (/ t 1300) 1)]
    (at "M0 -40C14 -12 24 2 24 16A24 24 0 0 1 -24 16C-24 2 -14 -12 0 -40Z"
        [745 (+ 430 (* p 90))] 0 (- 1.4 (* p 0.4)) "#9fd3f0" (fade p))))

(defn bang
  "! popping up (wiggling while t runs)."
  [p t]
  (text-at "!" [880 (- 330 (* 30 (pop-in p)) (* 10 (sin (/ t 90))))] (* 300 (pop-in p)) art/gold "1"))

(defn question [p]
  (text-at "?" [860 (- 320 (* 40 (pop-in p)))] (* 280 (pop-in p)) art/gold (r1 (min 1 (* 3 (- 1 p))))))

(defn zs
  "z z z drifting up from the sleeping face."
  [t]
  (str/join (for [i (range 3)
                  :let [p (mod (+ (/ t 2600) (/ i 3)) 1)]]
              (text-at "z" [(+ 790 (* p 160) (* 25 (sin (* p 8)))) (- 400 (* p 260))] (+ 110 (* p 120))
                       art/skin (r1 (sin (* p pi)))))))

(defn sparkles
  "Gold stars bursting out from the head."
  [p]
  (str/join (for [i (range 8)
                  :let [a (+ (* i (/ pi 4)) 0.3) r (+ 220 (* p 380))]]
              (at star-d [(+ 520 (* r (cos a))) (+ 520 (* r 0.9 (sin a)))] (* p 180) (- 2 p) art/gold (fade p)))))

(defn hearts
  "Pink hearts floating up."
  [p]
  (str/join (for [i (range 5)
                  :let [a (+ (* i 1.3) 0.4)]]
              (at heart-d [(+ 700 (* i 70) (* 30 (sin (+ (* p 9) a)))) (- 520 (* p 380) (* i 30))] (* 15 (sin a))
                  (+ 1.6 p) art/cheek (fade p)))))

(defn notes
  "Music notes rising to the right while she hums."
  [t]
  (str/join (for [i (range 2)
                  :let [p (mod (+ (/ t 1800) (/ i 2)) 1)]]
              (at note-d [(+ 780 (* p 220)) (- 520 (* p 300))] (* 20 (sin (* p 7))) 2 art/gold (r1 (sin (* p pi)))))))

(defn orbit
  "Little stars circling over a dizzy head."
  [t]
  (str/join (for [i (range 4)
                  :let [a (+ (/ t 260) (* i (/ pi 2)))]]
              (at star-d [(+ 520 (* 230 (cos a))) (+ 250 (* 60 (sin a)))] (/ t 5) 1.3 art/gold "1"))))

(defn cloud
  "A grey cloud with rain: no server answers."
  [t]
  (str "<g transform=\"translate(900 300)\"><path d=\"M-120 40a60 60 0 0 1 20 -110a80 80 0 0 1 150 -10a60 60 0 0 1 70 120Z\""
       " fill=\"#aab2b8\" stroke=\"" art/ink "\" stroke-width=\"14\"/>"
       (str/join (for [i (range 3)
                       :let [p (mod (+ (/ t 700) (/ i 3)) 1)]]
                   (str "<path d=\"M" (- (* i 80) 80) " " (r1 (+ 70 (* p 120))) "l-12 34\" stroke=\"#9fd3f0\" stroke-width=\"14\""
                        " stroke-linecap=\"round\" opacity=\"" (fade p) "\"/>")))
       "</g>"))

(defn poof
  "Puffs of smoke: a chat went away."
  [p]
  (str/join (for [i (range 6)
                  :let [a (* i (/ pi 3)) r (* 160 p)]]
              (str "<circle cx=\"" (r1 (+ 900 (* r (cos a)))) "\" cy=\"" (r1 (+ 420 (* r (sin a)))) "\" r=\""
                   (r1 (+ 30 (* 50 p))) "\" fill=\"#dde2e5\" stroke=\"" art/ink "\" stroke-width=\"8\" opacity=\"" (fade p) "\"/>"))))

(def ^:private confetti-colours ["#e8988a" "#efe19a" "#9fd3f0" "#a8d8a0" "#c9b3f0" "#D97757"])

(defn confetti
  "Paper bits falling round her: several chats done at once."
  [p]
  (str/join (for [i (range 14)
                  :let [x (+ 300 (* i 60) (* 40 (sin (+ (* p 10) i))))
                        y (+ 120 (* p 700) (* 50 (mod (* i 7) 5)))]]
              (str "<rect x=\"-14\" y=\"-8\" width=\"28\" height=\"16\" transform=\"translate(" (r1 x) " " (r1 y) ") rotate("
                   (r1 (+ (* p 720) (* i 40))) ")\" fill=\"" (confetti-colours (mod i 6)) "\" opacity=\"" (fade (* p p)) "\"/>"))))

(defn word
  "A word in a speech bubble (hi!, achoo!, hic), popping in."
  [txt p]
  (let [s (r1 (pop-in p))]
    (str "<g transform=\"translate(930 320) scale(" s ")\" opacity=\"" (r1 (min 1 (* 4 (- 1 p)))) "\">"
         "<path d=\"M-170 -80H170A40 40 0 0 1 210 -40V40A40 40 0 0 1 170 80H-60L-150 140L-120 80H-170A40 40 0 0 1 -210 40V-40A40 40 0 0 1 -170 -80Z\""
         " fill=\"" art/skin "\" stroke=\"" art/ink "\" stroke-width=\"14\"/>"
         (text-at txt [-150 45] 150 art/ink "1") "</g>")))

(defn swirl
  "A spinning scribble: something went wrong."
  [p]
  (str "<g transform=\"translate(860 320) rotate(" (r1 (* p 720)) ") scale(2.4)\" opacity=\"" (fade p) "\">"
       "<path d=\"M0 0m-28 0a28 28 0 1 1 56 0a20 20 0 1 1 -40 0a12 12 0 1 1 24 0\" fill=\"none\" stroke=\"" art/ink
       "\" stroke-width=\"7\" stroke-linecap=\"round\"/></g>"))

(defn draw-one
  "One effect [kind arg] at time t; p-effects take their progress as arg."
  [[kind arg] t]
  (case kind
    :dots (dots t arg) :sweat (sweat t) :bang (bang arg t) :question (question arg) :z (zs t)
    :sparkles (sparkles arg) :hearts (hearts arg) :notes (notes t) :orbit (orbit t) :cloud (cloud t)
    :poof (poof arg) :confetti (confetti arg) :swirl (swirl arg) :word (word (first arg) (second arg))
    ""))

(defn draw
  "Every effect of a pose at time t, as one string of SVG markup."
  [effects t]
  (str/join (map #(draw-one % t) effects)))
