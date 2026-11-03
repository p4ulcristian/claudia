(ns claudia.hub.push
  "Phone pushes (README, Hub operations: push): the hub tells a person's iPhones when one of their chats finished
  a turn, asks them a question, or waits on a secret they must allow. It learns that the way the page
  does, as that person: for each server the access grid lets them use, the front's socket (/ws, with
  X-Claudia-Email and no view, so only the person's own chats) sends `chats` on `hello` and on every
  change. Servers reach nothing new. Only people with a phone are watched.

  Each event is told once per person (the store remembers which: claim!), so a restart or a reconnect
  sends nothing twice. An alert's title and body are sealed for each phone; every alert carries the
  badge (chats waiting on the person plus chats finished since they last looked, across their
  servers), and a change of that count alone goes as a badge-only push. While the person works at a
  desktop page alerts are held; when they leave, only what still matters goes, the newest per chat."
  (:require ["ws" :refer [WebSocket]]
            [claudia.node.http :as io]
            [claudia.shared.text :as text]
            [clojure.string :as str]
            [claudia.hub.apns :as apns]
            [claudia.hub.config :as config]
            [claudia.hub.live :as live]
            [claudia.hub.presence :as presence]
            [claudia.hub.seal :as seal]
            [claudia.hub.store :as store]
            [claudia.shared.protocol :as protocol]))

;; A turn that ended longer ago than this when the hub first sees it is old news. Open asks are told
;; however old: they still wait.
(def ^:private fresh-ms (* 10 60000))
(def ^:private backoff-ms [1000 2000 5000 10000 30000 60000])
(def ^:private retry-ms [5000 30000 120000])
(def ^:private ping-ms 30000)
;; A badge-only push waits for the count to stay put this long.
(def ^:private badge-settle-ms 3000)
;; Until each of a person's servers has answered once, their count is partial.
(def ^:private badge-ready-ms 30000)
(def ^:private started (js/Date.now))

(defonce ^:private sender (atom nil))
(defonce ^:private watchers (atom {}))     ; [email server address] -> watcher atom
(defonce ^:private lists (atom {}))        ; email -> {server [Row]}
(defonce ^:private held (atom {}))         ; email -> {"server/chat" [notice]}
(defonce ^:private badge-timers (atom {}))
(defonce ^:private steady-times (atom {})) ; key -> since (s), kept steady across lists

(defn on? [] (some? @sender))

(defn- chat-id? [id] (and (string? id) (re-matches #"^[A-Za-z0-9-]{1,100}$" id)))

(defn- working?
  "Claude's turn runs, or work it left running after the turn does (subagents, background shells)."
  [r]
  (or (boolean (:running r)) (pos? (or (:agents r) 0))))

;; Notices: what a list holds to tell -------------------------------------------------------------

(defn- one-line-fit [s n] (text/fit (text/one-line s) n))

(defn- ask-notice
  "An open ask ({id kind text}) as a notice: a question, or a secret waiting on the person."
  [base a]
  (let [said (not-empty (str (:text a)))]
    (if (= "question" (:kind a))
      (assoc base :kind "ask" :event (str "ask:" (:id a))
             :body (one-line-fit (or said "Has a question") 300))
      (assoc base :kind "permission" :event (str "ask:" (:id a))
             :body (one-line-fit (str "Needs your permission: " (or said "a secret")) 180)))))

(defn- news-turn?
  "The chat's last turn is news: it ended on its own, recently, after the person's first phone, its
  agents are done, and the person has not seen it."
  [r since now]
  (let [t (:lastTurn r)]
    (boolean
     (and (map? t) (string? (:id t)) (not (:stopped t)) (not (working? r))
          (not (false? (:unseen r))) (number? (:ago t)) (< (:ago t) fresh-ms)
          (>= (- now (:ago t)) since)))))

(defn- finished-notice [base t]
  (assoc base :kind "finished" :event (str "turn:" (:id t))
         :body (if (:failed t) "Ended with an error" (one-line-fit (or (not-empty (str (:reply t))) "Finished") 200))))

(defn- chat-notices [server r since now]
  (let [base {:server server :chat (:id r) :title (one-line-fit (or (not-empty (str (:title r))) "Chat") 80)}]
    (cond-> (mapv #(ask-notice base %) (:asks r))
      (news-turn? r since now) (conj (finished-notice base (:lastTurn r))))))

(defn notices-of
  "The events a server's list holds for its person now (the TypeScript Claudia's src/hub/push.ts
  noticesOf). since: when they got their first phone (a turn that ended before that is not news to
  it). A Row's `asks` are its open asks ({id kind text}); `lastTurn` how its last turn ended
  ({id ago stopped failed reply}). Not told: a turn the person stopped, one whose agents still work
  (told once the last ends), one they have seen since it ended."
  [server rows since now]
  (vec (for [r rows
             :when (and (chat-id? (:id r)) (not (:archived r)))
             n (chat-notices server r since now)]
         n)))

(defn payload-of
  "What the phone gets: a plain alert Apple can read and, sealed with the phone's key in enc, the real
  title and body, which the app's extension shows instead. url: the chat's address on the hub."
  [n device-key badge]
  {:aps {:alert {:title "Claudia" :body "New message"}
         :mutable-content 1
         :sound "default"
         :badge badge
         :thread-id (str (:server n) "/" (:chat n))
         :interruption-level (if (= "finished" (:kind n)) "active" "time-sensitive")}
   :enc (seal/seal device-key {:title (:title n) :body (:body n)})
   :url (str "/c/" (:server n) "/" (:chat n))
   :server (:server n)
   :chat (:chat n)
   :kind (:kind n)})

;; The summary: badge and Live Activity ------------------------------------------------------------

(defn row-state
  "Where a chat stands for the summary: :waiting on the person (asks open), :running, :done (finished
  unseen), or nil (archived, or nothing to show)."
  [r]
  (cond
    (:archived r) nil
    (or (seq (:asks r)) (:waiting r)) :waiting
    (working? r) :running
    (:unseen r) :done
    :else nil))

(defn wait-kind
  "A waiting chat's kind: \"ask\" when one of its asks is a question, else \"permission\"."
  [asks]
  (if (some #(= "question" (:kind %)) asks) "ask" "permission"))

(defn started-at
  "When something started (seconds), from how long ago (ms) it did as of now (ms)."
  [ago now]
  (js/Math.floor (/ (- now (max 0 (or ago 0))) 1000)))

(defn- clock
  "A running or waiting chat as {:state :key :at :entry}: :at its start from the server's ago, :key
  what keeps that start steady (a new set of asks is a new wait)."
  [email server r state now]
  (let [waiting? (= state :waiting)]
    {:state state
     :key (str email "\n" server "/" (:id r)
               (if waiting? (str "\nwait:" (str/join "," (map :id (:asks r)))) "\nrun"))
     :at (started-at (if waiting? (reduce max 0 (keep :ago (:asks r))) (:runningFor r)) now)
     :entry {:title (or (not-empty (str (:title r))) "Chat") :server server :chat (:id r)
             :kind (when waiting? (wait-kind (:asks r)))}}))

(defn chat-clocks
  "The person's lists ([server rows] pairs, servers they may use) -> {:clocks [clock] :done n}: every
  chat that runs or waits on them, and how many finished unseen."
  [email server-rows now]
  (let [rows (for [[server rs] server-rows, r rs] [server r (row-state r)])]
    {:clocks (vec (for [[server r state] rows :when (#{:waiting :running} state)]
                    (clock email server r state now)))
     :done (count (filter #(= :done (nth % 2)) rows))}))

(defn steady
  "Fresh start times (key -> s) kept steady: each stays the one seen before (prev) while within 10 s
  of it, since an ago moves by a list's delay."
  [prev fresh]
  (into {} (for [[k at] fresh
                 :let [had (get prev k)]]
             [k (if (and had (<= (js/Math.abs (- had at)) 10)) had at)])))

(defn next-times
  "All steady times after a summary of this person: theirs are the fresh ones kept steady (the rest
  of theirs dropped), everyone else's as they were."
  [prev email fresh]
  (let [mine? #(str/starts-with? (key %) (str email "\n"))]
    (merge (into {} (remove mine?) prev) (steady prev fresh))))

(defn summary
  "{:running :waiting :done}: the clocks' chats with their steady :since, each list oldest first."
  [{:keys [clocks done]} times]
  (let [of (fn [state]
             (->> clocks
                  (filter #(= state (:state %)))
                  (map #(assoc (:entry %) :since (get times (:key %))))
                  (sort-by :since)
                  vec))]
    {:running (of :running) :waiting (of :waiting) :done done}))

(defn summary-of
  "Every chat of the person's that runs or waits on them, and how many finished unseen, across the
  servers the grid lets them use."
  [email]
  (let [mine (filter #(store/may? email (key %)) (get @lists email))
        found (chat-clocks email mine (js/Date.now))
        fresh (into {} (map (juxt :key :at)) (:clocks found))]
    (summary found (swap! steady-times next-times email fresh))))

(defn badge-of [email]
  (let [s (summary-of email)] (+ (count (:waiting s)) (:done s))))

(defn- badge-ready? [email]
  (or (>= (- (js/Date.now) started) badge-ready-ms)
      (every? #(:reported @%) (filter #(= email (:email @%)) (vals @watchers)))))

(defn- gone-device! [d detail]
  (js/console.log (str "push: APNs says " (apns/short-token (:token d)) " (" (:email d) ") is gone (" detail "); dropped"))
  (store/forget-device! (:token d)))

(declare schedule-badge!)

(defn- badge-answered! [email d n {:keys [status detail]}]
  (case status
    :ok (do (store/set-badge! (:token d) n) (store/device-ok! (:token d)))
    :dry (store/set-badge! (:token d) n)
    :gone (gone-device! d detail)
    :retry (schedule-badge! email 30000)
    (js/console.error (str "push: badge to " (apns/short-token (:token d)) " not sent: " detail))))

(defn- send-badge! [email]
  (swap! badge-timers dissoc email)
  (if-not (badge-ready? email)
    (schedule-badge! email 5000)
    (let [n (badge-of email)]
      (doseq [d (store/devices email) :when (not= n (:badge d))]
        (.then (@sender d {:aps {:badge n}} {:type "alert" :priority 5})
               #(badge-answered! email d n %))))))

(defn- schedule-badge! [email wait]
  (some-> (get @badge-timers email) js/clearTimeout)
  (swap! badge-timers assoc email (js/setTimeout #(send-badge! email) wait)))

(defn changed!
  "The badge and the Live Activity follow the person's lists (a phone registered, a list changed)."
  [email]
  (when (on?)
    (schedule-badge! email badge-settle-ms)
    (live/changed! email)))

;; Alerts ----------------------------------------------------------------------------------------

(defn- still-theirs?
  "The phone is still the same person's (not dropped or moved to another person)."
  [d]
  (some #(= (:token %) (:token d)) (store/devices (:email d))))

(declare deliver!)

(defn- delivered! [d n badge payload i {:keys [status detail]}]
  (cond
    (#{:ok :dry} status) (do (when (= status :ok) (store/device-ok! (:token d)))
                             (store/set-badge! (:token d) badge))
    (= status :gone) (gone-device! d detail)
    (or (= status :failed) (>= i (count retry-ms)))
    (js/console.error (str "push: " (:kind n) " for " (:server n) "/" (:chat n) " to "
                           (apns/short-token (:token d)) " not sent: " detail))
    :else (js/setTimeout #(when (still-theirs? d) (deliver! d n badge payload (inc i)))
                         (nth retry-ms i))))

(defn- deliver!
  "One alert to one phone, tried again after each of retry-ms while APNs says retry."
  ([d n badge] (deliver! d n badge (payload-of n (:key d) badge) 0))
  ([d n badge payload i]
   (.then (@sender d payload) #(delivered! d n badge payload i %))))

(defn- alert! [email n]
  (let [badge (badge-of email)]
    (doseq [d (store/devices email)] (deliver! d n badge))))

(defn with-held
  "The person's held alerts (\"server/chat\" -> [notice]) with news added, each event once per chat."
  [m news]
  (reduce (fn [acc n]
            (let [k (str (:server n) "/" (:chat n))]
              (if (some #(= (:event %) (:event n)) (get acc k))
                acc
                (update acc k (fnil conj []) n))))
          (or m {}) news))

(defn- hold! [email news]
  (when (seq news)
    (swap! held update email with-held news)))

(defn matters?
  "A held notice still means something for its chat's row r as it stands: the turn is still the last
  and unseen with nothing at work, or the ask is still open."
  [r n]
  (boolean
   (and r
        (if (= "finished" (:kind n))
          (and (= (:event n) (str "turn:" (get-in r [:lastTurn :id]))) (not (working? r)) (not (false? (:unseen r))))
          (some #(= (:event n) (str "ask:" (:id %))) (:asks r))))))

(defn- still-matters? [email n]
  (let [r (some #(when (= (:id %) (:chat n)) %) (get-in @lists [email (:server n)]))]
    (boolean (and (store/may? email (:server n)) (matters? r n)))))

(defn- release!
  "The person left the desktop: per chat the newest held alert goes if it still matters; the rest are
  dropped, never told later."
  [email]
  (let [mine (get @held email)]
    (swap! held dissoc email)
    (doseq [notices (vals mine)
            :let [newest (peek notices)]]
      (doseq [n (pop notices)] (store/claim! email (:server n) (:chat n) (:event n)))
      (when (and (store/claim! email (:server newest) (:chat newest) (:event newest))
                 (still-matters? email newest))
        (alert! email newest)))))

(defn- tell! [email server rows]
  ;; Still let in, and still with a phone (the grid may have changed since the last sync).
  (let [devices (store/devices email)]
    (when (and (store/may? email server) (seq devices))
      (swap! lists assoc-in [email server] (filterv #(chat-id? (:id %)) rows))
      (let [since (apply min (map :added-at devices))
            news (remove #(store/told? email (:server %) (:chat %) (:event %))
                         (notices-of server rows since (js/Date.now)))]
        (if (presence/present? email)
          (hold! email news)
          (doseq [n news :when (store/claim! email (:server n) (:chat n) (:event n))]
            (alert! email n)))
        (changed! email)))))

;; Watchers: one person on one server ------------------------------------------------------------

(defn backoff
  "How long to wait (ms) before connecting again after this many tries; r in [0, 1) spreads it."
  [tries r]
  (* (nth backoff-ms (min tries (dec (count backoff-ms)))) (+ 0.8 (* 0.4 r))))

(defn chats-of
  "The chats a socket frame carries when it is a `chats` frame, else nil."
  [data]
  (let [f (try (protocol/read-json (str data)) (catch :default _ nil))]
    (when (and (= "chats" (:type f)) (sequential? (:chats f)))
      (:chats f))))

(defn- troubled! [w why]
  (when-not (= why (:trouble @w))
    (swap! w assoc :trouble why)
    (js/console.error (str "push: " (:email @w) " on " (:server @w) ": " why "; trying again"))))

(declare connect!)

(defn- down! [w ^js ws why]
  (when (identical? ws (:ws @w))
    (some-> (:ping @w) js/clearInterval)
    (swap! w assoc :ws nil :ping nil)
    (.terminate ws)
    (when-not (:stopped @w)
      (troubled! w why)
      (let [t (js/setTimeout #(connect! w) (backoff (:tries @w) (js/Math.random)))]
        (swap! w #(-> % (update :tries inc) (assoc :timer t)))))))

(defn- opened! [w ^js ws alive]
  (.send ws (protocol/write-json {:type "hello"}))
  ;; A link that died without closing: no pong within a beat.
  (swap! w assoc :ping (js/setInterval
                        (fn []
                          (if-not @alive
                            (down! w ws "no answer to ping")
                            (do (reset! alive false) (.ping ws))))
                        ping-ms)))

(defn- heard! [w alive data]
  (reset! alive true)
  (when-let [chats (chats-of data)]
    (let [{:keys [email server trouble]} @w]
      (when trouble (js/console.log (str "push: " email " on " server ": back")))
      (swap! w assoc :trouble nil :tries 0 :reported true)
      (tell! email server chats))))

(defn- connect! [w]
  (when-not (:stopped @w)
    (let [{:keys [email host port]} @w
          ^js ws (WebSocket. (str "ws://" (io/url-host host) ":" port "/ws")
                             #js {:headers #js {"x-claudia-email" email} :handshakeTimeout 10000})
          alive (atom true)]
      (swap! w assoc :ws ws)
      (.on ws "open" #(opened! w ws alive))
      (.on ws "pong" #(reset! alive true))
      (.on ws "message" #(heard! w alive %))
      (.on ws "unexpected-response"
           (fn [_ ^js res] (down! w ws (str "its socket answered " (.-statusCode res)))))
      (.on ws "close" #(down! w ws "no chat socket"))
      (.on ws "error" #(down! w ws "no chat socket")))))

(defn- watcher [email s]
  (doto (atom {:email email :server (:name s) :host (:host s) :port (:port s) :tries 0})
    (connect!)))

(defn- stop-watcher! [w]
  (swap! w assoc :stopped true)
  (some-> (:timer @w) js/clearTimeout)
  (some-> (:ping @w) js/clearInterval)
  (some-> ^js (:ws @w) .terminate))

(defn- wanted
  "[email server address] -> server for each person with a phone and each server the grid lets them use."
  []
  (into {} (for [{:keys [email]} (store/push-people)
                 s (config/servers-of {:email email})]
             [[email (:name s) (:address s)] s])))

(defn sync!
  "Each person's servers as they stand: watched as long as they have a phone and the grid lets them in."
  []
  (when (on?)
    (let [want (wanted)
          gone (remove want (keys @watchers))]
      (doseq [[email server :as k] gone]
        (stop-watcher! (get @watchers k))
        (swap! watchers dissoc k)
        (swap! lists update email dissoc server))
      (doseq [[[email :as k] s] want :when (not (get @watchers k))]
        (swap! watchers assoc k (watcher email s)))
      ;; (a server taken away from someone leaves their badge and card at once)
      (doseq [email (distinct (map first gone))] (changed! email)))))

(defn- open-sender
  "Promise of the send function: the dry run, APNs with the key, or nil (push off, said why)."
  []
  (if (= "1" (.. js/process -env -HUB_APNS_DRY_RUN))
    (do (js/console.log "push: dry run (HUB_APNS_DRY_RUN=1): pushes are logged, not sent")
        (js/Promise.resolve apns/dry-run))
    (let [c (apns/config)]
      (if (:off c)
        (do (js/console.log (str "push: off (" (:off c) "); iPhone pushes need HUB_APNS_KEY_FILE, HUB_APNS_KEY_ID and HUB_APNS_TEAM_ID"))
            (js/Promise.resolve nil))
        (-> (apns/create c)
            (.then (fn [send] (js/console.log (str "push: on (APNs key " (:key-id c) ")")) send))
            (.catch (fn [e] (js/console.error (str "push: off (the APNs key in HUB_APNS_KEY_FILE can't be used: " (.-message e) ")")) nil)))))))

(defn- prune! []
  (store/prune-pushed! (- (js/Date.now) (* 30 24 3600000)))
  (store/prune-activities! (- (js/Date.now) (* 24 3600000))))

(defn- push-on! [send]
  (reset! sender send)
  (live/start! send summary-of)
  (presence/when-away! release!)
  (sync!)
  ;; The grid, the servers and the phones change through the hub, which syncs at once;
  ;; this catches anything else.
  (.unref (js/setInterval sync! 60000))
  (prune!)
  (.unref (js/setInterval prune! (* 24 3600000))))

(defn start!
  "Once: push on if the APNs key is set (or a dry run), else said once why not."
  []
  (.then (open-sender) #(when % (push-on! %))))

(defn stop! []
  (doseq [w (vals @watchers)] (stop-watcher! w))
  (reset! watchers {}))
