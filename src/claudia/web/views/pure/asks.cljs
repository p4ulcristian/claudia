(ns claudia.web.views.pure.asks
  "A question card's state as plain data: st maps a question's index to {:picked [labels] :other text
  :focus label}."
  (:require [clojure.string :as str]))

(defn answer-of
  "One question's answer: the picked labels (and the other text), joined."
  [{:keys [picked other]}]
  (str/join ", " (cond-> (vec picked) (not (str/blank? other)) (conj (str/trim other)))))

(defn ready?
  "Whether every question has an answer."
  [st qs]
  (every? #(not (str/blank? (answer-of (get st %)))) (range (count qs))))

(defn answers
  "The answers as sent: question text -> its answer."
  [st qs]
  (into {} (map-indexed (fn [i q] [(:question q) (answer-of (get st i))]) qs)))

(defn toggle
  "label picked or unpicked for question i: any number on a multi-select question, else one."
  [st qs i label]
  (let [cur (vec (get-in st [i :picked]))]
    (assoc-in st [i :picked]
              (if (:multiSelect (get qs i))
                (if (some #{label} cur) (vec (remove #{label} cur)) (conj cur label))
                (if (= [label] cur) [] [label])))))

(defn typed-other
  "The other text typed for question i; on a pick-one question it unpicks the options."
  [st i multi? v]
  (cond-> (assoc-in st [i :other] v)
    (and (not multi?) (not (str/blank? v))) (assoc-in [i :picked] [])))

(defn first-open
  "The first question without an answer yet, else the first."
  [st qs]
  (or (first (keep-indexed (fn [i _] (when-not (or (seq (get-in st [i :picked]))
                                                   (not (str/blank? (get-in st [i :other]))))
                                       i))
                           qs))
      0))

(defn preview
  "The preview of the option in focus (else the first picked) of question q."
  [q {:keys [picked focus]}]
  (:preview (some #(when (= (:label %) (or focus (first picked))) %) (:options q))))

(defn key-hint
  "The keys under the questions."
  [qs]
  (str "1–" (min 9 (apply max 1 (map #(count (:options %)) qs))) " to pick · Enter to answer · Esc to see the picture"))
