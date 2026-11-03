(ns claudia.web.scene3d.poses
  "Named poses for a scene3d person: where the hips, chest, hands and feet go, worked out from the
  body's own measurements (the rig), so a pose fits the model whatever its proportions. Coordinates
  are the person's own: x + their left, y up, z + in front of them, metres for a 1.75 m person, the
  floor at y = 0. A scene's pose starts from one of these (`base`) and overrides any field. A new
  pose glides there from the one before (`start-move`, `glide`).

  A rig: {:pelvis :neck :head :shoulder :elbow :wrist :hip :knee :ankle :ball (left side, [x y z])
  :top :arm :upper :fore :thigh :shin (numbers)}."
  (:require [claudia.web.scene3d.data :as data]
            [claudia.web.scene3d.maths :as m]))

(def ^:private add m/v+)
(defn mirror [[x y z]] [(- x) y z])
(defn- y-of [v] (nth v 1))

(defn- stand [r]
  (let [hand (add (:shoulder r) [0.07 (* -0.93 (:arm r)) 0.04])
        foot [(* 0.95 (first (:hip r))) (y-of (:ankle r)) (nth (:ankle r) 2)]]
    {:hips [0 (- (y-of (:pelvis r)) 0.005) (nth (:pelvis r) 2)] :chest (:neck r)
     :handL hand :handR (mirror hand) :footL foot :footR (mirror foot)}))

(defn- torso [r] (- (y-of (:neck r)) (y-of (:pelvis r))))

(defn- t-pose "Arms straight out to the sides." [r]
  (let [hand (add (:shoulder r) [(* 0.98 (:arm r)) 0 0])]
    (assoc (stand r) :handL hand :handR (mirror hand))))

(defn- reach "Both hands up over the head." [r]
  (let [hand (add (:shoulder r) [0.1 (* 0.96 (:arm r)) 0.06])]
    (assoc (stand r) :handL hand :handR (mirror hand) :look [0 3 1.5])))

(defn- wave "The right hand raised beside the head." [r]
  (let [s (mirror (:shoulder r))]
    (assoc (stand r) :handR (add s [-0.28 0.38 0.08]) :elbowR (add s [-0.6 -0.1 0]))))

(defn- point "The right arm straight out in front." [r]
  (let [s (mirror (:shoulder r))]
    (assoc (stand r) :handR (add s [0.04 0.02 (* 0.97 (:arm r))]) :look [0 1.5 3])))

(defn- hands-on-hips [r]
  (let [py (y-of (:pelvis r))
        hand [(+ (first (:hip r)) 0.07) (+ py 0.1) 0.02]]
    (assoc (stand r) :handL hand :handR (mirror hand)
           :elbowL [0.7 (+ py 0.15) -0.1] :elbowR [-0.7 (+ py 0.15) -0.1])))

(defn- hold "Hands in front at waist height, as if carrying a box." [r]
  (let [py (y-of (:pelvis r))
        hand [0.17 (+ py 0.22) 0.36]]
    (assoc (stand r) :handL hand :handR (mirror hand)
           :elbowL [0.5 py -0.2] :elbowR [-0.5 py -0.2] :look [0 0.9 1.5])))

(defn- walk "Mid-step: the left foot forward, arms swinging the other way." [r]
  (let [s (stand r) hx (first (:hip r)) ay (y-of (:ankle r))]
    (assoc s
           :hips (add (:hips s) [0 -0.03 0])
           :footL [hx ay 0.3]
           :footR [(- hx) (+ ay 0.06) -0.3]
           :toesR [(- hx) 0 -0.18]
           :handL (add (:shoulder r) [0.06 (* -0.9 (:arm r)) -0.18])
           :handR (add (mirror (:shoulder r)) [-0.06 (* -0.9 (:arm r)) 0.2])
           :look [0 1.6 4])))

(defn- sit "Sitting on a chair (seat 0.46 high), hands on the thighs." [r]
  (let [hip-y (+ 0.46 0.09)
        back -0.05
        hx (first (:hip r))
        knee [hx (- hip-y 0.02) (+ back (* 0.98 (:thigh r)))]
        foot [(* 1.05 hx) (y-of (:ankle r)) (+ (nth knee 2) 0.05)]
        hand [0.16 (+ hip-y 0.1) (+ back (* 0.6 (:thigh r)))]
        pole (add knee [0 0.2 0.4])]
    {:hips [0 hip-y back] :chest [0 (+ hip-y (torso r)) (- back 0.02)]
     :footL foot :footR (mirror foot) :kneeL pole :kneeR (mirror pole) :handL hand :handR (mirror hand)}))

(defn- sit-floor "Sitting on the floor, legs out in front, leaning back on the hands." [r]
  (let [hip-y 0.11 hx (first (:hip r))
        foot [(* 1.1 hx) 0.08 (* 0.97 (+ (:thigh r) (:shin r)))]
        hand [0.24 0.03 -0.24]
        toes (add foot [0 0.25 0.05])]
    {:hips [0 hip-y 0] :chest [0 (+ hip-y (* 0.97 (torso r))) -0.1] :pelvis [-5 0 0]
     :footL foot :footR (mirror foot) :toesL toes :toesR (mirror toes)
     :kneeL [hx 1 0.4] :kneeR [(- hx) 1 0.4] :handL hand :handR (mirror hand)
     :elbowL [0.5 0.3 -0.6] :elbowR [-0.5 0.3 -0.6]}))

(defn- kneel "Upright on both knees." [r]
  (let [hip-y (+ 0.06 (* 0.97 (:thigh r))) hx (first (:hip r))
        foot [hx 0.09 (* -0.92 (:shin r))]
        toes (add foot [0 -0.06 -0.2])
        s (stand r)
        lift [0 (- hip-y (y-of (:pelvis r))) 0]]
    (assoc s :hips [0 hip-y 0] :chest [0 (+ hip-y (torso r)) 0]
           :footL foot :footR (mirror foot) :toesL toes :toesR (mirror toes)
           :kneeL [hx 0.3 1] :kneeR [(- hx) 0.3 1]
           :handL (add (:handL s) lift) :handR (add (:handR s) lift))))

(defn- squat "A deep squat, heels down, arms forward for balance." [r]
  (let [hip-y 0.5 back -0.22
        foot [(* 1.3 (first (:hip r))) (y-of (:ankle r)) (nth (:ankle r) 2)]
        hand [0.17 0.95 0.5]]
    {:hips [0 hip-y back] :chest [0 (+ hip-y (* 0.95 (torso r))) (+ back 0.3)]
     :footL foot :footR (mirror foot) :kneeL [0.4 0.6 1] :kneeR [-0.4 0.6 1]
     :handL hand :handR (mirror hand) :look [0 1.2 3]}))

(defn- bend "Bent forward at the hips, hands hanging towards the floor." [r]
  (let [hip (- (y-of (:pelvis r)) 0.04)
        hand [0.15 0.2 0.36]]
    (assoc (stand r) :hips [0 hip -0.16] :chest [0 (- hip 0.1) 0.38] :pelvis [55 0 0]
           :handL hand :handR (mirror hand) :look [0 0 1])))

(defn- lie "Lying on the back, head towards -z, feet towards +z." [r]
  (let [y 0.11 t (torso r) hx (first (:hip r))
        foot [(* 1.15 hx) 0.09 (+ (:thigh r) (:shin r))]
        toes (add foot [0 0.25 0.04])
        hand [0.3 0.06 0.05]]
    {:hips [0 y 0] :chest [0 (+ y 0.02) (- t)] :pelvis [-90 0 0]
     :footL foot :footR (mirror foot) :toesL toes :toesR (mirror toes)
     :kneeL [hx 1 0.4] :kneeR [(- hx) 1 0.4] :handL hand :handR (mirror hand)
     :elbowL [0.6 0 -0.25] :elbowR [-0.6 0 -0.25] :look [0 3 (- t)]}))

(def ^:private poses
  {"stand" stand "t" t-pose "reach" reach "wave" wave "point" point "hands_on_hips" hands-on-hips
   "hold" hold "walk" walk "sit" sit "sit_floor" sit-floor "kneel" kneel "squat" squat "bend" bend
   "lie" lie})

(def names (vec (keys poses)))

(defn- v3 [v] (data/vec3 v nil))

(defn pose-of
  "A pose as a scene gives it (a name, or a map with an optional base) made whole: every field the
  base pose has, with the scene's overrides on top."
  [spec rig]
  (cond
    (string? spec) ((get poses spec stand) rig)
    (not (map? spec)) (stand rig)
    :else
    (let [base ((get poses (str (or (:base spec) "stand")) stand) rig)
          points (reduce (fn [out k] (if-let [v (v3 (get spec k))] (assoc out k v) out))
                         base [:hips :chest :pelvis :elbowL :elbowR :kneeL :kneeR :toesL :toesR])
          targets (reduce (fn [out k]
                            (let [v (get spec k)]
                              (cond (and (string? v) (seq v)) (assoc out k v)
                                    (v3 v) (assoc out k (v3 v))
                                    :else out)))
                          points [:look :handL :handR :footL :footR])
          twist (js/Number (:twist spec))]
      (cond-> targets
        (and (some? (:twist spec)) (js/isFinite twist)) (assoc :twist twist)))))

(defn refs?
  "Whether a pose looks at or reaches for a part (a string): it follows that part every frame."
  [spec rig]
  (let [p (pose-of spec rig)]
    (boolean (some #(string? (get p %)) [:look :handL :handR :footL :footR]))))

(defn chest-over
  "Where the chest goes without one given: over the hips, as high above them as at rest."
  [hips rig]
  [(first hips) (+ (second hips) (- (second (:neck rig)) (second (:pelvis rig)))) (nth hips 2)])

(defn toes
  "Where a foot's toes point: as given, else flat ahead on the floor (a foot higher than 0.25 m is
  left as it hangs: nil)."
  [given [fx fy fz] rig]
  (or given
      (when (< fy 0.25)
        [fx (+ fy (- (second (:ball rig)) (second (:ankle rig))))
         (+ fz (max (- (nth (:ball rig) 2) (nth (:ankle rig) 2)) 0.1))])))

(defn blend
  "A resolved pose t (0..1) of the way from a to b: numbers and points in between, the rest
  switching halfway."
  [a b t]
  (into {} (for [[k y] b :let [x (get a k)]]
             [k (cond (and (number? x) (number? y)) (+ x (* t (- y x)))
                      (and (vector? x) (vector? y)) (m/lerp3 x y t)
                      :else (if (< t 0.5) x y))])))

(defn start-move
  "A person's pose state when a new pose starts at `now` (ms): it glides from where they are over
  `seconds` (at once with 0 or before they were ever posed)."
  [state spec seconds now]
  (assoc state :spec spec
         :from (when (and (pos? seconds) (:cur state)) (:cur state))
         :t0 now :dur (* 1000 seconds) :dirty true))

(defn glide
  "The pose state at `now` given the goal (the resolved pose): :cur is where the body is, :from
  stays while the glide runs."
  [{:keys [from t0 dur] :as state} goal now]
  (let [p (min 1 (/ (- now t0) (max dur 1)))]
    (assoc state
           :cur (if from (blend from goal (m/ease-in-out-cubic p)) goal)
           :from (when (and from (< p 1)) from)
           :dirty false)))
