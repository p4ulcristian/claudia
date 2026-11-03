(ns claudia.hub.live
  "One summary Live Activity per person's phone: while any of their chats runs or waits on them, a
  lock-screen card and the Dynamic Island show how many, with the chats' titles. The hub starts it
  with the phone's push-to-start token, updates it through the token the app reports for it (POST
  /hub/push/live), and ends it when nothing has run or waited for a short while. ActivityKit payloads
  can't be sealed (iOS reads them, not the app), so these titles go to Apple in plain text: the
  user's accepted cost.

  Updates are state, not news: at most one per update-gap-ms per person, priority 10 only when a chat
  newly waits on them; one that fails is not retried as such: the state as it then stands is sent
  again a little later."
  (:require [clojure.string :as str]
            [claudia.hub.apns :as apns]
            [claudia.hub.store :as store]))

(def ^:private update-gap-ms 5000)
;; Nothing running or waiting for this long ends the card (a follow-up right after keeps it).
(def ^:private end-after-ms 30000)
;; After the end, the card stays on the lock screen this long (seconds).
(def ^:private dismiss-s (* 15 60))
;; iOS ends a card after 8 hours; one that ended sooner was dismissed by the person.
(def ^:private renew-after-ms (* 7.75 3600000))
(def ^:private retry-ms 30000)
(def ^:private list-max 5)
(def ^:private title-max 40)
(def ^:private payload-max 4096)
(def attributes "ClaudiaSummaryAttributes")

(defonce ^:private sender (atom nil))
(defonce ^:private summary-of (atom (fn [_] {:running [] :waiting [] :done 0})))
(defonce ^:private people (atom {}))   ; email -> {:timer :busy :last :urgent :quiet-since :waiting}
(defonce ^:private sent-sig (atom {})) ; activity -> what it was last sent (without the time)

(defn start! [send summary]
  (reset! sender send)
  (reset! summary-of summary))

(defn- cut-title [s]
  (let [chars (vec (or (not-empty (str/trim (str/replace (str s) #"\s+" " "))) "Chat"))]
    (if (> (count chars) title-max) (str (apply str (take (dec title-max) chars)) "…") (apply str chars))))

(defn- person [email] (get @people email {:last 0 :waiting #{}}))
(defn- person! [email f & args] (swap! people update email #(apply f (or % {:last 0 :waiting #{}}) args)))

(defn- on? [s] (pos? (+ (count (:running s)) (count (:waiting s)))))

(defn- byte-length [x] (js/Buffer.byteLength (js/JSON.stringify (clj->js x))))

(defn content-of
  "The content-state the app's ClaudiaSummaryAttributes reads: at most five of each, titles cut, the
  whole push under Apple's 4 KB (the longer list gives way first)."
  [s now-s wrap]
  (let [item (fn [i] {:title (cut-title (:title i)) :server (:server i) :chat (:chat i)
                      :since (:since i) :kind (:kind i)})
        state (fn [r w] {:running r :waiting w :done (:done s) :updatedAt now-s})]
    (loop [r (mapv #(assoc (item %) :kind nil) (take list-max (:running s)))
           w (mapv item (take list-max (:waiting s)))]
      (if (and (> (byte-length (wrap (state r w))) payload-max) (pos? (+ (count r) (count w))))
        (if (>= (count r) (count w)) (recur (pop r) w) (recur r (pop w)))
        (state r w)))))

(declare schedule!)

(defn changed!
  "The person's chats changed: the card follows (soon, within the gap)."
  [email]
  (when @sender
    (let [s (@summary-of email)
          waiting (set (map #(str (:server %) "/" (:chat %)) (:waiting s)))]
      (person! email (fn [p]
                       (cond-> (assoc p :waiting waiting)
                         (some #(not ((:waiting p) %)) waiting) (assoc :urgent true)
                         (on? s) (assoc :quiet-since nil)
                         (and (not (on? s)) (not (:quiet-since p))) (assoc :quiet-since (js/Date.now)))))
      (schedule! email nil))))

(defn- in-order
  "Runs (f x) for each x, one after another -> Promise."
  [xs f]
  (reduce (fn [p x] (.then p #(f x))) (js/Promise.resolve) xs))

(defn- target [d token]
  {:token token :env (:env d) :topic (str (:topic d) ".push-type.liveactivity")})

(defn- gone! [activity d]
  (js/console.log (str "push: APNs says a Live Activity token of " (apns/short-token (:token d)) " is gone; dropped"))
  (store/drop-activity! activity)
  (swap! sent-sig dissoc activity))

(defn- flush! [email]
  (let [p (person email)
        now (js/Date.now)
        now-s (js/Math.floor (/ now 1000))
        s (@summary-of email)
        on (on? s)
        quiet (and (not on) (:quiet-since p) (< (- now (:quiet-since p)) end-after-ms))
        priority (if (:urgent p) 10 5)
        sent (atom false)
        again (atom false)
        send (fn [d token payload prio]
               (reset! sent true)
               (@sender (target d token) payload {:type "liveactivity" :priority prio}))]
    (-> (in-order
         (store/devices email)
         (fn [d]
           (let [acts (store/activities (:token d))]
             (cond
               (and (or on quiet) (seq acts))
               (let [wrap (fn [st] {:aps {:timestamp now-s :event "update" :content-state st}})
                     state (content-of s now-s wrap)
                     sig (js/JSON.stringify (clj->js (assoc state :updatedAt 0)))]
                 (in-order acts
                           (fn [a]
                             (when-not (= sig (get @sent-sig (:activity a)))
                               (.then (send d (:token a) (wrap state) priority)
                                      (fn [{:keys [status detail]}]
                                        (case status
                                          (:ok :dry) (swap! sent-sig assoc (:activity a) sig)
                                          :gone (gone! (:activity a) d)
                                          :retry (reset! again true)
                                          (js/console.error (str "push: Live Activity update to " (apns/short-token (:token d)) " not sent: " detail)))))))))

               (and on (:live-start d) (nil? (:live-started-at d)))
               (let [wrap (fn [st] {:aps {:timestamp now-s :event "start" :content-state st
                                          :attributes-type attributes :attributes {}
                                          :alert {:title "Claudia" :body "Chats running"}}})]
                 (.then (send d (:live-start d) (wrap (content-of s now-s wrap)) 10)
                        (fn [{:keys [status detail]}]
                          (case status
                            (:ok :dry) (store/live-started! (:token d) now)
                            :gone (do (js/console.log (str "push: the push-to-start token of " (apns/short-token (:token d)) " is gone (" detail "); dropped"))
                                      (store/set-live-start! (:token d) nil))
                            :retry (reset! again true)
                            (js/console.error (str "push: Live Activity start on " (apns/short-token (:token d)) " not sent: " detail))))))

               (and (not on) (not quiet))
               (let [wrap (fn [st] {:aps {:timestamp now-s :event "end" :content-state st :dismissal-date (+ now-s dismiss-s)}})
                     failed-retry (atom false)]
                 (-> (in-order acts
                               (fn [a]
                                 (.then (send d (:token a) (wrap (content-of s now-s wrap)) 5)
                                        (fn [{:keys [status detail]}]
                                          (if (= status :retry)
                                            (do (reset! again true) (reset! failed-retry true))
                                            (do (when (= status :failed)
                                                  (js/console.error (str "push: Live Activity end to " (apns/short-token (:token d)) " not sent: " detail)))
                                                (store/end-activity! (:activity a))
                                                (swap! sent-sig dissoc (:activity a))))))))
                     (.then #(when (and (:live-started-at d) (not @failed-retry))
                               (store/live-started! (:token d) nil)))))))))
        (.then (fn []
                 (when @sent (person! email assoc :last now :urgent false))
                 (cond @again (schedule! email (+ (js/Date.now) retry-ms))
                       quiet (schedule! email (max (+ (:quiet-since p) end-after-ms)
                                                   (+ (:last (person email)) update-gap-ms)))))))))

(defn- schedule! [email at]
  (let [p (person email)]
    (some-> (:timer p) js/clearTimeout)
    (let [when-ms (or at (max (+ (:last p) update-gap-ms) (js/Date.now)))]
      (person! email assoc :timer
               (js/setTimeout
                (fn []
                  (person! email assoc :timer nil)
                  (if (:busy (person email))
                    (schedule! email (+ (js/Date.now) 200))
                    (do (person! email assoc :busy true)
                        (-> (flush! email)
                            (.catch #(js/console.error (str "push: Live Activity: " (.-message %))))
                            (.finally #(person! email assoc :busy false))))))
                (max 0 (- when-ms (js/Date.now))))))))

(defn ended!
  "The app saw its activity end (iOS's 8 hours, or the person dismissed it)."
  [email {:keys [activity device added-at]}]
  (when (store/end-activity! activity)
    (swap! sent-sig dissoc activity)
    (when (and (>= (- (js/Date.now) added-at) renew-after-ms) (empty? (store/activities device)))
      (store/live-started! device nil))
    (changed! email)))
