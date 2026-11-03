(ns claudia.web.scene3d.bar
  "What the scene3d view's bars and popup say and where they sit, without the page: the story
  track, the overlap button, the legend's swatches, the part popup's place, the stage's height and
  what Explain asks.")

(def height-min 220)

(defn clamp-height
  "The stage's height, kept between height-min and 1.5 windows (at least 600 px)."
  [h window-height]
  (js/Math.round (min (max h height-min) (max (* 1.5 window-height) 600))))

(defn stored-height "The stage's stored height (440 when none)." [stored window-height]
  (clamp-height (let [n (js/Number stored)] (if (pos? n) n 440)) window-height))

(defn explain-text
  "What Explain asks Claude about a clicked part."
  [{:keys [id title color shape parent note]} block]
  (str "Explain the \"" title (when (not= title id) (str " (id " id ")")) "\" in the 3D view"
       (when block (str " (block " block ")")) ": a " color " " shape
       (when parent (str ", part of " parent))
       (when note (str ", noted as \"" note "\""))
       ". What it is, what it does, how it works, and how it connects to the parts around it."))

(defn seg-fill
  "How full (percent) step i's segment is: done before the current step, its progress on it."
  [i idx prog]
  (cond (< i idx) 100 (= i idx) (* 100 prog) :else 0))

(defn tip-left
  "Where (percent of the track) the tip over step i sits: the middle of its segment."
  [secs i]
  (* 100 (/ (+ (reduce + (take i secs)) (/ (secs i) 2)) (max 1 (reduce + secs)))))

(defn check-class [now seen] (cond (seq now) "bad" (seq seen) "warn" :else "ok"))

(defn check-text
  "The overlap button's words (after its icon)."
  [now seen scanned]
  (if (seq now)
    (str " " (count now) " overlap" (when (> (count now) 1) "s"))
    (str " " (cond (seq seen) (str "clear now · " (count seen) " seen")
                   scanned "no overlaps in the run"
                   :else "no overlaps"))))

(defn swatch "A legend colour: a theme colour name as its CSS variable, else as given." [color]
  (if (re-matches #"[a-z]+" (str color)) (str "var(--" color ")") color))

(defn popup-at
  "Where the part popup sits in a view w wide and height tall, near the click."
  [{:keys [x y]} w height]
  {:left (min (+ x 12) (- w 250)) :top (max 4 (min (- y 10) (- height 250)))})
