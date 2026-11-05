(ns claudia.shared.narration
  "What a 3D story's narrator says, the same for the page (claudia.web.scene3d.story), the runner
  that makes a story's video and the hub that voices it: each step's line, and the story's lines
  with the voice they are said in."
  (:require [clojure.string :as str]))

(def max-chars 1200)

(defn say-of
  "A step's line: its say trimmed and cut to max-chars, \"\" when it has none."
  [step]
  (let [s (:say step)]
    (if (string? s)
      (let [t (str/trim s)] (subs t 0 (min max-chars (count t))))
      "")))

(defn voice-of
  "A story's voice description (its `voice`), or nil."
  [data]
  (when (string? (:voice data)) (:voice data)))

(defn lines
  "The lines a story's narrator says, once each, in order: [{:text :instruction}]. None when the
  story turns the narrator off (narrate: false)."
  [data]
  (if (or (false? (:narrate data)) (not (sequential? (:story data))))
    []
    (let [instruction (voice-of data)]
      (->> (:story data)
           (filter map?)
           (map say-of)
           (filter seq)
           distinct
           (mapv (fn [t] {:text t :instruction instruction}))))))

(defn seconds-by-line
  "The clips' lengths as the narrator looks them up: [{:text :instruction :seconds}] ->
  {[instruction text] seconds}."
  [clips]
  (into {} (map (juxt (juxt :instruction :text) :seconds)) clips))
