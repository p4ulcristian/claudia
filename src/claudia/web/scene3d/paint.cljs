(ns claudia.web.scene3d.paint
  "Page elements painted onto a canvas, for the recording: the labels, the legend and the subtitles
  as the page shows them (their boxes: background, border, round corners; their words: font,
  colour, place). Coordinates are CSS pixels from the stage's corner; the caller's transform maps
  them into the video. Shadows and filters are left out."
  (:require [claudia.web.scene3d.video :as video]))

(defn- px [v] (or (js/parseFloat v) 0))

(defn see-through? "Whether a computed colour shows nothing." [c]
  (boolean (or (= c "transparent") (re-find #"^rgba\(.*,\s*0\)$" (str c)))))

(defn- box-path! [^js ctx x y w h ^js cs]
  (.beginPath ctx)
  (.roundRect ctx x y w h (px (.-borderTopLeftRadius cs))))

(defn- left-bar!
  "A left border wider or other than the rest (a label's colour stripe), inside the round box."
  [^js ctx x y h ^js cs]
  (let [lw (px (.-borderLeftWidth cs))]
    (when (and (pos? lw) (or (not= lw (px (.-borderTopWidth cs)))
                             (not= (.-borderLeftColor cs) (.-borderTopColor cs))))
      (.save ctx)
      (.clip ctx)
      (set! (.-fillStyle ctx) (.-borderLeftColor cs))
      (.fillRect ctx x y lw h)
      (.restore ctx))))

(defn- box!
  "An element's box at rect r (from origin o): its background, its border, its left stripe."
  [^js ctx ^js cs ^js r o]
  (let [x (- (.-left r) (:left o)) y (- (.-top r) (:top o)) w (.-width r) h (.-height r)
        bw (px (.-borderTopWidth cs))]
    (box-path! ctx x y w h cs)
    (when-not (see-through? (.-backgroundColor cs))
      (set! (.-fillStyle ctx) (.-backgroundColor cs))
      (.fill ctx))
    (when (pos? bw)
      (set! (.-lineWidth ctx) bw)
      (set! (.-strokeStyle ctx) (.-borderTopColor cs))
      (box-path! ctx (+ x (/ bw 2)) (+ y (/ bw 2)) (- w bw) (- h bw) cs)
      (.stroke ctx)
      (box-path! ctx x y w h cs)
      (left-bar! ctx x y h cs))))

(defn- font-of [^js cs]
  (str (.-fontStyle cs) " " (.-fontWeight cs) " " (.-fontSize cs) " " (.-fontFamily cs)))

(defn- word!
  "One word of text node n, from start to end, where the page put it."
  [^js ctx ^js n [start end] o]
  (let [rg (js/document.createRange)]
    (.setStart rg n start)
    (.setEnd rg n end)
    (doseq [^js r (array-seq (.getClientRects rg))]
      (let [w (subs (.-data n) start end)
            ^js mt (.measureText ctx w)]
        (.fillText ctx w (- (.-left r) (:left o))
                   (video/baseline (- (.-top r) (:top o)) (.-height r)
                                 (.-fontBoundingBoxAscent mt) (.-fontBoundingBoxDescent mt)))))))

(defn- words! [^js ctx ^js n ^js cs o]
  (set! (.-font ctx) (font-of cs))
  (set! (.-fillStyle ctx) (.-color cs))
  (set! (.-textBaseline ctx) "alphabetic")
  (doseq [span (video/word-spans (.-data n))] (word! ctx n span o)))

(defn- shown? [^js cs]
  (and (not= "none" (.-display cs)) (not= "hidden" (.-visibility cs)) (pos? (px (.-opacity cs)))))

(defn el!
  "Paints element el and everything in it, at alpha (its parents' opacity), from origin o
  ({:left :top}, the stage's corner on the page)."
  [^js ctx ^js el o alpha]
  (let [cs (js/getComputedStyle el)]
    (when (shown? cs)
      (let [a (* alpha (px (.-opacity cs)))]
        (set! (.-globalAlpha ctx) a)
        (box! ctx cs (.getBoundingClientRect el) o)
        (doseq [^js c (array-seq (.-childNodes el))]
          (cond (= 1 (.-nodeType c)) (el! ctx c o a)
                (and (= 3 (.-nodeType c)) (seq (.trim (.-data c)))) (do (set! (.-globalAlpha ctx) a)
                                                                        (words! ctx c cs o))))))))
