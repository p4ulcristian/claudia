(ns claudia.web.scene3d.story
  "A scene3d's story: steps that say a line, show or hide parts, aim the camera, light parts up and
  pose people. Pure data, no three.js: which parts are on at which step, how long a step lasts.")

(defn- strings [v]
  (cond (string? v) [v]
        (sequential? v) (filterv string? v)
        :else []))

(defn- num [v] (let [n (js/Number v)] (when (and (some? v) (js/isFinite n)) n)))

(defn normalize
  "The story as the page uses it: every step with every field."
  [v]
  (if-not (sequential? v)
    []
    (vec (for [s v :when (map? s)
               :let [c (when (map? (:camera s)) (:camera s))
                     hold (num (:hold s))
                     move (num (:move s))]]
           {:say (if (string? (:say s)) (subs (.trim (:say s)) 0 (min 1200 (count (.trim (:say s))))) "")
            :show (strings (:show s))
            :hide (strings (:hide s))
            :focus (strings (:focus s))
            :camera (when c {:frame (strings (:frame c)) :target (:target c)
                             :dist (num (:dist c)) :az (num (:az c)) :el (num (:el c))
                             :read (when (string? (:read c)) (:read c))})
            :hold (if (and hold (pos? hold)) hold 0)
            :pose (when (map? (:pose s)) (:pose s))
            :mark (when (map? (:mark s)) (:mark s))
            :move (if (and move (pos? move)) (min move 20) 1.4)}))))

(defn matches?
  "\"blade*\" matches every id starting with \"blade\"; anything else must match exactly."
  [pattern id]
  (if (.endsWith pattern "*")
    (.startsWith id (subs pattern 0 (dec (count pattern))))
    (= pattern id)))

(defn matches-any? [patterns id] (boolean (some #(matches? % id) patterns)))

(defn managed?
  "A part the story never shows is on from the start (the floor, a housing); the rest appear in
  their step."
  [steps id]
  (boolean (some #(matches-any? (:show %) id) steps)))

(defn visible-at
  "The ids that are on after step n (n = -1: before the story starts)."
  [steps n ids]
  (reduce (fn [on step]
            (reduce (fn [on id]
                      (cond-> on
                        (matches-any? (:show step) id) (conj id)
                        (matches-any? (:hide step) id) (disj id)))
                    on ids))
          (set (remove #(managed? steps %) ids))
          (take (inc n) steps)))

(defn pose-at
  "The pose a person is in after step n: the latest step up to n that gave them one (nil: their own)."
  [steps n id]
  (some (fn [i]
          (let [p (:pose (nth steps i))]
            (when p (or (get p (keyword id)) (get p id)))))
        (range (min n (dec (count steps))) -1 -1)))

(def gap-seconds 0.45)
(def ^:private words-per-second 2.6)

(defn estimate-seconds
  "How long a step lasts without a recording: the words at a calm pace, or its hold."
  [{:keys [say hold pose move]}]
  (let [moving (if pose move 0)]
    (if (empty? say)
      (max hold moving 1.6)
      (max hold moving (+ 0.6 (/ (count (.split say #"\s+")) words-per-second))))))
