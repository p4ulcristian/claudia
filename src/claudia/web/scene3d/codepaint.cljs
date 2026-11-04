(ns claudia.web.scene3d.codepaint
  "A code slab's face painted on a canvas: a header with the file's name, line numbers, the code in
  Shiki's Dark+ colours (plain when the language is unknown), marked lines with an amber band. The
  rows, sizes and marks come from claudia.web.scene3d.code."
  (:require [claudia.web.scene3d.code :as code]))

(def ^:private bg "#1e1e1e")
(def ^:private head-bg "#2a2d2e")
(def ^:private dim "#6e7681")
(def ^:private plain "#d4d4d4")

(defn mono-font
  "The page's monospace font (the --mono variable), else monospace."
  [^js host]
  (let [v (.trim (.getPropertyValue (js/getComputedStyle host) "--mono"))]
    (if (seq v) v "monospace")))

(defn- char-width [^js ctx font]
  (set! (.-font ctx) (str "100px " font))
  (/ (.-width (.measureText ctx "M")) 100))

(defn- paint-head! [^js ctx w title font]
  (set! (.-fillStyle ctx) head-bg) (.fillRect ctx 0 0 w code/head)
  (doseq [[i c] (map-indexed vector ["#ff5f57" "#febc2e" "#28c840"])]
    (set! (.-fillStyle ctx) c) (.beginPath ctx) (.arc ctx (+ code/pad (* i 40)) (/ code/head 2) 11 0 7) (.fill ctx))
  (set! (.-font ctx) (str "32px " font)) (set! (.-fillStyle ctx) "#9da5b4")
  (.fillText ctx title (+ code/pad 140) (/ code/head 2)))

(defn- paint-tokens! [^js ctx ^js toks line x]
  (if (and toks (pos? (.-length toks)))
    (reduce (fn [x ^js t]
              (set! (.-fillStyle ctx) (or (.-color t) plain))
              (.fillText ctx (.-content t) x)
              (+ x (.-width (.measureText ctx (.-content t)))))
            x toks)
    (do (set! (.-fillStyle ctx) plain) (.fillText ctx line x))))

(defn- paint-row! [^js ctx {:keys [line-h char-w font-px tokens start marks width]} i row]
  (let [y (+ code/head (* code/pad 0.55) (* i line-h) (/ line-h 2))
        x0 (* code/pad 0.4)]
    (if-let [[n line] row]
      (do (when (marks n)
            (set! (.-fillStyle ctx) "rgba(255,190,0,0.16)") (.fillRect ctx 0 (- y (/ line-h 2)) width line-h)
            (set! (.-fillStyle ctx) "#ffbe00") (.fillRect ctx 0 (- y (/ line-h 2)) 9 line-h))
          (set! (.-fillStyle ctx) dim) (.fillText ctx (.padStart (str n) 4) x0 y)
          (paint-tokens! ctx (when tokens (aget tokens (- n start))) line
                         (+ x0 (* char-w font-px code/gutter-chars))))
      (do (set! (.-fillStyle ctx) dim) (.fillText ctx "     ⋮" x0 y)))))

(defn paint
  "A canvas with the rows painted. tokens: Shiki's lines for the whole text (or nil); start: the
  text's first line number; marks: a set of line numbers; title: the header."
  [^js host {:keys [rows tokens start marks title]}]
  (let [^js canvas (js/document.createElement "canvas")
        ^js ctx (.getContext canvas "2d")
        font (mono-font host)
        char-w (char-width ctx font)
        {:keys [line-h width height] font-px :font} (code/layout rows char-w)]
    (set! (.-width canvas) width) (set! (.-height canvas) height)
    (set! (.-textBaseline ctx) "middle")
    (set! (.-fillStyle ctx) bg) (.fillRect ctx 0 0 width height)
    (paint-head! ctx width title font)
    (set! (.-font ctx) (str font-px "px " font))
    (doseq [[i row] (map-indexed vector rows)]
      (paint-row! ctx {:line-h line-h :char-w char-w :font-px font-px :tokens tokens :start start
                       :marks marks :width width} i row))
    canvas))

(defn paint-text
  "A text part's canvas: its words in the page's sans font (bold for weight \"bold\"), in its colour,
  on nothing (the part is see-through around the letters)."
  [^js host colour {:keys [text weight]}]
  (let [^js canvas (js/document.createElement "canvas")
        ^js ctx (.getContext canvas "2d")
        v (.trim (.getPropertyValue (js/getComputedStyle host) "--sans"))
        font (str (if (= "bold" weight) "700 " "500 ") "128px " (if (seq v) v "sans-serif"))
        s (str text)]
    (set! (.-font ctx) font)
    (set! (.-width canvas) (max 8 (js/Math.ceil (+ (.-width (.measureText ctx s)) 48))))
    (set! (.-height canvas) 176)
    (set! (.-font ctx) font)
    (set! (.-textBaseline ctx) "middle")
    (set! (.-fillStyle ctx) colour)
    (.fillText ctx s 24 88)
    canvas))
