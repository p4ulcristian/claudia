(ns claudia.web.face.paint
  "Claudia's face from her state to SVG attributes (2026-11-05): part name -> {attribute value}
  (:html for the effects layer). The view writes only what changed. Looking around moves the layers
  apart, the far ones less: hair at the back, face, features, the front lock, the clip, so the head
  seems to turn."
  (:require [claudia.web.face.art :as art]
            [claudia.web.face.fx :as fx]))

(defn- r1 [v] (.toFixed (js/Number v) 2))

(defn transform
  "translate by x y, then turn r degrees and scale sx sy round the point c."
  ([x y] (transform x y 0 1 1 [0 0]))
  ([x y r sx sy [cx cy]]
   (str "translate(" (r1 (+ x cx)) " " (r1 (+ y cy)) ") rotate(" (r1 r) ") scale(" (r1 sx) " " (r1 sy)
        ") translate(" (- cx) " " (- cy) ")")))

(defn- layers
  "The parallax layers for a look [lx ly]."
  [[lx ly] sway]
  {:back {:transform (transform (* lx -6) (* ly -3))}
   :face {:transform (transform (* lx 10) (* ly 6))}
   :feat {:transform (transform (* lx 14) (* ly 12))}
   :lock {:transform (transform (* lx 13) (* ly 4) sway 1 1 art/lock-root)}
   :clip {:transform (transform (* lx 9) (* ly 4))}})

(defn- body
  "The whole head: moves, its lean and squash round the feet."
  [{:keys [dx dy rot sx sy]} tilt calm?]
  {:body {:transform (if calm?
                       (transform 0 0)
                       (transform (or dx 0) (or dy 0) (+ (or rot 0) tilt) (or sx 1) (or sy 1) art/feet))}})

(defn- show [on?] (if on? "inline" "none"))

(defn- eye-parts
  "One eye's three parts (round, line, filled) for kind, shut by blink (0 to 1)."
  [side kind blink]
  (let [{:keys [dot line fill]} (art/eye kind side)
        [sx sy] (or dot [1 1])
        p (name side)]
    {(keyword (str "eye-" p "-dot")) {:display (show dot)
                                      :transform (transform 0 0 0 sx (* sy (- 1 (* 0.9 blink))) (art/eye-at side))}
     (keyword (str "eye-" p "-line")) {:display (show line) :d (or line "M0 0")}
     (keyword (str "eye-" p "-fill")) {:display (show fill) :d (or fill "M0 0")}}))

(defn- features
  "Eyes, mouth, tongue and cheeks of a pose."
  [{:keys [eyes eye-far eye-near mouth tongue? blush blink]}]
  (let [{:keys [d filled?]} (art/mouth mouth)
        b (r1 (or blush 0.6))]
    (merge (eye-parts :far (or eye-far eyes :dot) blink)
           (eye-parts :near (or eye-near eyes :dot) blink)
           {:mouth {:d d :fill (if filled? art/ink "none")}
            :tongue {:display (show tongue?)}
            :blush-far {:opacity b}
            :blush-near {:opacity b}})))

(defn attrs
  "Every part's attributes for her state at now (calm?: no moving about, for reduced motion)."
  [{:keys [pose look tilt spin]} now calm?]
  (merge (layers look (or (:sway pose) 0))
         (body pose tilt calm?)
         (features pose)
         {:spin {:transform (transform 0 0 spin 1 1 art/spark-centre)}
          :fx {:html (fx/draw (:fx pose) now)}}))
