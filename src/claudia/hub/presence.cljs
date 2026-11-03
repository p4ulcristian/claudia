(ns claudia.hub.presence
  "Who is at a desktop now: each Claudia page outside the iPhone app tells the hub whether its person
  is working at it (POST /hub/presence), every heartbeat while they are and at once when they stop. A
  person is present while any of their pages said \"active\" within stale-ms. Kept in memory: after a
  hub restart everyone is away until their page's next beat, so alerts go out rather than wait."
  (:require [claudia.shared.presence :as p]))

(defonce ^:private pages (atom {}))   ; email -> {page-id at}
(defonce ^:private timers (atom {}))  ; email -> timeout
(defonce ^:private on-away (atom (fn [_])))

(defn when-away! [f] (reset! on-away f))

(defn present?
  ([email] (present? email (js/Date.now)))
  ([email now] (boolean (some #(< (- now %) p/stale-ms) (vals (get @pages email))))))

(defn- check!
  "Away now: tell once (when it was present); else look again when the newest beat goes stale."
  [email was]
  (some-> (get @timers email) js/clearTimeout)
  (swap! timers dissoc email)
  (if-not (present? email)
    (do (swap! pages dissoc email)
        (when was (@on-away email)))
    (let [newest (apply max (vals (get @pages email)))]
      (swap! timers assoc email
             (js/setTimeout #(check! email true) (+ (- (+ newest p/stale-ms) (js/Date.now)) 50))))))

(defn beaten
  "A person's pages ({page-id at}) after one page's word at now: an active page beat now, an
  inactive one is gone, and pages silent for stale-ms are dropped."
  [m page active now]
  (into {} (filter #(< (- now (val %)) p/stale-ms))
        (if active (assoc m page now) (dissoc m page))))

(defn beat!
  "One page's word: active (a beat) or not (blurred, hidden, idle, closing)."
  [email page active]
  (let [was (present? email)]
    (swap! pages update email beaten page active (js/Date.now))
    (check! email was)))
