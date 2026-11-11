(ns claudia.web.world.robots
  "The robots, one per chat, drawn instanced: each part of the Blender robot (body, visor, eyes,
  antenna, tip, jet; claudia.web.world.models), the body's chalk outline, the shadow and the waiting
  mark is one InstancedMesh for every robot, so a hundred robots cost as much as one. Each robot keeps
  its own motion (claudia.web.world.motion) in a JS object; every frame writes the parts' matrices
  once (the outline shares the body's)."
  (:require ["three/webgpu" :as THREE]
            ["three/tsl" :as tsl]
            ["three/examples/jsm/utils/BufferGeometryUtils.js" :as bgu]
            [claudia.web.world.chalk :as chalk]
            [claudia.web.world.motion :as motion]
            [claudia.web.world.plan :as plan]))

(def capacity 512)

(def size
  "How much bigger a robot is than in Blender (1.08 tall there, about 1.4 in the world)."
  1.3)

(def tip-height "The antenna tip's height over the robot's centre, in the world." (* 0.66 size))

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

(defn- pivot
  "A scale of (sx sy sz) about point [x y z] of the robot (eyes blink about their middle, the jet
  flickers from its top)."
  [[x y z]]
  {:to (.makeTranslation (THREE/Matrix4.) x y z) :back (.makeTranslation (THREE/Matrix4.) (- x) (- y) (- z))})

(defn- part-specs
  "Each part: its Blender geometry, material, whether it takes a colour per robot, a pivot it scales
  about."
  [parts]
  [{:id :body :geo (parts "body") :mat (chalk/piece-material) :tint true}
   {:id :visor :geo (parts "visor") :mat (chalk/piece-material)}
   {:id :eyes :geo (parts "eyes") :mat (basic {}) :tint true :pivot (pivot [0 0.07 0.3])}
   {:id :antenna :geo (parts "antenna") :mat (chalk/piece-material)}
   {:id :tip :geo (parts "tip") :mat (chalk/piece-material) :tint true}
   {:id :jet :geo (parts "jet") :mat (chalk/glow-material {:transparent true :opacity 0.85 :side THREE/DoubleSide})
    :pivot (pivot [0 -0.41 0])}])

(defn- instanced
  "An InstancedMesh for every robot; tint? gives it a colour per robot (set before the first draw, so
  its shader has them)."
  [^js geo ^js mat tint?]
  (let [m (THREE/InstancedMesh. geo mat capacity)]
    (set! (.-count m) 0)
    (set! (.-frustumCulled m) false)
    (when tint? (.setColorAt m 0 (THREE/Color. 0xffffff)))
    m))

(defn- outline
  "The bodies' chalk outline, moved by the bodies' own matrices (width: a uniform node, world units)."
  [^js body ^js width]
  (let [m (THREE/InstancedMesh. (.-geometry body) (chalk/outline-material (.div width size)) capacity)]
    (set! (.-instanceMatrix m) (.-instanceMatrix body))
    (set! (.-count m) 0)
    (set! (.-frustumCulled m) false)
    m))

(defn make
  "Every part's InstancedMesh added to scene (parts: the robot's geometries by name; width: the
  outline's width node); the robots' runtime (a JS object the frame loop moves)."
  [^js scene parts ^js width]
  (let [specs (part-specs parts)
        meshes (into {} (map (fn [s] [(:id s) (instanced (:geo s) (:mat s) (:tint s))]) specs))
        meshes (assoc meshes :outline (outline (:body meshes) width))
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

(defn- squash
  "How a part scales this frame: the eyes blink (shut while off), the jet flickers."
  [id r t phase]
  (case id
    :eyes (if (= :off (:state r)) 0.2 (motion/blink t phase))
    :jet (+ 0.8 (* 0.35 (js/Math.sin (+ (* 31 t) phase))))
    1))

(defn- write-parts! [^js rt i ^js body r t phase]
  (doseq [{id :id about :pivot} (.-specs rt)
          :let [^js p (.copy ^js (.-p scratch) body)]]
    (when about
      (.multiply p (:to about))
      (.multiply p (.makeScale ^js (.-k scratch) 1 (squash id r t phase) 1))
      (.multiply p (:back about)))
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
