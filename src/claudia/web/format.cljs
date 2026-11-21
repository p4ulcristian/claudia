(ns claudia.web.format
  "How the page writes times, durations, sizes and paths."
  (:require [clojure.string :as str]))

(defn when-text
  "A time (ms or anything js/Date takes) as the reader's local date and time; nil for nil."
  [at]
  (when at (.toLocaleString (js/Date. at) js/undefined #js {:dateStyle "medium" :timeStyle "short"})))

(defn clock-text
  "A time (ms) as the reader's clock: \"14:02\" today, \"Tue 14:02\" in the last week, else
  \"Nov 12, 14:02\"; nil for nil."
  [at now]
  (when at
    (let [d (js/Date. at)
          hm (.toLocaleTimeString d js/undefined #js {:hour "2-digit" :minute "2-digit" :hour12 false})]
      (cond (= (.toDateString d) (.toDateString (js/Date. now))) hm
            (< (- now at) (* 6 86400000)) (str (.toLocaleDateString d js/undefined #js {:weekday "short"}) " " hm)
            :else (str (.toLocaleDateString d js/undefined #js {:month "short" :day "numeric"}) ", " hm)))))

(defn tilde
  "A path under someone's home folder with ~ for that folder."
  [p]
  (str/replace (str p) #"^/home/[^/]+" "~"))

(defn duration
  "Seconds as m:ss (whole seconds, never below 0:00)."
  [secs]
  (let [s (max 0 (js/Math.floor secs))]
    (str (quot s 60) ":" (when (< (mod s 60) 10) "0") (mod s 60))))

(def ^:private units ["B" "KiB" "MiB" "GiB" "TiB" "PiB"])

(defn bytes-text
  "Bytes in binary units, as the kernel counts: \"13.1 GiB\" (one decimal under 100, none above;
  whole bytes as they are)."
  [n]
  (loop [v (or n 0) i 0]
    (if (and (>= v 1024) (< i (dec (count units))))
      (recur (/ v 1024) (inc i))
      (str (cond (zero? i) v (< v 100) (.toFixed v 1) :else (js/Math.round v)) " " (nth units i)))))
