(ns claudia.web.world.atmosphere
  "What makes the world feel alive without costing the card: the sky (a TSL gradient by the hour of
  the day, no texture), fog in the horizon's colour, two lights, drifting clouds (one InstancedMesh),
  the lighthouse beam, the bridges' traffic (one InstancedMesh of glowing packets) and the windows
  that light up while a chat works in their building."
  (:require ["three/webgpu" :as THREE]
            ["three/tsl" :as tsl]
            [claudia.web.world.geo :as geo]
            [claudia.web.world.plan :as plan]))

(defn- hour-now []
  (let [d (js/Date.)] (+ (.getHours d) (/ (.getMinutes d) 60))))

(defn daylight
  "How bright the lights are at an hour: 1 at midday, 0.35 at night."
  [hour]
  (+ 0.35 (* 0.65 (max 0 (js/Math.sin (* js/Math.PI (/ (- hour 6) 12)))))))

(defn- sky-node
  "Sky all round the world: the horizon's colour in a band, the zenith's above and, a little darker,
  below (the islands float in it)."
  [zenith horizon]
  (let [y (.-y tsl/positionWorldDirection)]
    (tsl/mix horizon (.mul zenith (tsl/mix 0.8 1.0 (tsl/step 0 y)))
             (tsl/smoothstep 0.02 0.5 (tsl/abs y)))))

(defn make-sky!
  "The sky and fog on scene, lights added; returns the uniforms and lights tick! retunes."
  [^js scene]
  (let [zenith (tsl/uniform (THREE/Color.))
        horizon (tsl/uniform (THREE/Color.))
        near (tsl/uniform 60.0)
        far (tsl/uniform 220.0)
        hemi (THREE/HemisphereLight. 0xe4f0ff 0x9a8a78 1.7)
        sun (THREE/DirectionalLight. 0xfff0d8 2.2)]
    (set! (.-backgroundNode scene) (sky-node zenith horizon))
    (set! (.-fogNode scene) (tsl/fog horizon (tsl/rangeFogFactor near far)))
    (.set (.-position sun) 10 18 8)
    (.add scene hemi) (.add scene sun)
    #js {:zenith zenith :horizon horizon :near near :far far :hemi hemi :sun sun}))

(defn tune-sky!
  "Sky colours and light for the hour now; fog distances for a world of radius r."
  [^js sky r]
  (let [hour (hour-now)
        [z h] (plan/sky hour)
        light (daylight hour)]
    (.setHex ^js (.-value (.-zenith sky)) z)
    (.setHex ^js (.-value (.-horizon sky)) h)
    (set! (.-value (.-near sky)) (* 2.2 r))
    (set! (.-value (.-far sky)) (* 6 r))
    (set! (.-intensity ^js (.-hemi sky)) (* 1.7 light))
    (set! (.-intensity ^js (.-sun sky)) (* 2.2 light))))

;; ---- clouds ----

(def ^:private puffs-per-cloud 5)
(def ^:private cloud-count 9)

(defn make-clouds! [^js scene]
  (let [m (THREE/InstancedMesh. (THREE/IcosahedronGeometry. 1 1)
                                (THREE/MeshLambertNodeMaterial. #js {:color 0xffffff :flatShading true})
                                (* puffs-per-cloud cloud-count))]
    (set! (.-frustumCulled m) false)
    (.add scene m)
    m))

(defn cloud-puff
  "Puff j of cloud i at time t in a world of radius r: [x y z scale]. Clouds drift along x and wrap:
  most in a sea below the islands, every third one high up behind them (never between the view and
  the world)."
  [i j t r]
  (let [h (plan/hash01 (str "cloud" i)) span (* 4 r)
        high? (zero? (mod i 3))
        x (- (mod (+ (* span h) (* t (+ 0.25 (* 0.3 h)))) span) (/ span 2))
        y (if high? (+ 8 (* 6 h)) (- -9 (* 7 h)))
        z (if high?
            (- (* r (+ 1.3 h)))
            (- (* span (plan/hash01 (str "cloudz" i))) (/ span 2)))
        k (plan/hash01 (str "puff" i "/" j))]
    [(+ x (* 1.6 (- j 2))) (+ y (* 0.6 k)) (+ z (* 0.8 (- k 0.5))) (+ 1.1 (* 1.2 k (- 3 (js/Math.abs (- j 2)))))]))

(def ^:private scratch #js {:m (THREE/Matrix4.)})

(defn tick-clouds! [^js m t r]
  (dotimes [i cloud-count]
    (dotimes [j puffs-per-cloud]
      (let [[x y z s] (cloud-puff i j t r)
            ^js mx (.-m scratch)]
        (.makeScale mx s (* 0.65 s) s)
        (.setPosition mx x y z)
        (.setMatrixAt m (+ (* i puffs-per-cloud) j) mx))))
  (set! (.. m -instanceMatrix -needsUpdate) true))

;; ---- the lighthouse beam ----

(defn make-beam! [^js scene]
  (let [g (doto (THREE/ConeGeometry. 1.6 22 20 1 true) (.rotateZ (/ js/Math.PI 2)) (.translate -11 0 0))
        m (THREE/Mesh. g (THREE/MeshBasicNodeMaterial. #js {:color 0xfff1b8 :transparent true :opacity 0.07
                                                            :blending THREE/AdditiveBlending
                                                            :depthWrite false :side THREE/DoubleSide}))]
    (.set (.-position m) 0 geo/lamp-height 0)
    (.add scene m)
    m))

(defn tick-beam! [^js m t]
  (set! (.. m -rotation -y) (* 0.6 t)))

;; ---- traffic on the bridges ----

(def ^:private packet-capacity 128)

(defn make-packets! [^js scene]
  (let [m (THREE/InstancedMesh. (THREE/SphereGeometry. 0.13 10 8)
                                (THREE/MeshBasicNodeMaterial. #js {:color 0xe8fbff})
                                packet-capacity)]
    (set! (.-count m) 0)
    (set! (.-frustumCulled m) false)
    (.add scene m)
    m))

(defn packets
  "[bridge-index offset direction] per packet: one per working chat on that bridge's server (at least
  one, so a bridge always shows it is alive), up to six."
  [bridge-servers working-by-server]
  (vec (for [[i s] (map-indexed vector bridge-servers)
             :let [n (min 6 (inc (get working-by-server s 0)))]
             j (range n)]
         [i (/ j n) (if (even? j) 1 -1)])))

(defn tick-packets!
  "Packets along their bridges' curves at time t."
  [^js m curves packet-list t]
  (let [n (min packet-capacity (count packet-list))
        ^js mx (.-m scratch)
        ^js v (THREE/Vector3.)]
    (dotimes [k n]
      (let [[i off dir] (nth packet-list k)
            u (mod (+ off (* 0.22 t)) 1)]
        (.getPoint ^js (nth curves i) (if (pos? dir) u (- 1 u)) v)
        (.makeTranslation mx (.-x v) (+ 0.18 (.-y v)) (.-z v))
        (.setMatrixAt m k mx)))
    (set! (.-count m) n)
    (set! (.. m -instanceMatrix -needsUpdate) true)))

;; ---- smoke over overworked servers ----

(def ^:private puffs-per-island 7)
(def ^:private smoke-capacity (* 8 puffs-per-island))

(defn make-smoke! [^js scene]
  (let [m (THREE/InstancedMesh. (THREE/IcosahedronGeometry. 1 1)
                                (THREE/MeshLambertNodeMaterial. #js {:color 0xd6d0c8 :flatShading true})
                                smoke-capacity)]
    (set! (.-count m) 0)
    (set! (.-frustumCulled m) false)
    (.add scene m)
    m))

(defn smoke-puff
  "Puff j over an island at [x y z] (seed: the island's id) at time t: [x y z scale]; each rises,
  swells and fades out, then starts again."
  [[x y z] seed j t]
  (let [h (plan/hash01 (str seed "/smoke" j))
        u (mod (+ (* 0.18 t) (/ j puffs-per-island) h) 1)
        a (* 2 js/Math.PI h)]
    [(+ x (* 2.5 (js/Math.cos a)) (* 1.2 u)) (+ y 1.5 (* 9 u)) (+ z (* 2.5 (js/Math.sin a)))
     (* 1.3 (js/Math.sin (* js/Math.PI u)))]))

(defn tick-smoke!
  "Smoke over every island in hot (islands of the plan that are overworked)."
  [^js m hot t]
  (let [^js mx (.-m scratch)
        puffs (for [isl (take 8 hot) j (range puffs-per-island)] (smoke-puff (:pos isl) (:id isl) j t))]
    (doseq [[k [x y z s]] (map-indexed vector puffs)]
      (.makeScale mx s s s)
      (.setPosition mx x y z)
      (.setMatrixAt m k mx))
    (set! (.-count m) (count puffs))
    (set! (.. m -instanceMatrix -needsUpdate) true)))
