(ns claudia.web.world.robots
  "The robots, one per chat, drawn instanced: each part (body, visor, eyes, antenna, jet, shadow,
  the waiting mark) is one InstancedMesh for every robot, so a hundred robots cost as much as one.
  Each robot keeps its own motion (claudia.web.world.motion) in a JS object; every frame writes the
  parts' matrices once."
  (:require ["three/webgpu" :as THREE]
            ["three/tsl" :as tsl]
            ["three/examples/jsm/utils/BufferGeometryUtils.js" :as bgu]
            [claudia.web.world.motion :as motion]
            [claudia.web.world.plan :as plan]))

(def capacity 512)

(def size
  "How much bigger a robot is than its parts as written (a robot is then about 1.6 tall)."
  1.5)

(defn- toon [opts] (THREE/MeshToonNodeMaterial. (clj->js opts)))
(defn- basic [opts] (THREE/MeshBasicNodeMaterial. (clj->js opts)))

(defn- shadow-material
  "A soft dark disc, drawn by its uv (no texture)."
  []
  (doto (basic {:transparent true :depthWrite false})
    (set! -colorNode (tsl/color 0x101418))
    (set! -opacityNode (.mul (tsl/smoothstep 0.5 0.1 (.distance (tsl/uv) (tsl/vec2 0.5))) 0.38))))

(defn- mark-geometry
  "The waiting mark: an exclamation point."
  []
  (bgu/mergeGeometries #js [(.translate (THREE/BoxGeometry. 0.1 0.32 0.1) 0 0.13 0)
                            (.translate (THREE/BoxGeometry. 0.1 0.1 0.1) 0 -0.16 0)]))

(defn- local [x y z & [rx]]
  (.compose (THREE/Matrix4.) (THREE/Vector3. x y z)
            (.setFromEuler (THREE/Quaternion.) (THREE/Euler. (or rx 0) 0 0))
            (THREE/Vector3. 1 1 1)))

(defn- part-specs
  "Each part: its geometry, material, where it sits on the robot, whether it takes a colour per robot."
  []
  [{:id :body :geo (THREE/CapsuleGeometry. 0.3 0.32 6 16) :mat (toon {}) :at (local 0 0 0) :tint true}
   {:id :visor :geo (THREE/BoxGeometry. 0.44 0.25 0.12) :mat (toon {:color 0x161a24}) :at (local 0 0.13 0.23)}
   {:id :eyes :geo (bgu/mergeGeometries #js [(.translate (THREE/BoxGeometry. 0.075 0.095 0.02) -0.095 0 0)
                                             (.translate (THREE/BoxGeometry. 0.075 0.095 0.02) 0.095 0 0)])
    :mat (basic {}) :at (local 0 0.135 0.295) :tint true}
   {:id :stem :geo (THREE/CylinderGeometry. 0.016 0.016 0.24 5) :mat (toon {:color 0x8d939e}) :at (local 0 0.55 0)}
   {:id :tip :geo (THREE/SphereGeometry. 0.065 12 8) :mat (basic {}) :at (local 0 0.69 0) :tint true}
   {:id :jet :geo (THREE/ConeGeometry. 0.13 0.26 12 1 true) :mat (basic {:color 0x9fdcff :transparent true :opacity 0.8})
    :at (local 0 -0.52 0 js/Math.PI)}])

(defn- instanced
  "An InstancedMesh for every robot; tint? gives it a colour per robot (set before the first draw, so
  its shader has them)."
  [^js geo ^js mat tint?]
  (let [m (THREE/InstancedMesh. geo mat capacity)]
    (set! (.-count m) 0)
    (set! (.-frustumCulled m) false)
    (when tint? (.setColorAt m 0 (THREE/Color. 0xffffff)))
    m))

(defn make
  "Every part's InstancedMesh added to scene; the robots' runtime (a JS object the frame loop moves)."
  [^js scene]
  (let [specs (part-specs)
        meshes (into {} (map (fn [s] [(:id s) (instanced (:geo s) (:mat s) (:tint s))]) specs))
        shadow (instanced (doto (THREE/PlaneGeometry. 1.1 1.1) (.rotateX (- (/ js/Math.PI 2)))) (shadow-material) false)
        mark (instanced (mark-geometry) (basic {:color 0xffc23d}) false)]
    (doseq [^js m (concat (vals meshes) [shadow mark])] (.add scene m))
    #js {:specs specs :meshes meshes :shadow shadow :mark mark
         :list #js [] :states (js/Map.) :seeded false}))

(defn- eye-colour [state]
  (case state :working 0x8ff3ff :waiting 0xffd36b :done 0x8dffb0 :off 0x2b3038 0xd9f6ff))

(defn- fresh-state
  "A robot's motion state, at spot, or coming out of the gate (new after the first load)."
  [{:keys [spot]} from-gate? phase]
  (let [[x _ z] (if from-gate? [0 0 0] spot)]
    #js {:x x :z z :y (if from-gate? 4.4 1) :vx 0 :vz 0 :yaw 0 :phase phase}))

(defn- paint-robot!
  "Robot i's per-robot colours: body and antenna tip in its chat colour, eyes by what it does."
  [^js rt i r ^js body-colour]
  (let [ms (.-meshes rt)
        off? (= :off (:state r))
        c (if off? (.lerp (.clone body-colour) (THREE/Color. 0x5c6066) 0.75) body-colour)]
    (.setColorAt ^js (:body ms) i c)
    (.setColorAt ^js (:tip ms) i (if (= :working (:state r)) (.offsetHSL (.clone c) 0 0 0.12) c))
    (.setColorAt ^js (:eyes ms) i (THREE/Color. (eye-colour (:state r))))))

(defn sync!
  "The robots to show now (claudia.web.world.plan/robots): states kept by id, new ones made (out of the
  gate once the world has been seen), colours painted. colour-of: chat colour name -> THREE.Color."
  [^js rt robot-list colour-of]
  (let [states ^js (.-states rt)
        seeded? (.-seeded rt)]
    (doseq [[i r] (map-indexed vector robot-list)]
      (when-not (.has states (:id r))
        (.set states (:id r) (fresh-state r seeded? (* 2 js/Math.PI (plan/hash01 (:id r))))))
      (paint-robot! rt i r (colour-of (:color r))))
    (doseq [id (js/Array.from (.keys states))]
      (when-not (some #(= id (:id %)) robot-list) (.delete states id)))
    (doseq [^js m (concat (vals (.-meshes rt)) [(.-shadow rt) (.-mark rt)])]
      (set! (.-count m) (count robot-list))
      (some-> (.-instanceColor m) (set! -needsUpdate true)))
    (set! (.-list rt) (to-array robot-list))
    (set! (.-seeded rt) true)))

(defn- target-of
  "Where a robot heads: its spot, drifting round it while idle or done."
  [r ^js s t]
  (let [[x _ z] (:spot r)]
    (if (#{:idle :done} (:state r))
      (let [[dx dz] (motion/wander t (.-phase s) 0.7)] [(+ x dx) (+ z dz)])
      [x z])))

(defn- step-axis! [^js s t dt r]
  (let [[tx tz] (target-of r s t)
        [vx vz] (motion/cap-speed (motion/spring-velocity (.-x s) (.-vx s) tx dt)
                                  (motion/spring-velocity (.-z s) (.-vz s) tz dt))]
    (set! (.-vx s) vx) (set! (.-vz s) vz)
    (set! (.-x s) (+ (.-x s) (* vx dt))) (set! (.-z s) (+ (.-z s) (* vz dt)))))

(defn- rest-yaw
  "Facing while still: toward its building when working, else in toward its island's middle."
  [r]
  (let [a (:out r)]
    (if (= :working (:state r))
      (js/Math.atan2 (js/Math.cos a) (js/Math.sin a))
      (js/Math.atan2 (- (js/Math.cos a)) (- (js/Math.sin a))))))

(defn- step!
  "One robot's motion for this frame; returns [pitch roll] from how it leans."
  [^js s r t dt]
  (step-axis! s t dt r)
  (let [yaw0 (.-yaw s)
        yaw (motion/damp-angle yaw0 (motion/heading (.-vx s) (.-vz s) (rest-yaw r)) 5 dt)
        hover (motion/hover (:state r) t (.-phase s))]
    (set! (.-yaw s) yaw)
    (set! (.-y s) (motion/damp (.-y s) (+ (:ground r) hover) 3 dt))
    (motion/lean (js/Math.hypot (.-vx s) (.-vz s)) (/ (motion/wrap-angle (- yaw yaw0)) (max dt 1e-4)))))

(def ^:private scratch
  #js {:m (THREE/Matrix4.) :p (THREE/Matrix4.) :q (THREE/Quaternion.) :e (THREE/Euler. 0 0 0 "YXZ")
       :v (THREE/Vector3.) :s (THREE/Vector3. 1 1 1) :k (THREE/Matrix4.)})

(defn- body-matrix ^js [^js s [pitch roll]]
  (let [sc scratch]
    (.set ^js (.-e sc) pitch (.-yaw s) roll "YXZ")
    (.compose ^js (.-m sc) (.set ^js (.-v sc) (.-x s) (.-y s) (.-z s))
              (.setFromEuler ^js (.-q sc) (.-e sc)) (.set ^js (.-s sc) size size size))))

(defn- write-parts! [^js rt i ^js body r t phase]
  (doseq [{:keys [id at]} (.-specs rt)
          :let [^js p (.multiplyMatrices ^js (.-p scratch) body at)]]
    (case id
      :eyes (.multiply p (.makeScale ^js (.-k scratch) 1 (if (= :off (:state r)) 0.2 (motion/blink t phase)) 1))
      :jet (.multiply p (.makeScale ^js (.-k scratch) 1 (+ 0.8 (* 0.35 (js/Math.sin (+ (* 31 t) phase)))) 1))
      nil)
    (.setMatrixAt ^js (get (.-meshes rt) id) i p)))

(defn- write-shadow-and-mark! [^js rt i ^js s r cam-yaw]
  (let [sc scratch
        lift (- (.-y s) (:ground r))
        k (* size (max 0.4 (- 1.25 (* 0.25 lift))))
        waiting? (= :waiting (:state r))]
    (.setMatrixAt ^js (.-shadow rt) i
                  (.compose ^js (.-m sc) (.set ^js (.-v sc) (.-x s) (+ (:ground r) 0.03) (.-z s))
                            (.identity ^js (.-q sc)) (.set ^js (.-s sc) k 1 k)))
    (.setMatrixAt ^js (.-mark rt) i
                  (.compose ^js (.-m sc) (.set ^js (.-v sc) (.-x s) (+ (.-y s) (* 1.05 size)) (.-z s))
                            (.setFromAxisAngle ^js (.-q sc) (THREE/Vector3. 0 1 0) cam-yaw)
                            (let [g (if waiting? size 0)] (.set ^js (.-s sc) g g g))))))

(defn frame!
  "Moves every robot by dt (seconds) at time t and writes its parts; cam-yaw turns the marks to face
  the camera."
  [^js rt t dt cam-yaw]
  (let [states ^js (.-states rt)]
    (dotimes [i (alength (.-list rt))]
      (let [r (aget (.-list rt) i)
            ^js s (.get states (:id r))]
        (write-parts! rt i (body-matrix s (step! s r t dt)) r t (.-phase s))
        (write-shadow-and-mark! rt i s r cam-yaw)))
    (doseq [^js m (concat (vals (.-meshes rt)) [(.-shadow rt) (.-mark rt)])]
      (set! (.. m -instanceMatrix -needsUpdate) true))))

(defn position
  "Where robot id is now, [x y z], or nil."
  [^js rt id]
  (when-let [^js s (.get ^js (.-states rt) id)] [(.-x s) (.-y s) (.-z s)]))

(defn pick
  "The robot under a raycaster, or nil."
  [^js rt ^js raycaster]
  (let [^js body (:body (.-meshes rt))]
    (set! (.-boundingSphere body) nil)
    (when-let [hit (first (.intersectObject raycaster body false))]
      (aget (.-list rt) (.-instanceId hit)))))
