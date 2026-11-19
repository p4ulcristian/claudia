(ns claudia.slave.sdk-version
  "Which Agent SDK a chat's session runs on (README decision 80): the versions compared, the one a
  new session starts on, the one it falls back to, where its files are, and whether an idle chat
  moves to the newest. Pure: data in, data out."
  (:require [clojure.string :as str]))

(defn- parts
  "A version (\"0.3.326\", \"1.0.0-beta.2\") as [numbers pre-release]: the numbers of its core, and
  its pre-release text or nil."
  [v]
  (let [[core pre] (str/split (str v) #"-" 2)]
    [(mapv #(let [n (js/parseInt % 10)] (if (js/isNaN n) 0 n)) (str/split core #"\.")) pre]))

(defn- pad [nums n] (into nums (repeat (- n (count nums)) 0)))

(defn compare-versions
  "Negative when a is older than b, 0 when the same, positive when newer. Numbers compare as numbers
  (0.3.99 is older than 0.3.326); a pre-release is older than its release."
  [a b]
  (let [[na pa] (parts a)
        [nb pb] (parts b)
        n (max (count na) (count nb))
        c (compare (pad na n) (pad nb n))]
    (cond (not (zero? c)) c
          (= pa pb) 0
          (nil? pa) 1
          (nil? pb) -1
          :else (compare pa pb))))

(defn older? [a b] (neg? (compare-versions a b)))

(defn newest
  "The newest of versions (nil when there are none)."
  [versions]
  (reduce (fn [best v] (if (or (nil? best) (older? best v)) v best)) nil versions))

(defn fallback
  "The version a session falls back to when failed did not load or start: the one the host started
  with, else the newest other loaded one; nil when there is none."
  [failed start loaded]
  (if (and start (not= start failed) (contains? (set loaded) start))
    start
    (newest (remove #{failed} loaded))))

(defn start-version
  "The version a new session starts on: the release's (installed), unless it failed before (broken:
  version -> error), then its fallback."
  [installed broken start loaded]
  (if (and installed (not (contains? broken installed)))
    installed
    (fallback installed start (remove #(contains? broken %) loaded))))

(defn stored-package
  "Where version's SDK package is in a release folder's SDK store (<release>.sdk/<version>,
  deploy/sdk-store.sh): the copy a session loads when it is there, since its files stay while a
  session runs on them (the release's own node_modules go two updates later)."
  [release version]
  (str release ".sdk/" version "/node_modules/@anthropic-ai/claude-agent-sdk"))

(defn package-dir
  "The first of candidates ([[dir version-in-it] ...], in order of preference: the store copy, then
  the release's own) that holds version v, or nil."
  [v candidates]
  (when v (some (fn [[dir found]] (when (= v found) dir)) candidates)))

(defn move-to-newest?
  "Whether a chat's session closes now to move to the newest SDK: it is live, between turns, not
  settling (a background task just ended: Claude may start a turn of its own), with no background
  task running, and its SDK is older than the one new sessions start on (newest)."
  [{:keys [live running settling background sdk] latest :newest}]
  (boolean (and live (not running) (not settling) (not background)
                (string? sdk) (string? latest) (older? sdk latest))))
