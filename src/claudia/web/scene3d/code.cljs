(ns claudia.web.scene3d.code
  "A code slab's text as the engine paints it, without three.js: which lines it shows (ranges, with
  a gap row between two), which are marked, its language from the file name, and the canvas size
  that fits the longest line. The painting itself is claudia.web.scene3d.codepaint."
  (:require [clojure.string :as str]))

(def canvas-width 2048)
(def pad 46)
(def head 92)
(def gutter-chars 6)

(defn- span
  "A line number or \"a-b\" as [a b] (a <= b), nil when it is neither."
  [x]
  (let [[a b] (cond (number? x) [x x]
                    (string? x) (let [[_ a b] (re-matches #"\s*(\d+)\s*(?:-\s*(\d+))?\s*" x)]
                                  (when a [(js/parseInt a 10) (js/parseInt (or b a) 10)]))
                    :else nil)]
    (when (and a b (pos? a)) [(min a b) (max a b)])))

(defn spans
  "The data's ranges: a number, \"a-b\", or a list of them, as sorted [[a b]]; [] for none."
  [x]
  (vec (sort (keep span (if (sequential? x) x [x])))))

(defn marked
  "The set of line numbers mark names (numbers and \"a-b\" ranges)."
  [x]
  (set (mapcat (fn [[a b]] (range a (inc b))) (spans x))))

(defn rows
  "The rows a slab shows of the text's lines (numbered from start): [n line] in the ranges, nil for
  a gap between two ranges; every line when there are no ranges. At most 400 rows."
  [lines start ranges]
  (let [numbered (map-indexed (fn [i l] [(+ start i) l]) lines)
        last-n (+ start (count lines) -1)
        in (fn [[a b]] (filterv (fn [[n]] (<= a n b)) numbered))]
    (vec (take 400 (if (empty? ranges)
                     numbered
                     (->> ranges
                          (filter (fn [[a]] (<= a last-n)))
                          (map in)
                          (remove empty?)
                          (interpose [nil])
                          (apply concat)))))))

(defn lines-of "The text's lines, tabs as two spaces." [text]
  (str/split-lines (str/replace (str text) "\t" "  ")))

(defn lang-of
  "The language: the data's lang, else the file's extension (Shiki knows clj, sql, ts…), else nil."
  [lang file]
  (or (not-empty (str lang))
      (some->> (str file) (re-find #"\.([A-Za-z0-9]+)$") second str/lower-case)))

(defn layout
  "The canvas for rows, given the font's character width per pixel of font size (char-w): the font
  size that fits the longest row (at most 40 px), the line height and the canvas height."
  [rs char-w]
  (let [longest (+ gutter-chars 1 (reduce max 1 (map (fn [[_ l]] (count l)) (filter some? rs))))
        font (min 40 (/ (- canvas-width (* pad 1.4)) (* longest char-w)))
        line-h (* font 1.6)]
    {:font font :line-h line-h :width canvas-width
     :height (js/Math.ceil (+ head (* pad 1.1) (* (max 1 (count rs)) line-h)))}))

(defn title-of "The slab's header: its title, else its file, else nothing." [p]
  (str (or (:title p) (:file p) "")))
