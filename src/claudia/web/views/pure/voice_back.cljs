(ns claudia.web.views.pure.voice-back
  "Listen back (README decision 20): which kept sounds a sent message plays, and when they go.")

(def keep-ms
  "How long a dictated clip is kept on the device after it was spoken: 30 minutes."
  (* 30 60 1000))

(defn expired?
  "Whether sound s ({:at}) was spoken more than keep-ms before now."
  [s now]
  (> (- now (:at s)) keep-ms))

(defn by-msg
  "The kept sounds still in time that went out in a message, by its id, each message's in the
  order spoken: {msg [{:id :at :secs :text :raw}]} (no blobs)."
  [sounds now]
  (->> sounds
       (filter #(and (:msg %) (not (expired? % now))))
       (sort-by :at)
       (reduce (fn [m s] (update m (:msg s) (fnil conj []) (select-keys s [:id :at :secs :text :raw]))) {})))

(defn heard-lines
  "What each clip of a message was heard as: [{:secs :raw :text :cleaned?}], cleaned? when the
  cleanup changed the words the speech model heard."
  [clips]
  (vec (for [{:keys [secs raw text]} clips]
         {:secs secs :raw raw :text text :cleaned? (not= raw text)})))

(defn total-secs
  "A message's clips' length in whole seconds."
  [clips]
  (js/Math.round (reduce + 0 (keep :secs clips))))
