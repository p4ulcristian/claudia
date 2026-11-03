(ns claudia.web.scene3d.data
  "The scene3d form's data as the engine reads it, without three.js: numbers and lists made safe,
  what a part needs (outline, edges, label), the part popup's rows, which overlaps are allowed, and
  the story's plan for a step (which parts fade, which glow) and its clock."
  (:require [claudia.web.scene3d.maths :as m]
            [claudia.web.scene3d.story :as story]))

(defn lst [v] (if (sequential? v) v []))

(defn vec3
  "[x y z] from the data (missing or bad numbers are 0), or the default when it is not a point."
  ([v] (vec3 v [0 0 0]))
  ([v d] (if (and (sequential? v) (>= (count v) 3))
           (mapv #(let [n (js/Number %)] (if (js/isNaN n) 0 n)) (take 3 v))
           d)))

(defn size-of [s] (if (number? s) [s s s] (vec3 s [1 1 1])))
(defn id-str [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn person-scale
  "A person's size is their height; the model is `model-height` tall."
  [size model-height]
  (let [h (cond (number? size) size (sequential? size) (js/Number (second size)) :else js/NaN)]
    (if (and (js/isFinite h) (pos? h)) (/ h model-height) 1)))

(defn radius-of "A rod's or tube's radius (0.08 unless the data gives a positive one)." [p]
  (or (let [r (js/Number (:radius p))] (when (pos? r) r)) 0.08))

(defn joint-ref
  "\"anna.handR\" -> [\"anna\" \"handR\"]; nil when the text names no joint."
  [s]
  (let [dot (.indexOf s ".")]
    (when (pos? dot) [(subs s 0 dot) (subs s (inc dot))])))

;; ---- what a part gets ----

(defn outline? "The chalk outline (the diagram style), not on groups, people or a ghost ball." [p style]
  (and (= style "diagram") (not (#{"group" "person"} (:shape p))) (not= false (:outline p))
       (not (and (:ghost p) (= "sphere" (:shape p))))))

(defn edges?
  "Dark edge lines (the realistic style) on a plain solid part without a preset material."
  [p style has-material?]
  (and (= style "real") (not has-material?) (not (:wire p))
       (not (#{"plane" "ring" "rod" "tube" "group" "person"} (:shape p)))))

(defn legend-of [d] (filterv #(and (map? %) (string? (:label %))) (lst (:legend d))))

(defn script-of "The script's source, or nil when there is none." [d]
  (when (and (string? (:script d)) (seq (.trim (:script d)))) (:script d)))

(defn scan-seconds-of [d fallback]
  (if (pos? (js/Number (:scan d))) (js/Number (:scan d)) fallback))

(defn pair-key [[a b]] (str a " × " b))
(defn hits-key [hits] (apply str (interpose "|" (map pair-key hits))))
(defn pose-key [spec] (pr-str spec))

;; ---- the part popup ----

(defn- fixed [n] (.toFixed n 2))

(defn part-rows
  "The facts shown for a clicked part. at: its world position [x y z]; rod-length: for a rod;
  bad?: whether it overlaps now."
  [id spec at rod-length solid? bad?]
  (cond-> [["id" id] ["shape" (str (or (:shape spec) "box"))] ["color" (str (or (:color spec) "blue"))]
           ["size" (if (= "rod" (:shape spec))
                     (str "length " (fixed rod-length))
                     (js/JSON.stringify (clj->js (or (:size spec) "—"))))]
           ["at" (apply str (interpose ", " (map fixed at)))]]
    (:parent spec) (conj ["part of" (str (:parent spec))])
    true (conj ["solid" (if solid? (if bad? "yes, overlapping now" "yes") "no (ghost/housing)")])))

(defn picked
  "What the popup shows for a clicked part, at x y within the view."
  [id spec rows x y]
  {:id id :title (str (or (:label spec) id)) :x x :y y
   :rows rows :note (:note spec) :shape (str (or (:shape spec) "box"))
   :color (str (or (:color spec) "blue")) :parent (:parent spec)})

;; ---- overlaps ----

(defn allow-pairs [allow] (filter #(and (sequential? %) (= 2 (count %))) allow))

(defn allowed?
  "Whether parts a and b may overlap: some [p q] pair of patterns names them, either way round."
  [pairs a b]
  (boolean (some (fn [[p q]] (or (and (story/matches? p a) (story/matches? q b))
                                 (and (story/matches? p b) (story/matches? q a))))
                 pairs)))

(defn skipped-pairs "The index pairs [i j] (i < j) of ids that are allowed to overlap." [ids pairs]
  (set (for [i (range (count ids)) j (range (inc i) (count ids))
             :when (allowed? pairs (ids i) (ids j))]
         [i j])))

(defn clearance-scale
  "How much a part is shrunk on an axis of half-size h so parts that touch by `clearance` pass."
  [h clearance]
  (if (> h 1e-6) (max 0.5 (- 1 (/ clearance h))) 1))

;; ---- the story ----

(defn reveal-plan
  "Which parts are on after a step: ids that just change fade (animate?) or jump. k-of: how far each
  id has appeared now; fades: the running fades. -> {:k-of :fades :jump [[id k]]}."
  [ids on k-of fades animate? wall]
  (reduce (fn [{:keys [k-of fades jump order] :as plan} id]
            (let [to (if (on id) 1 0)
                  now (get k-of id 1)
                  queued? (some #(= id (:id %)) fades)
                  plan (cond-> plan queued? (assoc :fades (filterv #(not= id (:id %)) fades)))]
              (cond
                (and (= to now) (not queued?)) (assoc plan :k-of (assoc k-of id to))
                animate? (-> plan
                             (update :fades conj {:id id :from now :to to
                                                  :at (+ wall (min (* 35 order) 700))
                                                  :dur (if (pos? to) 700 400)})
                             (update :order inc))
                :else (assoc plan :jump (conj jump [id to]) :k-of (assoc k-of id to)))))
          {:k-of k-of :fades fades :jump [] :order 0}
          ids))

(defn fade-value "A fade's value at wall time ms (eased out)." [{:keys [from to at dur]} wall]
  (+ from (* (- to from) (m/ease-out-cubic (m/progress wall at dur)))))

(defn running-fades [fades wall] (filterv #(< (- wall (:at %)) (:dur %)) fades))

(defn spot
  "One part under the spotlight: lit (in focus while there is one), notes (its label's note shows:
  always without a story, else only lit), dim (what its fade goes to)."
  [on? focus id steps-empty?]
  (let [lit (boolean (and on? (focus id)))]
    {:lit lit :notes (or steps-empty? lit) :dim (if (and on? (not lit)) 0.16 1)}))

(defn paint
  "A part's glow: red when it overlaps, blue when clicked, an amber pulse when in focus (the
  realistic style). -> {:hex :strength :person-strength}."
  [bad? chosen? lit? pulse]
  (let [hex (cond bad? 0xff2a2a chosen? 0x2f6dff lit? 0xffc247 :else 0x000000)
        strength (cond bad? 0.9 lit? (+ 0.2 (* 0.6 pulse)) :else 0.7)]
    {:hex hex :strength strength :person-strength (if (= hex 0x2f6dff) 0.35 strength)}))

(defn tick
  "The story's clock after a tick at `now` (ms). clock: {:playing :dur :elapsed :last :idx :n}.
  -> the clock with :prog (when the step has a length) and :then (:next, :end or nil)."
  [{:keys [playing dur elapsed idx n] :as clock} now]
  (let [elapsed (if (and playing (pos? dur)) (+ elapsed (/ (- now (:last clock)) 1000)) elapsed)
        done? (and playing (pos? dur) (>= elapsed dur))]
    (assoc clock :elapsed elapsed :last now
           :prog (when (pos? dur) (min 1 (/ elapsed dur)))
           :then (when done? (if (< (inc idx) n) :next :end)))))

(defn restart?
  "Play starts over: before the story began, or on its last step once that has run out."
  [idx n dur elapsed]
  (or (neg? idx) (and (>= idx (dec n)) (pos? dur) (>= elapsed dur))))

(defn voice-lines [steps] (vec (distinct (filter seq (map :say steps)))))
