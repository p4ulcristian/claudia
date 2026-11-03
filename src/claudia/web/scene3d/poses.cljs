(ns claudia.web.scene3d.poses
  "Named poses for a scene3d person: where the hips, chest, hands and feet go, worked out from the
  body's own measurements (the rig), so a pose fits the model whatever its proportions. Coordinates
  are the person's own: x + their left, y up, z + in front of them, metres for a 1.75 m person, the
  floor at y = 0. A scene's pose starts from one of these (`base`) and overrides any field.

  A rig: {:pelvis :neck :head :shoulder :elbow :wrist :hip :knee :ankle :ball (left side, [x y z])
  :top :arm :upper :fore :thigh :shin (numbers)}.")

(defn add [[a b c] [x y z]] [(+ a x) (+ b y) (+ c z)])
(defn mirror [[x y z]] [(- x) y z])
(defn- y-of [v] (nth v 1))

(defn- stand [r]
  (let [hand (add (:shoulder r) [0.07 (* -0.93 (:arm r)) 0.04])
        foot [(* 0.95 (first (:hip r))) (y-of (:ankle r)) (nth (:ankle r) 2)]]
    {:hips [0 (- (y-of (:pelvis r)) 0.005) (nth (:pelvis r) 2)] :chest (:neck r)
     :handL hand :handR (mirror hand) :footL foot :footR (mirror foot)}))

(defn- torso [r] (- (y-of (:neck r)) (y-of (:pelvis r))))

(defn- sit [r]
  (let [hip-y (+ 0.46 0.09)
        back -0.05
        hx (first (:hip r))
        knee [hx (- hip-y 0.02) (+ back (* 0.98 (:thigh r)))]
        foot [(* 1.05 hx) (y-of (:ankle r)) (+ (nth knee 2) 0.05)]
        hand [0.16 (+ hip-y 0.1) (+ back (* 0.6 (:thigh r)))]
        pole (add knee [0 0.2 0.4])]
    {:hips [0 hip-y back] :chest [0 (+ hip-y (torso r)) (- back 0.02)]
     :footL foot :footR (mirror foot) :kneeL pole :kneeR (mirror pole) :handL hand :handR (mirror hand)}))

(def ^:private poses
  {"stand" stand
   ;; arms straight out to the sides
   "t" (fn [r] (let [hand (add (:shoulder r) [(* 0.98 (:arm r)) 0 0])]
                 (assoc (stand r) :handL hand :handR (mirror hand))))
   ;; both hands up over the head
   "reach" (fn [r] (let [hand (add (:shoulder r) [0.1 (* 0.96 (:arm r)) 0.06])]
                     (assoc (stand r) :handL hand :handR (mirror hand) :look [0 3 1.5])))
   ;; the right hand raised beside the head
   "wave" (fn [r] (let [s (mirror (:shoulder r))]
                    (assoc (stand r) :handR (add s [-0.28 0.38 0.08]) :elbowR (add s [-0.6 -0.1 0]))))
   ;; the right arm straight out in front
   "point" (fn [r] (let [s (mirror (:shoulder r))]
                     (assoc (stand r) :handR (add s [0.04 0.02 (* 0.97 (:arm r))]) :look [0 1.5 3])))
   "hands_on_hips" (fn [r] (let [py (y-of (:pelvis r))
                                 hand [(+ (first (:hip r)) 0.07) (+ py 0.1) 0.02]]
                             (assoc (stand r) :handL hand :handR (mirror hand)
                                    :elbowL [0.7 (+ py 0.15) -0.1] :elbowR [-0.7 (+ py 0.15) -0.1])))
   ;; hands in front at waist height, as if carrying a box
   "hold" (fn [r] (let [py (y-of (:pelvis r))
                        hand [0.17 (+ py 0.22) 0.36]]
                    (assoc (stand r) :handL hand :handR (mirror hand)
                           :elbowL [0.5 py -0.2] :elbowR [-0.5 py -0.2] :look [0 0.9 1.5])))
   ;; mid-step: the left foot forward, arms swinging the other way
   "walk" (fn [r] (let [s (stand r) hx (first (:hip r)) ay (y-of (:ankle r))]
                    (assoc s
                           :hips (add (:hips s) [0 -0.03 0])
                           :footL [hx ay 0.3]
                           :footR [(- hx) (+ ay 0.06) -0.3]
                           :toesR [(- hx) 0 -0.18]
                           :handL (add (:shoulder r) [0.06 (* -0.9 (:arm r)) -0.18])
                           :handR (add (mirror (:shoulder r)) [-0.06 (* -0.9 (:arm r)) 0.2])
                           :look [0 1.6 4])))
   ;; sitting on a chair (seat 0.46 high), hands on the thighs
   "sit" sit
   ;; sitting on the floor, legs out in front, leaning back on the hands
   "sit_floor" (fn [r] (let [hip-y 0.11 hx (first (:hip r))
                             foot [(* 1.1 hx) 0.08 (* 0.97 (+ (:thigh r) (:shin r)))]
                             hand [0.24 0.03 -0.24]
                             toes (add foot [0 0.25 0.05])]
                         {:hips [0 hip-y 0] :chest [0 (+ hip-y (* 0.97 (torso r))) -0.1] :pelvis [-5 0 0]
                          :footL foot :footR (mirror foot) :toesL toes :toesR (mirror toes)
                          :kneeL [hx 1 0.4] :kneeR [(- hx) 1 0.4] :handL hand :handR (mirror hand)
                          :elbowL [0.5 0.3 -0.6] :elbowR [-0.5 0.3 -0.6]}))
   ;; upright on both knees
   "kneel" (fn [r] (let [hip-y (+ 0.06 (* 0.97 (:thigh r))) hx (first (:hip r))
                         foot [hx 0.09 (* -0.92 (:shin r))]
                         toes (add foot [0 -0.06 -0.2])
                         s (stand r)
                         lift [0 (- hip-y (y-of (:pelvis r))) 0]]
                     (assoc s :hips [0 hip-y 0] :chest [0 (+ hip-y (torso r)) 0]
                            :footL foot :footR (mirror foot) :toesL toes :toesR (mirror toes)
                            :kneeL [hx 0.3 1] :kneeR [(- hx) 0.3 1]
                            :handL (add (:handL s) lift) :handR (add (:handR s) lift))))
   ;; a deep squat, heels down, arms forward for balance
   "squat" (fn [r] (let [hip-y 0.5 back -0.22
                         foot [(* 1.3 (first (:hip r))) (y-of (:ankle r)) (nth (:ankle r) 2)]
                         hand [0.17 0.95 0.5]]
                     {:hips [0 hip-y back] :chest [0 (+ hip-y (* 0.95 (torso r))) (+ back 0.3)]
                      :footL foot :footR (mirror foot) :kneeL [0.4 0.6 1] :kneeR [-0.4 0.6 1]
                      :handL hand :handR (mirror hand) :look [0 1.2 3]}))
   ;; bent forward at the hips, hands hanging towards the floor
   "bend" (fn [r] (let [hip (- (y-of (:pelvis r)) 0.04)
                        hand [0.15 0.2 0.36]]
                    (assoc (stand r) :hips [0 hip -0.16] :chest [0 (- hip 0.1) 0.38] :pelvis [55 0 0]
                           :handL hand :handR (mirror hand) :look [0 0 1])))
   ;; lying on the back, head towards -z, feet towards +z
   "lie" (fn [r] (let [y 0.11 t (torso r) hx (first (:hip r))
                       foot [(* 1.15 hx) 0.09 (+ (:thigh r) (:shin r))]
                       toes (add foot [0 0.25 0.04])
                       hand [0.3 0.06 0.05]]
                   {:hips [0 y 0] :chest [0 (+ y 0.02) (- t)] :pelvis [-90 0 0]
                    :footL foot :footR (mirror foot) :toesL toes :toesR (mirror toes)
                    :kneeL [hx 1 0.4] :kneeR [(- hx) 1 0.4] :handL hand :handR (mirror hand)
                    :elbowL [0.6 0 -0.25] :elbowR [-0.6 0 -0.25] :look [0 3 (- t)]}))})

(def names (vec (keys poses)))

(defn- v3 [v]
  (when (and (sequential? v) (>= (count v) 3))
    (mapv #(let [n (js/Number %)] (if (js/isNaN n) 0 n)) (take 3 v))))

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
