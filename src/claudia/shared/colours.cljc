(ns claudia.shared.colours
  "The chat colours, one list for the runner and the page: a crayon box of 20 solid colours picked so
  no two look alike, both as the crayon and as the whole window tinted with it (measured in OKLab;
  README decision 26), and 8 gradients, each between two of the crayons (README decision 80). A new
  chat takes the solid colour most different from the ones the other chats wear, never one the chat
  started last wears. The hex values here are the same as claudia.css's --c-<name>.")

(def hexes
  "Each solid chat colour's crayon, in hue order (passive ash aside, which is only picked by hand)."
  (array-map "cherry" "#e5383b" "chocolate" "#a0673f" "peach" "#ffc49b" "tangerine" "#ff8c1a"
             "gold" "#d4a72c" "sunflower" "#ffd23f" "olive" "#9a9a3a" "kiwi" "#9bd63a"
             "forest" "#3f9e4d" "mint" "#7fe0b2" "jade" "#2fbf8a" "aqua" "#5ce1f0"
             "sky" "#6fc0ff" "ocean" "#2f7fd8" "sapphire" "#4a5cf0" "periwinkle" "#8f9cff"
             "grape" "#9b5de5" "plum" "#b0478f" "magenta" "#f15bb5" "bubblegum" "#ff9ecd"))

(def gradients
  "Each gradient chat colour, in hue order: the crayon it starts from (top left), the crayon it runs
  to (bottom right), and its stand-in hex, halfway between the two in OKLCH (the shorter way round
  the hue circle), which tints the window's theme and paints whatever takes one colour."
  (array-map "berry" {:from "plum" :to "cherry" :hex "#ce3d70"}
             "ember" {:from "cherry" :to "sunflower" :hex "#ff8800"}
             "sunset" {:from "tangerine" :to "magenta" :hex "#ff6a6e"}
             "citrus" {:from "sunflower" :to "kiwi" :hex "#d4d425"}
             "meadow" {:from "kiwi" :to "jade" :hex "#62cd6d"}
             "lagoon" {:from "aqua" :to "ocean" :hex "#20b3e8"}
             "dusk" {:from "sapphire" :to "magenta" :hex "#ad57e5"}
             "candy" {:from "bubblegum" :to "periwinkle" :hex "#d299f1"}))

(def solid-colours "The solid colours, in hue order: the crayons." (vec (keys hexes)))

(def gradient-colours "The gradient colours, in hue order." (vec (keys gradients)))

(def chat-colours "The colours a chat can have: the solids, then the gradients." (into solid-colours gradient-colours))

(def renamed
  "The colours before the crayon box -> the crayon each became (a runner moves its chats over)."
  {"burgundy" "plum" "red" "cherry" "orange" "tangerine" "amber" "gold" "yellow" "sunflower"
   "lime" "kiwi" "green" "jade" "teal" "mint" "cyan" "aqua" "blue" "ocean" "indigo" "sapphire"
   "violet" "periwinkle" "purple" "grape" "pink" "bubblegum" "brown" "chocolate"})

(defn gradient?
  "Whether c is one of the gradient chat colours."
  [c]
  (contains? gradients c))

(defn chat-colour?
  "Whether c is a colour a chat may be given: a solid one or a gradient."
  [c]
  (or (contains? hexes c) (gradient? c)))

(defn crayons
  "The crayons a chat colour is made of: a solid colour itself, a gradient its two; () for anything
  else (ash, an unknown name, nil)."
  [c]
  (cond (contains? hexes c) (list c)
        (gradient? c) (list (:from (gradients c)) (:to (gradients c)))
        :else ()))

(defn hex-of
  "A chat colour's one hex: a solid's crayon, a gradient's stand-in; nil for anything else."
  [c]
  (or (hexes c) (:hex (gradients c))))

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
  "How different two chat colours look (OKLab distance between their hexes)."
  [x y]
  (Math/sqrt (reduce + (map #(* (- %1 %2) (- %1 %2)) (oklab (hex-of x)) (oklab (hex-of y))))))

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

(def recent-count
  "How many of the most recently started chats count as worn, archived or not, so new chats walk
  through the box instead of going back to a colour just used."
  5)

(defn- started [chat]
  (or (:started chat) (:created chat) 0))

(defn recent
  "The chats (not except) started most recently, newest first: recent-count of them."
  [chats except]
  (take recent-count (sort-by (comp - started) (remove #(= except (:id %)) chats))))

(defn last-crayons
  "The crayons the chat started last (not except) wears, archived or not: one for a solid colour,
  two for a gradient, none for ash or no chat colour."
  [chats except]
  (set (crayons (:color (first (recent chats except))))))

(defn worn
  "How many chats wear each crayon: those in the list (not archived) and the recently started ones
  (archived too), each chat once, except never counted; a gradient chat wears both its crayons."
  [chats except]
  (let [shown (remove :archived chats)
        counted (vals (into {} (map (juxt :id identity)) (concat shown (recent chats except))))]
    (frequencies (mapcat #(when (not= except (:id %)) (crayons (:color %))) counted))))

(defn most-different
  "A new chat's colour, always a solid one: never a crayon the chat started last wears; among the
  others worn least, the one furthest from every crayon worn more and from the last chat's (the
  first in hue order when nothing is worn)."
  [chats except]
  (let [used (worn chats except)
        last-cs (last-crayons chats except)
        open (remove last-cs solid-colours)
        least (apply min (map #(get used % 0) open))
        far-from (concat (filter #(> (get used % 0) least) solid-colours) last-cs)
        score (fn [c] (if (seq far-from) (apply min (map #(apart c %) far-from)) 0))]
    (reduce (fn [best c] (if (> (score c) (score best)) c best))
            (filter #(= least (get used % 0)) open))))
