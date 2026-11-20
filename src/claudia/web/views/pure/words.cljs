(ns claudia.web.views.pure.words
  "Small words the views share: a capital first letter, a server's initial, the chat colours, a long
  path clipped to its end. Plain data only, so the tests run them on Node."
  (:require [clojure.string :as str]
            [claudia.shared.colours :as colours]))

(def solid-colours "The solid chat colours, the crayons (passive ash aside)." colours/solid-colours)

(def gradient-colours "The gradient chat colours (README decision 80)." colours/gradient-colours)

(def colours-with-ash
  "The solid chat colours and passive ash, last."
  (conj solid-colours "ash"))

(defn colour-var
  "The CSS variable of a chat colour or ash (\"var(--c-mint)\"; a gradient's is its one stand-in
  colour); nil for anything that is not one."
  [c]
  (when (or (colours/chat-colour? c) (= "ash" c)) (str "var(--c-" c ")")))

(defn colour-fill
  "What paints a chat colour's circles and swatches: a gradient's CSS gradient (\"var(--g-dusk)\"),
  else its colour-var."
  [c]
  (if (colours/gradient? c) (str "var(--g-" c ")") (colour-var c)))

(defn colour-style
  "The style a chat's element carries for its colour: --chat (one colour, for tints and text) and
  --chat-fill (what its circle is painted with), both set so a row never inherits another's."
  [c]
  {"--chat" (colour-var c) "--chat-fill" (colour-fill c)})

(defn root-look
  "What the page's root wears for the open chat's colour c (claudia.css): the attributes and the
  properties, each name to its value or nil (removed). A gradient adds data-gradient and its two
  crayons, --hue-a and --hue-b, which the window's background runs between."
  [c]
  (let [v (colour-var c)
        [a b] (when (colours/gradient? c) (colours/crayons c))]
    {:attrs {"data-chat" (when v c) "data-gradient" (when a "")}
     :props {"--hue" v "--hue-a" (colour-var a) "--hue-b" (colour-var b)}}))

(defn capital
  "The word with its first letter upper case: \"paul\" -> \"Paul\"."
  [s]
  (str (str/upper-case (subs s 0 1)) (subs s 1)))

(defn clip
  "Long paths keep their end, where the file and project names are."
  [p limit]
  (if (<= (count p) limit)
    p
    (loop [parts (vec (str/split p #"/")) out nil]
      (let [out (or out (peek parts))
            parts (if (= out (peek parts)) (pop parts) parts)]
        (if (and (seq parts) (<= (+ (count out) (count (peek parts)) 1) (- limit 2)))
          (recur (pop parts) (str (peek parts) "/" out))
          (str "…/" out))))))

(defn plural
  "n and the noun, an s after it unless n is 1: 1 file, 3 files."
  [n noun]
  (str n " " noun (when (not= 1 n) "s")))
