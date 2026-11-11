(ns claudia.web.world.motion
  "How a robot moves, as plain numbers: a slightly underdamped spring toward where it is going (it
  settles with a small overshoot), a top speed, turning toward where it heads, leaning into its
  speed and its turns, and its hover. Frame-rate independent: every step takes dt in seconds.")

(def max-dt
  "Longer frames (a tab coming back) are cut to this, so the spring never jumps."
  (/ 1 30))

(def ^:private stiffness 14)
(def ^:private damping (* 2 0.72 (js/Math.sqrt stiffness)))
(def top-speed 5.5)

(defn spring-velocity
  "One axis of the spring: the velocity after dt toward target (semi-implicit Euler: move by it
  after capping it)."
  [x v target dt]
  (+ v (* dt (- (* stiffness (- target x)) (* damping v)))))

(defn cap-speed
  "Velocity [vx vz] cut to top speed, its direction kept."
  [vx vz]
  (let [s (js/Math.hypot vx vz)]
    (if (> s top-speed) [(* vx (/ top-speed s)) (* vz (/ top-speed s))] [vx vz])))

(defn wrap-angle
  "An angle in (-pi, pi]."
  [a]
  (- a (* 2 js/Math.PI (js/Math.round (/ a (* 2 js/Math.PI))))))

(defn damp
  "Eases a toward b at rate lambda over dt, the same at any frame rate."
  [a b lambda dt]
  (+ a (* (- b a) (- 1 (js/Math.exp (- (* lambda dt)))))))

(defn damp-angle
  "damp for angles: the short way round."
  [a b lambda dt]
  (+ a (* (wrap-angle (- b a)) (- 1 (js/Math.exp (- (* lambda dt)))))))

(defn heading
  "The yaw a robot faces: where it moves when it moves, else rest-yaw (three.js: yaw 0 faces +z)."
  [vx vz rest-yaw]
  (if (> (js/Math.hypot vx vz) 0.35) (js/Math.atan2 vx vz) rest-yaw))

(defn lean
  "Forward tilt from speed and side tilt from turning, both capped."
  [speed turn-rate]
  [(js/Math.min 0.32 (* 0.07 speed))
   (js/Math.max -0.35 (js/Math.min 0.35 (* -0.12 turn-rate)))])

(defn hover
  "Height over the spot: a slow bob, a quicker one while working, low and still when off."
  [state t phase]
  (case state
    :off 0.6
    :working (+ 1.25 (* 0.06 (js/Math.sin (+ (* 5 t) phase))))
    :waiting (+ 1.4 (* 0.15 (js/Math.sin (+ (* 3.2 t) phase))))
    (+ 1.25 (* 0.12 (js/Math.sin (+ (* 2 t) phase))))))

(defn wander
  "An idle robot's drift round its spot: a slow loop of radius r."
  [t phase r]
  [(* r (js/Math.sin (+ (* 0.23 t) phase))) (* r (js/Math.sin (+ (* 0.31 t) (* 2 phase))))])

(defn blink
  "Eye height (1 open, 0 shut): a short blink every few seconds."
  [t phase]
  (let [period (+ 3 (* 2 (/ phase (* 2 js/Math.PI))))
        x (mod (+ t phase) period)]
    (if (< x 0.14) (js/Math.abs (- 1 (/ x 0.07))) 1)))
