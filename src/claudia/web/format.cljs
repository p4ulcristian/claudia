(ns claudia.web.format
  "How the page writes times, durations and paths."
  (:require [clojure.string :as str]))

(defn when-text
  "A time (ms or anything js/Date takes) as the reader's local date and time; nil for nil."
  [at]
  (when at (.toLocaleString (js/Date. at) js/undefined #js {:dateStyle "medium" :timeStyle "short"})))

(defn tilde
  "A path under someone's home folder with ~ for that folder."
  [p]
  (str/replace (str p) #"^/home/[^/]+" "~"))

(defn duration
  "Seconds as m:ss (whole seconds, never below 0:00)."
  [secs]
  (let [s (max 0 (js/Math.floor secs))]
    (str (quot s 60) ":" (when (< (mod s 60) 10) "0") (mod s 60))))
