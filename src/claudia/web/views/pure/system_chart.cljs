(ns claudia.web.views.pure.system-chart
  "The History tab's 7-day chart (README decision 95), pure: the days' lines and names, the busy
  cores, memory and waiting as lines in percent, and the events as marks."
  (:require [claudia.web.views.pure.system-tiles :as tiles]))

(def day-ms 86400000)
(def week-ms (* 7 day-ms))

(def ^:private day-names ["Sun" "Mon" "Tue" "Wed" "Thu" "Fri" "Sat"])

(defn days
  "The local midnights after from up to to, each {:at :name}. offset: minutes local time is behind
  UTC (js/Date's getTimezoneOffset), so the days fall where the reader's do."
  [from to offset]
  (let [shift (* offset 60000)
        first-day (+ shift (* day-ms (js/Math.ceil (/ (- from shift) day-ms))))]
    (vec (for [at (range first-day (inc to) day-ms)]
           {:at at :name (day-names (.getUTCDay (js/Date. (- at shift))))}))))

(defn day-spans
  "The days from..to as spans {:at :end :name}: the first from `from` (part of a day), each one a
  local midnight to the next, the last up to `to`."
  [from to offset]
  (let [ds (days from to offset)
        first-name (day-names (.getUTCDay (js/Date. (- from (* offset 60000)))))
        starts (cons {:at from :name first-name} (remove #(= from (:at %)) ds))]
    (vec (map (fn [d nxt] (assoc d :end (or (:at nxt) to))) starts (concat (rest starts) [nil])))))

(defn x-of "Where a time sits across a chart w wide from..to." [at from to w]
  (/ (js/Math.round (* 10 w (/ (- at from) (max 1 (- to from))))) 10))

(defn event-tone "An event's mark: red for a crash or an out-of-memory kill, amber otherwise." [kind]
  (if (#{"oom" "crash"} kind) "red" "amber"))

(defn row-cores "A History row's busy cores in percent of all." [{:keys [cores coresTotal]}]
  (when (and cores coresTotal (pos? coresTotal)) (* 100 (/ cores coresTotal))))

(def series
  "The chart's lines: [id label value-of-a-row tone], each in percent."
  [["cores" "busy cores" row-cores "green"]
   ["memory" "memory" tiles/row-memory "blue"]
   ["waiting" "waiting" tiles/row-waiting "amber"]])

(defn slot-count "As many slots as fit, one per 3 px, at most a slot per minute of the week." [w]
  (max 24 (min 10080 (js/Math.floor (/ w 3)))))

(defn lines
  "Each shown series' path over the week to `to` in a w × h box: [{:id :tone :d}]. shown: the ids."
  [rows shown to w h]
  (let [n (slot-count w) from (- to week-ms)]
    (vec (for [[id _ f tone] series
               :when (shown id)]
           {:id id :tone tone :d (tiles/line-path (tiles/slots rows f from to n) n 100 w h)}))))

(defn marks
  "The events of the week as marks: each event with :x and :tone, oldest first."
  [events to w]
  (let [from (- to week-ms)]
    (vec (for [e (sort-by :at events) :when (<= from (:at e) to)]
           (assoc e :x (x-of (:at e) from to w) :tone (event-tone (:kind e)))))))
