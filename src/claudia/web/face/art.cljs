(ns claudia.web.face.art
  "Claudia's face as drawing data (2026-11-05): the logo girl turned three-quarters in the toon look
  (orange bob, cream face, spark hair clip, ink outlines), traced from a Blender render. Coordinates
  are that render's 1024-px frame; the face sits in view-box. Eyes, mouths and effects come in kinds,
  each a pure function of where it is and how far along it is (p from 0 to 1)."
  (:require [clojure.string :as str]
            [claudia.web.logo :as logo]))

(def view-box "250 240 540 560")
(def ink "#2b2420")
(def hair "#D97757")
(def skin "#f9e4cf")
(def cheek "#e8988a")
(def gold "#efe19a")

(def back-hair
  (str "M300 735C270 725 255 715 268 695C290 665 292 620 292 560C292 420 340 275 512 262C650 262 735 360 740 520"
       "C742 620 738 690 760 718C778 740 772 758 745 768C680 792 590 792 512 790L548 752L480 600L405 718"
       "C360 742 330 762 318 760C300 756 280 745 300 735Z"))
(def face-shape "M324 548C380 512 430 488 470 476L600 508L602 600C600 680 575 730 530 744C480 756 430 742 400 718C345 676 322 620 324 548Z")
(def lock-fill "M600 505C606 600 600 680 560 742L516 785C590 788 660 783 704 772L704 505Z")
(def lock-edge "M600 505C606 600 600 680 560 742L512 788")
(def spark logo/spark)
(def spark-transform "translate(500 330) scale(.42)")
(def spark-centre [553 383])
(def lock-root [600 505])
(def feet [520 790])

(def eye-at {:far [372 590] :near [480 596]})
(def cheeks {:far [352 634 18 15] :near [537 655 27 19]})

(defn- n [v] (.toFixed (js/Number v) 1))

(defn- pts
  "An SVG path from a template: every {i} is replaced by (nth vs i), rounded."
  [tpl vs]
  (str/replace tpl #"\{(\d+)\}" (fn [[_ i]] (n (nth vs (js/parseInt i))))))

(defn- eye-line
  "The line-drawn eyes (^ ^, closed, > <, spirals) as a path round the eye's centre; side :far or :near."
  [kind side [x y]]
  (let [s (if (= side :far) 1 -1)]
    (case kind
      :happy (pts "M{0} {1}Q{2} {3} {4} {1}" [(- x 16) (+ y 10) x (- y 16) (+ x 16)])
      :shut (pts "M{0} {1}Q{2} {3} {4} {1}" [(- x 16) (+ y 4) x (+ y 16) (+ x 16)])
      :squeeze (pts "M{0} {1}L{2} {3}L{0} {4}" [(- x (* s 14)) (- y 12) (+ x (* s 12)) y (+ y 12)])
      :spiral (pts "M{0} {1}a4 4 0 1 1 8 0a8 8 0 1 1 -16 0a12 12 0 1 1 24 0a16 16 0 1 1 -32 0" [(- x 4) y])
      :half (pts "M{0} {1}L{2} {1}" [(- x 19) (- y 3) (+ x 19)])
      :sad (pts "M{0} {1}L{2} {3}" [(- x 17) (- y (if (= side :far) 22 32)) (+ x 17) (- y (if (= side :far) 32 22))])
      nil)))

(defn- eye-shape
  "The filled shapes (stars, hearts) round the eye's centre."
  [kind [x y]]
  (case kind
    :star (pts "M{0} {1}L{2} {3}L{4} {5}L{2} {6}L{0} {7}L{8} {6}L{9} {5}L{8} {3}Z"
               [x (- y 26) (+ x 7) (- y 7) (+ x 24) y (+ y 7) (+ y 26) (- x 7) (- x 24)])
    :heart (pts "M{0} {1}C{2} {3} {4} {5} {0} {6}C{7} {5} {8} {3} {0} {1}Z"
                [x (+ y 18) (- x 26) (- y 2) (- x 16) (- y 26) (- y 10) (+ x 16) (+ x 26)])
    nil))

(defn eye
  "How an eye of kind looks: :dot [sx sy] scales the round eye (nil hides it), :line a stroked path,
  :fill a filled one (nil when none)."
  [kind side]
  (let [at (eye-at side)
        dot (case kind :dot [1 1] :wide [1.25 1.25] :narrow [1.05 0.6] :half [1 0.5] :sad [1 0.92] nil)]
    {:dot dot
     :line (eye-line kind side at)
     :fill (eye-shape kind at)}))

(def ^:private mouths
  {:smile ["M392 652Q426 682 460 654" false]
   :grin ["M390 650Q426 704 462 650Z" true]
   :small ["M408 660Q426 672 444 660" false]
   :o ["M416 664a10 12 0 1 0 20 0a10 12 0 1 0 -20 0Z" true]
   :big-o ["M410 670a16 24 0 1 0 32 0a16 24 0 1 0 -32 0Z" true]
   :whistle ["M421 664a5 6 0 1 0 10 0a5 6 0 1 0 -10 0Z" true]
   :flat ["M398 664L454 664" false]
   :wavy ["M388 666q10 -9 19 0t19 0t19 0t19 0" false]
   :frown ["M398 672Q426 650 454 672" false]
   :cat ["M398 658q7 12 14 0q7 12 14 0" false]})

(defn mouth
  "A mouth of kind as {:d path :filled? bool} (an unknown kind smiles)."
  [kind]
  (let [[d filled?] (get mouths kind (:smile mouths))]
    {:d d :filled? filled?}))

(def tongue "M418 666q8 22 17 0Z")
