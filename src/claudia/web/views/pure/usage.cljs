(ns claudia.web.views.pure.usage
  "Meters, the model and the / menu as plain data: token counts, a model's name, a meter's level, the
  time left, the commands a typed / matches."
  (:require [clojure.string :as str]))

(defn k-tokens
  "Tokens short: 950, 155k, 1.2M; ? when not known."
  [n]
  (cond (nil? n) "?"
        (>= n 1e6) (str (/ (js/Math.round (/ n 1e5)) 10) "M")
        (>= n 1000) (str (js/Math.round (/ n 1000)) "k")
        :else (str n)))

(defn pretty-model
  "\"claude-opus-5-5[1m]\" -> \"Opus 5.5 1M\"."
  [id]
  (when (seq id)
    (let [long? (re-find #"(?i)\[1m\]" id)
          parts (->> (str/split (-> id (str/replace #"\[.*\]" "") (str/replace #"^claude-" "")) #"-")
                     (remove #(re-matches #"\d{8}" %)))
          family (or (some #(when (re-matches #"(?i)[a-z]+" %) %) parts) (first parts))
          version (str/join "." (filter #(re-matches #"\d+" %) parts))]
      (str/trim (str (str/upper-case (subs family 0 1)) (subs family 1) " " version (when long? " 1M"))))))

(defn level
  "A meter's colour by its percent."
  [p]
  (cond (>= p 90) "red" (>= p 70) "amber" :else "ok"))

(defn countdown
  "Time left: \"2d 4h\", \"3h 12m\", seconds in the last hour (\"14m 05s\")."
  [ms]
  (if (<= ms 0)
    "now"
    (let [s (js/Math.floor (/ ms 1000))
          d (quot s 86400) hh (quot (mod s 86400) 3600) m (quot (mod s 3600) 60) sec (mod s 60)]
      (cond (pos? d) (str d "d " hh "h")
            (pos? hh) (str hh "h " m "m")
            :else (str m "m " (when (< sec 10) "0") sec "s")))))

(def short-name {"five_hour" "5h" "seven_day" "wk" "seven_day_opus" "op" "seven_day_sonnet" "so"})

(defn two
  "The 5-hour and the week limit, in that order, those there are."
  [limits]
  (keep (fn [k] (some #(when (= k (:key %)) %) limits)) ["five_hour" "seven_day"]))

(defn limits-tip
  "The rings' hover text: each limit's percent and the time left."
  [shown now]
  (str/join " · " (for [l shown]
                    (str (:label l) " " (:percent l) "%"
                         (when (:resetsAt l) (str " · in " (countdown (- (:resetsAt l) now))))))))

(defn commands-of
  "The chat's slash commands: its own (the SDK's init) with the descriptions the probe read, else the
  probe's list."
  [state server-meta]
  (let [described (into {} (map (juxt :name identity)) (:commands server-meta))]
    (if (seq (:commands state))
      (vec (for [n (:commands state)] (or (described n) {:name n})))
      (vec (:commands server-meta)))))

(defn matches
  "\"/\" at the start, before any space: the commands that fit, those starting with it first."
  [text oc server-meta]
  (when-let [[_ q] (re-matches #"/(\S*)" (or text ""))]
    (let [q (str/lower-case q)]
      (->> (commands-of (:state oc) server-meta)
           (filter #(str/includes? (str/lower-case (:name %)) q))
           (sort-by #(if (str/starts-with? (str/lower-case (:name %)) q) 0 1))
           (take 8)
           vec))))

(defn chosen-model
  "The model picked in settings (default when none) among models, else the first."
  [models settings]
  (or (some #(when (= (:value %) (or (:model settings) "default")) %) models) (first models)))
