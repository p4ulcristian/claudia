(ns claudia.web.views.code
  "Code shown in the picture: line numbers, Dark+ colours, highlighted lines, notes pinned under
  lines, long code folded around what matters, copy. Used by the files form (a file opened) and the
  git form (a diff)."
  (:require [clojure.string :as str]
            [reagent.hooks :as h]
            [claudia.web.shiki :as shiki]))

(def ^:private fold-over 60) ; longer code folds
(def ^:private around 4)     ; lines kept open around each marked or noted line
(def ^:private head-n 40)    ; lines shown when nothing is marked

(defn color-var [c] (when (seq c) (str "var(--" c ")")))
(defn items [v] (if (sequential? v) v []))

(def ^:private mark-colours
  {"red" "var(--red)" "green" "var(--green)" "amber" "var(--amber)" "blue" "var(--blue)" "violet" "var(--violet)" "grey" "var(--muted)"})

(defn mark-colour
  "A mark's chalk colour by its :color name; amber when it names none the board has."
  [m]
  (get mark-colours (:color m) "var(--amber)"))

(defn project-name
  "A project's name for a form's head: the last folder of its root."
  [root]
  (or (last (filter seq (str/split root #"/"))) "project"))

(defn- code-rows
  "[{:n :sign :text}]; diff: lines starting + or - are added/removed (no number for removed)."
  [{:keys [code line diff]}]
  (let [raw (str/split (str/replace (str code) #"\n+$" "") #"\n" -1)]
    (loop [ls raw n (if (number? line) line 1) out []]
      (if-let [l (first ls)]
        (let [sign (if (and diff (#{"+" "-"} (subs l 0 (min 1 (count l))))) (subs l 0 1) "")
              text (if (and diff (or (seq sign) (str/starts-with? l " "))) (subs l 1) l)]
          (recur (rest ls) (if (= sign "-") n (inc n)) (conj out {:n (when-not (= sign "-") n) :sign sign :text text})))
        out))))

(defn marked-lines
  "[3 \"5-7\"] -> #{3 5 6 7}."
  [mark]
  (set (mapcat (fn [m]
                 (let [[a b] (map #(js/parseInt % 10) (str/split (str m) #"-"))]
                   (when-not (js/isNaN a) (range a (inc (if (or (nil? b) (js/isNaN b)) a b))))))
               (items mark))))

(defn- shown-rows [rows focus]
  (if (<= (count rows) fold-over)
    (vec (repeat (count rows) true))
    (let [idx (keep-indexed #(when (and (:n %2) (focus (:n %2))) %1) rows)]
      (if (empty? idx)
        (vec (map #(< % head-n) (range (count rows))))
        (let [on (set (mapcat #(range (max 0 (- % around)) (inc (min (dec (count rows)) (+ % around)))) idx))]
          (mapv on (range (count rows))))))))

(defn token-spans
  "A line's Shiki tokens as coloured spans."
  [^js toks]
  (for [[k ^js t] (map-indexed vector (array-seq toks))]
    ^{:key k} [:span {:style (merge {:color (.-color t)} (shiki/font-style (.-fontStyle t)))} (.-content t)]))

(defn ext-of
  "A file name's extension (what picks its language), or nil."
  [file]
  (let [file (str file)]
    (when (str/includes? file ".") (last (str/split file #"\.")))))

(defn- hidden-run-end [shown i]
  (loop [j i] (if (and (< j (count shown)) (not (nth shown j))) (recur (inc j)) j)))

(defn row-plan
  "What the code draws, in order: [:line i] for row i, [:fold i j] for a fold over rows i..j-1.
  opened: the folds (by first row) opened, whose rows are drawn instead."
  [shown opened]
  (loop [i 0 open-until 0 out []]
    (cond
      (>= i (count shown)) out
      (and (not (nth shown i)) (>= i open-until))
      (let [j (hidden-run-end shown i)]
        (if (opened i) (recur i j out) (recur j open-until (conj out [:fold i j]))))
      :else (recur (inc i) open-until (conj out [:line i])))))

(defn line-span
  "\":12\" or \":12–30\": the first and last line numbers shown in the head; nil without numbers."
  [first-no last-no]
  (when first-no (str ":" first-no (when (and last-no (not= last-no first-no)) (str "–" last-no)))))

(defn- fold-button [i j set-opened]
  ^{:key (str "fold" i)}
  [:button.f-fold {:type "button" :on-click (fn [] (set-opened #(conj % i)))}
   (str "⋯ " (- j i) " line" (when (not= 1 (- j i)) "s"))])

(defn- row-lines
  "Row i: its line, then the notes pinned under it."
  [r i ^js toks marked notes]
  (cons ^{:key i}
        [:div.f-ln {:class [(case (:sign r) "+" "add" "-" "del" nil)
                            (when (and (:n r) (marked (:n r))) "hot")]}
         [:span.num (or (:n r) "")]
         [:span.sign (if (= "-" (:sign r)) "−" (:sign r))]
         (if (and toks (pos? (.-length toks)))
           (into [:code] (token-spans toks))
           [:code (if (seq (:text r)) (:text r) " ")])]
        (for [nt (when (:n r) (get notes (:n r)))]
          ^{:key (str "n" (:id nt))}
          [:div.f-pin {:data-item (:id nt) :style {"--pin" (color-var (:color nt))}}
           [:span.chalk-box (:text nt)]])))

(defn- code-head [file lang rows folded opened set-opened copied copy]
  (let [cut (inc (or (str/last-index-of file "/") -1))]
    [:div.f-code-head
     (if (seq file)
       [:span.f-file {:title file}
        [:span.dir (subs file 0 cut)] [:b (subs file cut)]
        (when-let [at (line-span (some :n rows) (some :n (rseq rows)))] [:span.at at])]
       [:span.f-file])
     (when lang [:span.f-lang lang])
     (when folded
       [:button.f-copy {:type "button" :on-click #(set-opened (if (seq opened) #{} (set (range (count rows)))))}
        (if (seq opened) "fold" "unfold")])
     [:button.f-copy {:type "button" :on-click copy} (if copied "copied" "copy")]]))

(defn copy-text
  "What copy puts on the clipboard: the code without the removed lines."
  [rows]
  (str/join "\n" (map :text (remove #(= "-" (:sign %)) rows))))

(defn code-view
  "data: {:file :code :lang :line (first number) :diff :mark :notes :whole :error}."
  [data]
  (let [rows (h/use-memo #(code-rows data) [(:code data) (:line data) (:diff data)])
        file (if (string? (:file data)) (:file data) "")
        lang (or (:lang data) (ext-of file))
        text (h/use-memo #(str/join "\n" (map :text rows)) [rows])
        tokens (shiki/use-tokens text lang)
        marked (marked-lines (:mark data))
        notes (group-by #(js/Number (:line %)) (items (:notes data)))
        shown (if (:whole data) (vec (repeat (count rows) true)) (shown-rows rows (into marked (keys notes))))
        [opened set-opened] (h/use-state #{})
        [copied set-copied] (h/use-state false)
        copy (fn []
               (-> (js/navigator.clipboard.writeText (copy-text rows))
                   (.then (fn [] (set-copied true) (js/setTimeout #(set-copied false) 1200)))))]
    [:div.f-code {:class (when (:diff data) "is-diff")}
     (code-head file lang rows (some false? shown) opened set-opened copied copy)
     (when (:error data) [:p.f-note.f-code-err (:error data)])
     (into [:div.f-lines {:class (when (shiki/clojure? lang) "nowrap")}]
           (mapcat (fn [[kind i j]]
                     (if (= kind :fold)
                       [(fold-button i j set-opened)]
                       (row-lines (nth rows i) i (shiki/line tokens i) marked notes)))
                   (row-plan shown opened)))]))
