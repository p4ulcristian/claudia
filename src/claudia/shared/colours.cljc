(ns claudia.shared.colours
  "The chat colours, one list for the runner and the page (README decision 26): a crayon box of 20
  picked so no two look alike, both as the crayon and as the whole window tinted with it (measured
  in OKLab). A new chat takes the colour most different from the ones the other chats wear. The
  hex values here are the same as claudia.css's --c-<name>.")

(def hexes
  "Each chat colour's crayon, in hue order (passive ash aside, which is only picked by hand)."
  (array-map "cherry" "#e5383b" "chocolate" "#a0673f" "peach" "#ffc49b" "tangerine" "#ff8c1a"
             "gold" "#d4a72c" "sunflower" "#ffd23f" "olive" "#9a9a3a" "kiwi" "#9bd63a"
             "forest" "#3f9e4d" "mint" "#7fe0b2" "jade" "#2fbf8a" "aqua" "#5ce1f0"
             "sky" "#6fc0ff" "ocean" "#2f7fd8" "sapphire" "#4a5cf0" "periwinkle" "#8f9cff"
             "grape" "#9b5de5" "plum" "#b0478f" "magenta" "#f15bb5" "bubblegum" "#ff9ecd"))

(def chat-colours "The colours a chat can have, in hue order." (vec (keys hexes)))

(def renamed
  "The colours before the crayon box -> the crayon each became (a runner moves its chats over)."
  {"burgundy" "plum" "red" "cherry" "orange" "tangerine" "amber" "gold" "yellow" "sunflower"
   "lime" "kiwi" "green" "jade" "teal" "mint" "cyan" "aqua" "blue" "ocean" "indigo" "sapphire"
   "violet" "periwinkle" "purple" "grape" "pink" "bubblegum" "brown" "chocolate"})

(defn chat-colour?
  "Whether c is a colour a chat may be given."
  [c]
  (contains? hexes c))

;; ---- how far apart two colours look ----

(defn- linear [c]
  (let [c (/ c 255)] (if (<= c 0.04045) (/ c 12.92) (Math/pow (/ (+ c 0.055) 1.055) 2.4))))

(defn- channels [hex]
  (mapv #(linear #?(:clj (Integer/parseInt (subs hex % (+ % 2)) 16) :cljs (js/parseInt (subs hex % (+ % 2)) 16))) [1 3 5]))

(defn oklab
  "A #rrggbb colour in OKLab: [L a b]."
  [hex]
  (let [[r g b] (channels hex)
        cbrt #(Math/pow % (/ 1 3))
        l (cbrt (+ (* 0.4122214708 r) (* 0.5363325363 g) (* 0.0514459929 b)))
        m (cbrt (+ (* 0.2119034982 r) (* 0.6806995451 g) (* 0.1073969566 b)))
        s (cbrt (+ (* 0.0883024619 r) (* 0.2817188376 g) (* 0.6299787005 b)))]
    [(+ (* 0.2104542553 l) (* 0.7936177850 m) (* -0.0040720468 s))
     (+ (* 1.9779984951 l) (* -2.4285922050 m) (* 0.4505937099 s))
     (+ (* 0.0259040371 l) (* 0.7827717662 m) (* -0.8086757660 s))]))

(defn apart
  "How different two chat colours look (OKLab distance)."
  [x y]
  (Math/sqrt (reduce + (map #(* (- %1 %2) (- %1 %2)) (oklab (hexes x)) (oklab (hexes y))))))

;; ---- a new chat's colour ----

(defn recolour
  "What a stored chat row needs for the crayon box: its colour or the one it had before ash
  renamed ({:color …} / {:prev_color …}), :fresh when it has none or an unknown one, nil when fine."
  [{:keys [color prev-color]}]
  (let [changes (cond-> {}
                  (renamed color) (assoc :color (renamed color))
                  (renamed prev-color) (assoc :prev_color (renamed prev-color)))]
    (cond (seq changes) changes
          (or (empty? color) (not (or (chat-colour? color) (= "ash" color)))) :fresh
          :else nil)))

(defn worn
  "How many of the other chats in the list (not archived, not except) wear each chat colour."
  [chats except]
  (frequencies (keep #(when (and (not (:archived %)) (not= except (:id %)) (chat-colour? (:color %))) (:color %)) chats)))

(defn most-different
  "A new chat's colour: among the colours worn least, the one furthest from every colour worn more
  (the first in hue order when nothing is worn more)."
  [chats except]
  (let [used (worn chats except)
        least (apply min (map #(get used % 0) chat-colours))
        others (filter #(> (get used % 0) least) chat-colours)
        score (fn [c] (if (seq others) (apply min (map #(apart c %) others)) 0))]
    (reduce (fn [best c] (if (> (score c) (score best)) c best))
            (filter #(= least (get used % 0)) chat-colours))))
