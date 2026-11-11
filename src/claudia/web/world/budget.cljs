(ns claudia.web.world.budget
  "How sharp the world is drawn, kept to what the card manages: the resolution scale drops a step
  when frames come late (slower than the screen's own refresh) and climbs back while they keep up.
  Pure: the engine feeds it frame times once a second.")

(def ^:private lowest 0.6)
(def ^:private stride 0.15)

(defn refresh-ms
  "The screen's refresh interval, as the shortest of the frame times seen (ms)."
  [frame-times]
  (reduce min 1000 frame-times))

(defn next-scale
  "The resolution scale after a second of frame-times (ms): down a step when the median frame is a
  quarter or more late, up a step (to cap) when nearly every frame is on time, else unchanged."
  [scale frame-times cap]
  (if (< (count frame-times) 10)
    scale
    (let [sorted (vec (sort frame-times))
          refresh (first sorted)
          median (sorted (quot (count sorted) 2))
          slow (sorted (int (* 0.9 (count sorted))))]
      (cond (> median (* 1.25 refresh)) (max lowest (- scale stride))
            (< slow (* 1.1 refresh)) (min cap (+ scale stride))
            :else scale))))
