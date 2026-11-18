(ns claudia.web.face.lens
  "Claudia's sunglasses are her screen (2026-11-18): code rain while she works, a big symbol for a
  feeling (! ? ♥ z), or words typed out in green and scrolled across both lenses (which chat
  finished, hi!, achoo!). Pure: what to show from the pose, and SVG markup from what to show and
  the time t in ms."
  (:require [clojure.string :as str]
            [claudia.web.face.art :as art]))

(defn- r1 [v] (.toFixed (js/Number v) 1))

(def ^:private symbols
  {:bang "!" :question "?" :z "z" :sparkles "✦" :hearts "♥" :notes "♪" :orbit "@" :confetti "★"})

(def ^:private words {:cloud "OFFLINE" :poof "BYE" :swirl "ERROR"})

(defn- fx-show
  "What one effect [kind arg] puts on the lenses, nil when nothing."
  [[kind arg]]
  (cond (= kind :word) {:kind :text :text (str/upper-case (first arg))}
        (words kind) {:kind :text :text (words kind) :color (if (= kind :swirl) art/red art/green)}
        (symbols kind) {:kind :symbol :text (symbols kind) :color (if (= kind :hearts) art/red art/green)}
        (= kind :dots) {:kind :rain :speed (if arg 3 1)}
        (= kind :sweat) {:kind :rain :speed 3}))

(defn show
  "What the lenses show for a pose and the ticker (a chat's news, it wins): {:kind :rain|:text|
  :symbol|:dark, :text, :color, :speed, :start (when the words began, ms)}."
  [{:keys [fx]} ticker]
  (merge {:color art/green :start 0}
         (cond ticker {:kind :text :text (:text ticker) :color (:color ticker art/green) :start (:start ticker)}
               :else (or (some fx-show (sort-by #(if (= :word (first %)) 0 1) fx)) {:kind :dark}))))

(def ^:private glyphs (vec "01アイウエオカキクケコサシスセソ{}<>=/;:+*#"))

(defn- column
  "One column of rain at x inside a lens [cx cy rx ry], i its number: two glyphs, the head bright."
  [[_ cy _ ry] x i t speed]
  (let [h (* 2 ry)
        v (+ 0.6 (* 0.1 (mod (* i 7) 5)))
        y (+ (- cy ry) (mod (+ (* t 0.06 speed v) (* i 37)) (+ h 20)))
        g #(glyphs (mod (+ (quot t 110) (* i 11) %) (count glyphs)))]
    (str "<text x=\"" (r1 x) "\" y=\"" (r1 y) "\">" (g 0) "</text>"
         "<text x=\"" (r1 x) "\" y=\"" (r1 (- y 13)) "\" opacity=\".45\">" (g 3) "</text>")))

(defn rain
  "Code falling in both lenses at time t (speed 1 calm, 3 busy; 0 holds it still)."
  [ls t speed]
  (str/join (for [[cx _ rx :as l] ls
                  [i x] (map-indexed vector (range (- cx rx -4) (+ cx rx) 11))]
              (column l x i t speed))))

(defn- symbol-at [ls txt]
  (str/join (for [[cx cy] ls]
              (str "<text x=\"" cx "\" y=\"" (+ cy 14) "\" text-anchor=\"middle\" font-size=\"44\">" txt "</text>"))))

(defn ticker
  "Words typed out (14 letters a second) and scrolled from right to left across both lenses, t ms
  after they began."
  [ls txt t]
  (let [[[lx _ lrx] [rx ry-y rrx]] ls
        left (- lx lrx) width (- (+ rx rrx) left)
        typed (subs txt 0 (min (count txt) (quot (* 14 t) 1000)))
        travel (+ width (* 19 (count txt)))
        x (- (+ left width) (mod (* t 0.11) travel))]
    (str "<text x=\"" (r1 x) "\" y=\"" (+ ry-y 11) "\" font-size=\"32\">" typed "</text>")))

(defn markup
  "The lenses' content as SVG markup: lenses ls (art/lenses :up or :down), what to show, time now."
  [ls {:keys [kind text color speed start]} now calm?]
  (let [body (case kind
               :rain (rain ls now (if calm? 0 speed))
               :symbol (symbol-at ls text)
               :text (ticker ls text (- now start))
               "")]
    (str (str/join (for [[cx cy rx ry] ls]
                     (str "<ellipse cx=\"" cx "\" cy=\"" cy "\" rx=\"" rx "\" ry=\"" ry "\" fill=\"#020e06\" opacity=\".92\"/>")))
         "<g fill=\"" color "\" style=\"filter:drop-shadow(0 0 5px " color ")\" font-family=\"ui-monospace,monospace\" font-weight=\"700\" font-size=\"13\">"
         body "</g>")))
