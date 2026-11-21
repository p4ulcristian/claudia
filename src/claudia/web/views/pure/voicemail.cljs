(ns claudia.web.views.pure.voicemail
  "Voice messages as circles round the mic (README decision 90): one circle per chat holding its
  waiting messages, where each sits, how big, and what a finger on one means.")

(def keep-ms "How long a message waits to be heard: a day (the runner keeps it as long)." (* 24 60 60 1000))

(def max-circles "Circles shown round the mic; more chats wait their turn." 6)

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
(def ^:private gap-deg
  "The first two circles' turn from the middle: straight out from the mic stays free for the
  speaker's name, the hint and the clips waiting to be heard."
  40)
(def ^:private step-deg "The angle between neighbouring circles on one side." 34)

(defn- turn
  "The i-th circle's turn from the middle, degrees: one side then the other, 40, -40, 74, -74 …"
  [i]
  (* (if (even? i) 1 -1) (+ gap-deg (* step-deg (quot i 2)))))

(defn spots
  "Where n circles sit round the mic, {:x :y} px from its centre (y down), the chat waiting longest
  first, alternating sides: side :left (the tablet's rail: above then below its left), else above
  it, to the right then the left."
  [n side]
  (let [mid (if (= side :left) 180 270)]
    (vec (for [i (range n)
               :let [a (* (+ mid (turn i)) (/ js/Math.PI 180))]]
           {:x (js/Math.round (* ring-r (js/Math.cos a))) :y (js/Math.round (* ring-r (js/Math.sin a)))}))))

(def ^:private swipe-px "A finger moved this far on a circle swipes it away." 36)
(def long-press-ms "A finger held this long on a circle opens its chat." 550)

(defn gesture
  "What a finger on a circle did, moved dx dy px over ms: :dismiss, :open or :play."
  [dx dy ms]
  (cond
    (>= (js/Math.hypot dx dy) swipe-px) :dismiss
    (>= ms long-press-ms) :open
    :else :play))
