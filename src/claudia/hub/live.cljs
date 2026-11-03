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
  (:require [claudia.shared.text :as text]
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

(defn cut-title
  "A chat's title on the card: one line, at most title-max characters, \"Chat\" when it has none."
  [s]
  (text/fit (or (not-empty (text/one-line s)) "Chat") title-max))

(def ^:private fresh-person {:last 0 :waiting #{}})

(defn- person [email] (get @people email fresh-person))
(defn- person! [email f & args] (swap! people update email #(apply f (or % fresh-person) args)))

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

(defn update-push [now-s st] {:aps {:timestamp now-s :event "update" :content-state st}})

(defn start-push [now-s st]
  {:aps {:timestamp now-s :event "start" :content-state st
         :attributes-type attributes :attributes {}
         :alert {:title "Claudia" :body "Chats running"}}})

(defn end-push [now-s st]
  {:aps {:timestamp now-s :event "end" :content-state st :dismissal-date (+ now-s dismiss-s)}})

(defn noted
  "The person's card state p after their summary s changed at now: urgent when a chat newly waits,
  quiet since now when nothing runs or waits any more."
  [p s now]
  (let [waiting (set (map #(str (:server %) "/" (:chat %)) (:waiting s)))]
    (cond-> (assoc p :waiting waiting)
      (some #(not ((:waiting p) %)) waiting) (assoc :urgent true)
      (on? s) (assoc :quiet-since nil)
      (and (not (on? s)) (not (:quiet-since p))) (assoc :quiet-since now))))

(declare schedule!)

(defn changed!
  "The person's chats changed: the card follows (soon, within the gap)."
  [email]
  (when @sender
    (let [s (@summary-of email)
          now (js/Date.now)]
      (person! email noted s now)
      (schedule! email nil))))

(defn- in-order
  "Runs (f x) for each x, one after another -> Promise of their results."
  [xs f]
  (reduce (fn [p x] (.then p (fn [acc] (.then (js/Promise.resolve (f x)) #(conj acc %)))))
          (js/Promise.resolve [])
          xs))

(defn outcome
  "What a flush's sends add up to: :sent when any push went out, :again when any asks to retry.
  Results nest (a phone's results are its activities' results); nil is a push not sent."
  [results]
  (let [all (remove nil? (flatten (vector results)))]
    {:sent (boolean (some :sent all)) :again (boolean (some :again all))}))

(defn- target [d token]
  {:token token :env (:env d) :topic (str (:topic d) ".push-type.liveactivity")})

(defn- send! [d token payload prio]
  (@sender (target d token) payload {:type "liveactivity" :priority prio}))

(defn- sent [status] {:sent true :again (= status :retry)})

(defn- gone! [activity d]
  (js/console.log (str "push: APNs says a Live Activity token of " (apns/short-token (:token d)) " is gone; dropped"))
  (store/drop-activity! activity)
  (swap! sent-sig dissoc activity))

(defn state-sig
  "What a content-state looks like apart from its time: the same one is not sent twice."
  [state]
  (js/JSON.stringify (clj->js (assoc state :updatedAt 0))))

(defn- update-one! [d a payload sig priority]
  (when-not (= sig (get @sent-sig (:activity a)))
    (.then (send! d (:token a) payload priority)
           (fn [{:keys [status detail]}]
             (case status
               (:ok :dry) (swap! sent-sig assoc (:activity a) sig)
               :gone (gone! (:activity a) d)
               :retry nil
               (js/console.error (str "push: Live Activity update to " (apns/short-token (:token d)) " not sent: " detail)))
             (sent status)))))

(defn- update-card! [d acts s now-s priority]
  (let [state (content-of s now-s (partial update-push now-s))
        sig (state-sig state)]
    (in-order acts #(update-one! d % (update-push now-s state) sig priority))))

(defn- start-card! [d s now now-s]
  (.then (send! d (:live-start d) (start-push now-s (content-of s now-s (partial start-push now-s))) 10)
         (fn [{:keys [status detail]}]
           (case status
             (:ok :dry) (store/live-started! (:token d) now)
             :gone (do (js/console.log (str "push: the push-to-start token of " (apns/short-token (:token d)) " is gone (" detail "); dropped"))
                       (store/set-live-start! (:token d) nil))
             :retry nil
             (js/console.error (str "push: Live Activity start on " (apns/short-token (:token d)) " not sent: " detail)))
           (sent status))))

(defn- end-one! [d a s now-s]
  (.then (send! d (:token a) (end-push now-s (content-of s now-s (partial end-push now-s))) 5)
         (fn [{:keys [status detail]}]
           (when-not (= status :retry)
             (when (= status :failed)
               (js/console.error (str "push: Live Activity end to " (apns/short-token (:token d)) " not sent: " detail)))
             (store/end-activity! (:activity a))
             (swap! sent-sig dissoc (:activity a)))
           (sent status))))

(defn- end-card!
  "Ends each of the phone's activities; once all ended (none asks to retry), it may get a new card."
  [d acts s now-s]
  (.then (in-order acts #(end-one! d % s now-s))
         (fn [results]
           (when (and (:live-started-at d) (not (:again (outcome results))))
             (store/live-started! (:token d) nil))
           results)))

(defn card-step
  "What a phone's card needs now: :update its activities, :start one, :end them, or nil."
  [d acts on quiet]
  (cond
    (and (or on quiet) (seq acts)) :update
    (and on (:live-start d) (nil? (:live-started-at d))) :start
    (and (not on) (not quiet)) :end
    :else nil))

(defn- flush-phone! [d {:keys [s on quiet now now-s priority]}]
  (let [acts (store/activities (:token d))]
    (case (card-step d acts on quiet)
      :update (update-card! d acts s now-s priority)
      :start (start-card! d s now now-s)
      :end (end-card! d acts s now-s)
      nil)))

(defn next-flush
  "When to flush again (ms), or nil: soon after a retry was asked for; while quiet, when the quiet
  ends (not before the gap since the last send)."
  [{:keys [again]} quiet quiet-since last-sent now]
  (cond
    again (+ now retry-ms)
    quiet (max (+ quiet-since end-after-ms) (+ last-sent update-gap-ms))
    :else nil))

(defn- flush! [email]
  (let [p (person email)
        now (js/Date.now)
        s (@summary-of email)
        on (on? s)
        quiet (boolean (and (not on) (:quiet-since p) (< (- now (:quiet-since p)) end-after-ms)))
        ctx {:s s :on on :quiet quiet :now now :now-s (js/Math.floor (/ now 1000))
             :priority (if (:urgent p) 10 5)}]
    (.then (in-order (store/devices email) #(flush-phone! % ctx))
           (fn [results]
             (let [o (outcome results)]
               (when (:sent o) (person! email assoc :last now :urgent false))
               (when-let [at (next-flush o quiet (:quiet-since p) (:last (person email)) (js/Date.now))]
                 (schedule! email at)))))))

(defn- run-flush! [email]
  (person! email assoc :timer nil)
  (if (:busy (person email))
    (schedule! email (+ (js/Date.now) 200))
    (do (person! email assoc :busy true)
        (-> (flush! email)
            (.catch #(js/console.error (str "push: Live Activity: " (.-message %))))
            (.finally #(person! email assoc :busy false))))))

(defn- schedule! [email at]
  (let [p (person email)
        due (or at (max (+ (:last p) update-gap-ms) (js/Date.now)))]
    (some-> (:timer p) js/clearTimeout)
    (person! email assoc :timer
             (js/setTimeout #(run-flush! email) (max 0 (- due (js/Date.now)))))))

(defn ended!
  "The app saw its activity end (iOS's 8 hours, or the person dismissed it)."
  [email {:keys [activity device added-at]}]
  (when (store/end-activity! activity)
    (swap! sent-sig dissoc activity)
    (when (and (>= (- (js/Date.now) added-at) renew-after-ms) (empty? (store/activities device)))
      (store/live-started! device nil))
    (changed! email)))
