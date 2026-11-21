(ns claudia.web.views.pure.voicemail
  "Voice messages as circles round the mic (README decision 90): one circle per chat holding its
  waiting messages, where each sits, how big, and what a finger on one means.")

(def keep-ms "How long a message waits to be heard: a day (the runner keeps it as long)." (* 24 60 60 1000))

(def max-circles "Circles shown round a floating mic (a full ring); more chats wait their turn." 8)

(defn max-for
  "How many circles fit on side: a full ring (:ring) holds max-circles, a fan above the mic six."
  [side]
  (if (= side :ring) max-circles 6))

(defn circles
  "The runners' messages (by-server: {server [{:chat :id :voice :text or :ask :at}]}) as one circle
  per chat, the chat waiting longest first: [{:k [server chat] :msgs (oldest first) :at}]. Messages a
  day old or more at now are left out."
  [by-server now]
  (->> (for [[server msgs] by-server
             m msgs
             :when (> (:at m) (- now keep-ms))]
         (assoc m :k [server (:chat m)]))
       (group-by :k)
       (map (fn [[k ms]] (let [ms (vec (sort-by :at ms))] {:k k :msgs ms :at (:at (first ms))})))
       (sort-by :at)
       (take max-circles)
       vec))

(defn- words-of [{:keys [text ask]}]
  (+ (count (str text)) (reduce + 0 (map #(count (str (:question %))) (:questions ask)))))

(defn size
  "A circle's diameter in px: 30, up to 40 for a lot to say."
  [msgs]
  (+ 30 (min 10 (quot (reduce + 0 (map words-of msgs)) 150))))

(def ^:private ring-r "How far a circle's centre sits from the mic's centre, px." 76)
(def ^:private fan-gap-deg
  "Above the mic, the first two circles' turn from straight up: straight up stays free for the
  speaker's name, the hint and the clips waiting to be heard."
  20)
(def ^:private fan-step-deg "Above the mic, the angle between neighbouring circles on one side." 30)

(defn- fan-turn
  "Above the mic, the i-th circle's turn from straight up, degrees: right then left, 20, -20, 50, -50 …"
  [i]
  (* (if (even? i) 1 -1) (+ fan-gap-deg (* fan-step-deg (quot i 2)))))

(defn- angle
  "The i-th circle's direction from the mic's centre, degrees (0 right, 90 down): round a floating
  mic a ring of max-circles clockwise from just right of straight up (half a step off, so none sits
  straight up, down, left or right); else the fan above it."
  [i side]
  (if (= side :ring)
    (let [step (/ 360 max-circles)] (+ -90 (/ step 2) (* i step)))
    (+ 270 (fan-turn i))))

(defn spots
  "Where n circles sit round the mic, {:x :y} px from its centre (y down), the chat waiting longest
  first: side :ring a full ring round it, else a fan above it."
  [n side]
  (vec (for [i (range n)
             :let [a (* (angle i side) (/ js/Math.PI 180))]]
         {:x (js/Math.round (* ring-r (js/Math.cos a))) :y (js/Math.round (* ring-r (js/Math.sin a)))})))
