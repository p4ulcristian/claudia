(ns claudia.web.face.mind
  "Claudia's face, what she feels (2026-11-05): her mood from the chats (working, waiting on you,
  servers gone), the events she reacts to (a chat done, a new chat, the list opened, you typing),
  and one step of her life per frame. All pure: the view (claudia.web.face.view) hands in the time
  and random numbers and draws what comes out.

  Her state: :mood, :clip (the act running, {:id :start :arg}), :look [x y] and :tilt and :spin
  (eased each step), :pointer (where the mouse is, nil when far), :hover?, :last-poke (when anything
  last happened), :blink-at / :next-blink / :next-idle (times), :clicks (recent click times),
  :away-at (when the page was hidden), :world (chat counts) and :hour."
  (:require [claudia.web.face.clips :as clips]
            [claudia.web.rowdiff :as rowdiff]))

(def sleep-after-ms 180000)
(def hello-after-ms 60000)

(defn initial
  "Her state when the page starts at time now."
  [now]
  {:mood :idle :clip nil :look [0 0] :tilt 0 :spin 0 :pointer nil :hover? false :last-poke now
   :blink-at -1e9 :next-blink (+ now 1500) :next-idle (+ now 4000) :clicks [] :away-at nil
   :world {:running 0 :waiting 0 :offline? false} :hour 12})

(defn world-of
  "What she watches in app-db: chats working, chats waiting on you, every server gone."
  [db]
  (let [rows (remove (comp :archived second) (rowdiff/keyed-rows db))
        statuses (map :status (vals (:servers db)))]
    {:running (count (filter (comp :running second) rows))
     :waiting (count (filter (comp :waiting second) rows))
     :offline? (boolean (and (seq statuses) (not-any? #{:open} statuses)))}))

(defn night? "Late hours, when she gets sleepy." [hour] (or (>= hour 23) (< hour 6)))

(defn mood-of
  "Her mood from the world, how long nothing happened (ms) and the hour."
  [{:keys [running waiting offline?]} quiet-ms hour]
  (cond offline? :offline
        (pos? waiting) :wait
        (>= running 3) :busy
        (pos? running) :work
        (> quiet-ms sleep-after-ms) :sleep
        (night? hour) :sleepy
        :else :idle))

(defn- chat-errors "Chats with an error, as a set of keys." [db]
  (set (keep (fn [[k c]] (when (:error c) k)) (:chats db))))

(defn- failures
  "A chat or the page got a new error."
  [before after]
  (when (or (seq (remove (chat-errors before) (chat-errors after)))
            (and (:error after) (not= (:error after) (:error before))))
    [:failed]))

(defn- list-events
  "The chat list opened or closed; another chat opened."
  [before after]
  (cond-> []
    (and (:sidebar? after) (not (:sidebar? before))) (conj :list-open)
    (and (:sidebar? before) (not (:sidebar? after))) (conj :list-close)
    (and (:open before) (:open after) (not= (:open before) (:open after))) (conj :switch)))

(defn- typing-events
  "A message went out (the outbox grew), or the open chat's draft changed."
  [before after]
  (let [sent-count #(reduce + (map count (vals (:outbox %))))
        draft #(get-in % [:drafts (or (:open %) :new)])]
    (cond (> (sent-count after) (sent-count before)) [:sent]
          (and (= (:open before) (:open after)) (not= (draft before) (draft after))) [:typing]
          :else [])))

(defn- turn-events
  "The chat rows' changes as event names; two or more done at once are one :many-done."
  [before after]
  (let [kinds (map first (rowdiff/row-events before after))
        dones (count (filter #{:done} kinds))
        named {:waiting :asked :new :new-chat :started :started :gone :gone}]
    (concat (when (= dones 1) [:done])
            (when (> dones 1) [:many-done])
            (keep named kinds))))

(defn db-events
  "Everything she reacts to between two states of app-db, as event names."
  [before after]
  (vec (concat (turn-events before after) (failures before after)
               (list-events before after) (typing-events before after))))

;; ---- acting ----

(defn- playing?
  "The clip still running at now, else nil."
  [{:keys [clip]} now]
  (when (and clip (< (- now (:start clip)) (:ms (clips/clips (:id clip))))) clip))

(defn start-clip
  "Starts clip id (with arg) unless a stronger one is running; a running :typing just goes on."
  ([state id now] (start-clip state id now nil))
  ([state id now arg]
   (let [running (playing? state now)
         prio (comp :prio clips/clips :id)]
     (if (and running (> (prio running) (prio {:id id})))
       state
       (assoc state :clip {:id id :start now :arg arg})))))

(defn- poked
  "Something happened at now: she is not alone any more (and wakes up if she slept)."
  [state now]
  (cond-> (assoc state :last-poke now)
    (= :sleep (:mood state)) (start-clip :wake now)
    (= :sleep (:mood state)) (assoc :mood :idle)))

(defn- clicked
  "A click: a giggle, or dizzy after four quick ones."
  [state now]
  (let [recent (conj (filterv #(< (- now %) 1500) (:clicks state)) now)]
    (-> (assoc state :clicks (if (>= (count recent) 4) [] recent))
        (start-clip (if (>= (count recent) 4) :dizzy :giggle) now))))

(defn- came-back
  "The page is seen again: hello when it was away a while."
  [state now]
  (cond-> (assoc state :away-at nil)
    (and (:away-at state) (> (- now (:away-at state)) hello-after-ms)) (start-clip :hello now)))

(defn react
  "Her state after event ev at time now: a db event name (db-events), :click, :hover-in, :hover-out,
  :hidden, :visible or :drag."
  [state ev now]
  (case ev
    :hover-in (-> (poked state now) (assoc :hover? true))
    :hover-out (assoc state :hover? false)
    :click (clicked (poked state now) now)
    :hidden (assoc state :away-at now)
    :visible (came-back (poked state now) now)
    (start-clip (poked state now) ev now)))

(defn point
  "The mouse at [dx dy] from her (in her own sizes, nil when far): she looks there."
  [state at now]
  (cond-> (assoc state :pointer at)
    at (poked now)))

;; ---- one step of her life ----

(def ^:private pointer-moods #{:idle :sleepy :wait})

(defn- blink-now
  "The natural blink at now (0 open to 1 shut), 160 ms long."
  [{:keys [blink-at]} now]
  (let [d (- now blink-at)]
    (if (< -1 d 160) (clips/bump (/ d 160)) 0)))

(defn pose
  "Her whole pose at now: the mood's, the mouse's if it is on her, the clip's on top; effects of
  mood and clip together; the natural blink unless the pose sets one."
  [{:keys [mood hover?] :as state} now]
  (let [base (clips/mood-pose mood now)
        over (when (and hover? (#{:idle :sleepy :sleep} mood)) clips/hover-pose)
        act (when-let [c (playing? state now)] (clips/clip-pose c now))]
    (-> (merge base over act)
        (assoc :fx (vec (concat (:fx base) (:fx act))))
        (update :blink #(or % (blink-now state now))))))

(defn- aim
  "Where she looks: the clip's way, else the mouse (in calm moods), else the mood's, else ahead."
  [{:keys [pointer mood] :as state} now the-pose]
  (or (when (playing? state now) (:look the-pose))
      (when (and pointer (pointer-moods mood)) pointer)
      (:look the-pose)
      [0 0]))

(defn- ease [a b k] (+ a (* (- b a) k)))

(defn- move
  "Look, lean and clip spin eased toward the pose over dt ms."
  [state now dt the-pose]
  (let [[tx ty] (aim state now the-pose)
        [lx ly] (:look state)
        k (min 1 (* dt 0.006 (if (= :busy (:mood state)) 2.5 1)))]
    (assoc state
           :look [(ease lx tx k) (ease ly ty k)]
           :tilt (ease (:tilt state) (* 3 tx) k)
           :spin (mod (+ (:spin state) (* (:spin the-pose 0) dt 0.06)) 360))))

(defn- own-life
  "When idle with nothing running: maybe start a clip of her own (rs: three random numbers)."
  [{:keys [mood hover? next-idle] :as state} now [r1 r2 r3]]
  (let [weights (clips/idle-weights mood)]
    (if (or (not weights) hover? (playing? state now) (< now next-idle))
      state
      (-> (start-clip state (clips/pick-weighted weights r1) now [(dec (* 2 r2)) (- (* 1.4 r3) 0.7)])
          (assoc :next-idle (+ now 3500 (* 8000 r2)))))))

(defn- blinking
  "Schedules the natural blinks (r: a random number; sometimes two come close together)."
  [{:keys [next-blink] :as state} now r]
  (if (< now next-blink)
    state
    (assoc state :blink-at now :next-blink (+ now (if (< r 0.15) 260 (+ 1800 (* 4200 r)))))))

(defn tick
  "One step at now, dt ms after the last, with four random numbers rs and the hour: the mood from
  the world, a clip of her own maybe, blinks, easing; the pose to draw is in :pose."
  [state now dt rs hour]
  (let [mood (mood-of (:world state) (- now (:last-poke state)) hour)
        s1 (-> (assoc state :mood mood :hour hour)
               (own-life now (take 3 rs))
               (blinking now (nth rs 3)))
        the-pose (pose s1 now)]
    (assoc (move s1 now dt the-pose) :pose the-pose)))
