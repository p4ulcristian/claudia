(ns claudia.web.views.diff
  "A diff of two texts as a table: one column (old and new line numbers) or side by side, with the
  words that differ marked inside each changed line (jsdiff)."
  (:require ["diff" :as jsdiff]
            [clojure.string :as str]))

(defn- hunks
  "Hunks of numbered lines: {:header :lines [{:kind \" \"|\"-\"|\"+\" :text :old :new}]}."
  [before after whole?]
  (let [^js patch (jsdiff/structuredPatch "a" "b" (or before "") (or after "") "" ""
                                          #js {:context (if whole? js/Number.MAX_SAFE_INTEGER 3)})]
    (vec
     (for [^js h (.-hunks patch)]
       (loop [raws (seq (.-lines h)) o (.-oldStart h) n (.-newStart h) out []]
         (if-not raws
           {:header (str "@@ " (.-oldStart h) "," (.-oldLines h) " → " (.-newStart h) "," (.-newLines h)) :lines out}
           (let [raw (first raws)
                 kind (subs raw 0 1)
                 text (subs raw 1)]
             (case kind
               " " (recur (next raws) (inc o) (inc n) (conj out {:kind " " :text text :old o :new n}))
               "-" (recur (next raws) (inc o) n (conj out {:kind "-" :text text :old o}))
               "+" (recur (next raws) o (inc n) (conj out {:kind "+" :text text :new n}))
               (recur (next raws) o n out)))))))))

(defn- pairs
  "Each run of removed lines with the added run right after it."
  [lines]
  (loop [ls lines out []]
    (if-not (seq ls)
      out
      (if (= " " (:kind (first ls)))
        (recur (rest ls) (conj out {:kind " " :line (first ls)}))
        (let [[del r1] (split-with #(= "-" (:kind %)) ls)
              [add r2] (split-with #(= "+" (:kind %)) r1)]
          (recur r2 (conj out {:kind "change" :del (vec del) :add (vec add)})))))))

(defn- words [text other side]
  (if (nil? other)
    text
    (let [parts (if (= side "-") (jsdiff/diffWordsWithSpace text other) (jsdiff/diffWordsWithSpace other text))]
      (into [:<>]
            (keep-indexed
             (fn [i ^js p]
               (cond
                 (and (= side "-") (.-added p)) nil
                 (and (= side "+") (.-removed p)) nil
                 (if (= side "-") (.-removed p) (.-added p)) ^{:key i} [:mark (.-value p)]
                 :else ^{:key i} [:<> (.-value p)])))
            parts))))

(defn- unified [hs]
  [:table.diff.unified
   [:colgroup [:col.ln-col] [:col.ln-col] [:col]]
   [:tbody
    (for [[hi h] (map-indexed vector hs)]
      ^{:key hi}
      [:<>
       [:tr.hunk [:td {:col-span 3} (:header h)]]
       (for [[pi p] (map-indexed vector (pairs (:lines h)))]
         (if (= " " (:kind p))
           ^{:key pi} [:tr.ctx [:td.ln (:old (:line p))] [:td.ln (:new (:line p))] [:td.code (:text (:line p))]]
           ^{:key pi}
           [:<>
            (for [[k l] (map-indexed vector (:del p))]
              ^{:key (str "d" k)} [:tr.del [:td.ln (:old l)] [:td.ln] [:td.code [words (:text l) (:text (get (:add p) k)) "-"]]])
            (for [[k l] (map-indexed vector (:add p))]
              ^{:key (str "a" k)} [:tr.add [:td.ln] [:td.ln (:new l)] [:td.code [words (:text l) (:text (get (:del p) k)) "+"]]])]))])]])

(defn- split [hs]
  [:table.diff.split
   [:colgroup [:col.ln-col] [:col] [:col.ln-col] [:col]]
   [:tbody
    (for [[hi h] (map-indexed vector hs)]
      ^{:key hi}
      [:<>
       [:tr.hunk [:td {:col-span 4} (:header h)]]
       (for [[pi p] (map-indexed vector (pairs (:lines h)))]
         (if (= " " (:kind p))
           (let [l (:line p)]
             ^{:key pi} [:tr.ctx [:td.ln (:old l)] [:td.code (:text l)] [:td.ln (:new l)] [:td.code (:text l)]])
           ^{:key pi}
           [:<>
            (for [k (range (max (count (:del p)) (count (:add p))))
                  :let [d (get (:del p) k) a (get (:add p) k)]]
              ^{:key k}
              [:tr
               [:td.ln {:class (if d "del" "empty")} (:old d)]
               [:td.code {:class (if d "del" "empty")} (when d [words (:text d) (:text a) "-"])]
               [:td.ln {:class (if a "add" "empty")} (:new a)]
               [:td.code {:class (if a "add" "empty")} (when a [words (:text a) (:text d) "+"])]])]))])]])

(defn diff-table
  "before/after: the texts; split?: side by side; whole?: every line, not only the changes."
  [before after split? whole?]
  (let [hs (hunks before after whole?)]
    (cond
      (empty? hs) [:p.muted.small "No difference in the text."]
      split? [split hs]
      :else [unified hs])))

(defn line-total [text] (if (str/blank? text) 0 (count (str/split-lines text))))

(defn patch-view
  "Git's patch text, its lines coloured: files, hunks, added and removed."
  [text]
  [:pre.patch
   (for [[i line] (map-indexed vector (str/split-lines (str text)))]
     ^{:key i}
     [:span {:class (cond (re-find #"^(diff --git|index |--- |\+\+\+ )" line) "p-file"
                          (str/starts-with? line "@@") "p-hunk"
                          (str/starts-with? line "+") "p-add"
                          (str/starts-with? line "-") "p-del")}
      line "\n"])])
