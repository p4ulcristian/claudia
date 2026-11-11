(ns claudia.web.world.engine
  "The world in three.js: a WebGPURenderer (WebGL 2 where the browser has no WebGPU) with node
  materials, driven by setAnimationLoop. The still world is two merged meshes rebuilt only when the
  servers or projects change; robots, clouds, packets and windows are InstancedMeshes moved every
  frame. One running world is a JS object `w`; start! makes it, update! hands it the plan, stop!
  frees it."
  (:require ["three/webgpu" :as THREE]
            ["three/examples/jsm/controls/OrbitControls.js" :refer [OrbitControls]]
            [claudia.web.world.atmosphere :as atmo]
            [claudia.web.world.geo :as geo]
            [claudia.web.world.labels :as labels]
            [claudia.web.world.motion :as motion]
            [claudia.web.world.plan :as plan]
            [claudia.web.world.robots :as robots]))

;; ---- making it ----

(defn- make-renderer
  "WebGPU where the browser has it, else WebGL 2; force-webgl? after a WebGPU device was lost."
  [^js host force-webgl?]
  (let [r (THREE/WebGPURenderer. #js {:antialias true :forceWebGL (boolean force-webgl?)})]
    (.setPixelRatio r (min js/devicePixelRatio 1.5))
    (.setSize r (max 1 (.-clientWidth host)) (max 1 (.-clientHeight host)))
    (set! (.-toneMapping r) THREE/ACESFilmicToneMapping)
    (.appendChild host (.-domElement r))
    r))

(defn- make-controls [^js camera ^js dom]
  (doto (OrbitControls. camera dom)
    (set! -enableDamping true)
    (set! -dampingFactor 0.08)
    (set! -maxPolarAngle 1.32)
    (set! -minDistance 5)
    (set! -screenSpacePanning false)))

(defn- make-still [^js scene]
  (let [lit (THREE/Mesh. (THREE/BufferGeometry.) (THREE/MeshLambertNodeMaterial. #js {:vertexColors true :flatShading true}))
        glow (THREE/Mesh. (THREE/BufferGeometry.) (THREE/MeshBasicNodeMaterial. #js {:vertexColors true}))
        colliders (THREE/Group.)]
    (set! (.-visible lit) false) (set! (.-visible glow) false)
    (.add scene lit) (.add scene glow) (.add scene colliders)
    #js {:lit lit :glow glow :colliders colliders}))

(defn- make-world [^js host ^js renderer handlers]
  (let [scene (THREE/Scene.)
        camera (THREE/PerspectiveCamera. 38 (/ (max 1 (.-clientWidth host)) (max 1 (.-clientHeight host))) 0.5 2000)]
    #js {:host host :renderer renderer :scene scene :camera camera
         :controls (make-controls camera (.-domElement renderer))
         :sky (atmo/make-sky! scene) :clouds (atmo/make-clouds! scene) :beam (atmo/make-beam! scene)
         :packets (atmo/make-packets! scene) :windows (atmo/make-windows! scene)
         :robots (robots/make scene) :still (make-still scene) :layer (labels/make-layer host)
         :handlers handlers :plan nil :robot-list [] :packet-list [] :curves [] :window-ids []
         :radius 30 :last nil :focus nil :inset 0 :offset 0 :hovered nil :pointer nil :down nil
         :raycaster (THREE/Raycaster.) :v (THREE/Vector3.)}))

;; ---- the plan ----

(defn- world-radius [islands]
  (reduce max 10 (map (fn [{:keys [pos radius]}] (+ radius (js/Math.hypot (first pos) (last pos)))) islands)))

(defn- collider [{:keys [id pos radius kind]}]
  (let [m (THREE/Mesh. (THREE/CylinderGeometry. radius radius 1.4 12))]
    (.set (.-position m) (first pos) (- (second pos) 0.4) (last pos))
    (set! (.-visible m) false)
    (set! (.-userData m) #js {:island id :kind (name kind)})
    m))

(defn- replace-colliders! [^js group islands]
  (doseq [^js c (vec (.-children group))]
    (.remove group c) (.dispose (.-geometry c)))
  (doseq [i islands] (.add group (collider i))))

(defn- frame-camera!
  "The first view: the whole world from above and in front."
  [^js w r]
  (.set (.. w -camera -position) 0 (* 1.05 r) (* 1.95 r))
  (.set (.. w -controls -target) 0 -1 (* 0.1 r))
  (set! (.. w -controls -maxDistance) (* 4.5 r))
  (.update (.-controls w)))

(defn- rebuild-still!
  "A new still world (servers or projects changed)."
  [^js w p]
  (let [{:keys [lit glow]} (geo/still-world p)
        ^js still (.-still w)
        r (world-radius (:islands p))
        first? (nil? (.-plan w))]
    (.dispose (.. still -lit -geometry)) (.dispose (.. still -glow -geometry))
    (set! (.. still -lit -geometry) lit)
    (set! (.. still -glow -geometry) glow)
    (set! (.. still -lit -visible) true) (set! (.. still -glow -visible) true)
    (replace-colliders! (.-colliders still) (:islands p))
    (set! (.-curves w) (mapv geo/bridge-curve (:bridges p)))
    (set! (.-window-ids w) (atmo/place-windows! (.-windows w) (geo/window-matrices (:buildings p))))
    (set! (.-radius w) r)
    (set! (.-plan w) p)
    (atmo/tune-sky! (.-sky w) r)
    (when first? (frame-camera! w r))))

(defn- chat-colours
  "Chat colour name -> THREE.Color, read from the page's own --c-<name> variables."
  []
  (let [style (js/getComputedStyle js/document.documentElement)]
    (memoize (fn [c] (let [v (.trim (.getPropertyValue style (str "--c-" c)))]
                       (THREE/Color. (if (seq v) v "#9aa0a8")))))))

(defn- label-items [^js w robot-list]
  (concat
   (for [{:keys [id label kind]} (:islands (.-plan w))]
     {:id (str "i:" id) :text label :kind kind})
   (for [{:keys [id title state color]} robot-list
         :when (or (= :waiting state) (= id (.-hovered w)) (= id (.-focus w)))]
     {:id id :text title :kind :robot :color (str "var(--c-" color ")")})))

(defn- working-by-server [robot-list]
  (frequencies (keep #(when (= :working (:state %)) (first (:key %))) robot-list)))

(defn update!
  "Hands the world what to show: the servers (/hub/me) and every chat's row (with :server)."
  [^js w servers rows]
  (let [p (plan/world servers rows)
        robot-list (plan/robots p rows)]
    (when (not= p (.-plan w)) (rebuild-still! w p))
    (robots/sync! (.-robots w) robot-list (chat-colours))
    (atmo/light-windows! (.-windows w) (.-window-ids w) (plan/working-buildings robot-list))
    (set! (.-packet-list w) (atmo/packets (mapv :to (:bridges p)) (working-by-server robot-list)))
    (set! (.-robot-list w) robot-list)
    (labels/sync! (.-layer w) (label-items w robot-list))))

;; ---- every frame ----

(defn- island-point [^js w id]
  (when-let [{:keys [pos kind]} (first (filter #(= id (:id %)) (:islands (.-plan w))))]
    (let [[x y z] pos] [x (+ y (if (= kind :gate) 6.3 3.6)) z])))

(defn- label-point [^js w id]
  (if (.startsWith id "i:")
    (island-point w (subs id 2))
    (when-let [[x y z] (robots/position (.-robots w) id)] [x (+ y 0.95) z])))

(defn- follow-focus!
  "While a robot's chat is open, the view's centre glides to it."
  [^js w dt]
  (when-let [[x y z] (some->> (.-focus w) (robots/position (.-robots w)))]
    (let [^js tg (.. w -controls -target)]
      (.set tg (motion/damp (.-x tg) x 2.5 dt) (motion/damp (.-y tg) (- y 0.6) 2.5 dt) (motion/damp (.-z tg) z 2.5 dt)))))

(defn- pick-at
  "What is under the pointer: {:robot r} or {:island id}, or nil."
  [^js w [px py]]
  (let [^js rc (.-raycaster w)
        ^js dom (.. w -renderer -domElement)
        rect (.getBoundingClientRect dom)]
    (.setFromCamera rc (THREE/Vector2. (dec (* 2 (/ (- px (.-left rect)) (.-width rect))))
                                       (- 1 (* 2 (/ (- py (.-top rect)) (.-height rect)))))
                    (.-camera w))
    (if-let [r (robots/pick (.-robots w) rc)]
      {:robot r}
      (when-let [^js hit (first (.intersectObjects rc (.. w -still -colliders -children) false))]
        {:island (.. hit -object -userData -island) :kind (.. hit -object -userData -kind)}))))

(defn- hover! [^js w]
  (when-let [pt (.-pointer w)]
    (set! (.-pointer w) nil)
    (let [id (some-> (pick-at w pt) :robot :id)]
      (set! (.. w -renderer -domElement -style -cursor) (if id "pointer" ""))
      (when (not= id (.-hovered w))
        (set! (.-hovered w) id)
        (labels/sync! (.-layer w) (label-items w (.-robot-list w)))))))

(defn- shift-view!
  "Moves the picture left by half the panel's width (gliding), so the followed robot stays in the
  part of the window the panel leaves free."
  [^js w dt]
  (let [^js cam (.-camera w)
        ^js host (.-host w)
        off (motion/damp (.-offset w) (/ (.-inset w) 2) 4 dt)]
    (set! (.-offset w) off)
    (if (< off 0.5)
      (.clearViewOffset cam)
      (let [cw (max 1 (.-clientWidth host)) ch (max 1 (.-clientHeight host))]
        (.setViewOffset cam cw ch off 0 cw ch)))))

(defn- frame! [^js w ms]
  (let [t (/ ms 1000)
        dt (if (.-last w) (min motion/max-dt (- t (.-last w))) 0)
        ^js host (.-host w)]
    (set! (.-last w) t)
    (hover! w)
    (robots/frame! (.-robots w) t dt (.getAzimuthalAngle (.-controls w)))
    (atmo/tick-clouds! (.-clouds w) t (.-radius w))
    (atmo/tick-beam! (.-beam w) t)
    (atmo/tick-packets! (.-packets w) (.-curves w) (.-packet-list w) t)
    (follow-focus! w dt)
    (shift-view! w dt)
    (.update (.-controls w))
    (.render (.-renderer w) (.-scene w) (.-camera w))
    (labels/place! (.-layer w) (.-camera w) (.-clientWidth host) (.-clientHeight host)
                   #(label-point w %) (.-v w))))

;; ---- pointer, size, life ----

(defn- on-pointer! [^js w]
  (let [dom (.. w -renderer -domElement)
        xy (fn [^js e] [(.-clientX e) (.-clientY e)])]
    (.addEventListener dom "pointermove" #(set! (.-pointer w) (xy %)))
    (.addEventListener dom "pointerdown" #(set! (.-down w) (xy %)))
    (.addEventListener dom "pointerup"
                       (fn [^js e]
                         (let [[dx dy] (map - (xy e) (or (.-down w) [-99 -99]))]
                           (when (< (js/Math.hypot dx dy) 6)
                             (when-let [p (pick-at w (xy e))] ((:on-pick (.-handlers w)) p))))))))

(defn- on-resize! [^js w]
  (let [ro (js/ResizeObserver.
            (fn []
              (let [^js host (.-host w) cw (max 1 (.-clientWidth host)) ch (max 1 (.-clientHeight host))]
                (.setSize (.-renderer w) cw ch)
                (set! (.. w -camera -aspect) (/ cw ch))
                (.updateProjectionMatrix (.-camera w)))))]
    (.observe ro (.-host w))
    (set! (.-ro w) ro)))

(defn start!
  "A world in host; resolves to it once the renderer is ready. handlers: {:on-pick fn, :on-lost fn
  (the WebGPU device was lost: start again with :force-webgl?)}."
  [^js host {:keys [force-webgl? on-lost] :as handlers}]
  (let [renderer (make-renderer host force-webgl?)]
    (set! (.-onDeviceLost renderer) (fn [_] (on-lost)))
    (-> (.init renderer)
        (.then (fn []
                 (let [w (make-world host renderer handlers)]
                   (on-pointer! w)
                   (on-resize! w)
                   (.setAnimationLoop renderer #(frame! w %))
                   w))))))

(defn focus!
  "The robot the view follows (its chat is open, its name shown), or nil; inset: the width in px the
  chat panel covers on the right."
  [^js w id inset]
  (set! (.-focus w) id)
  (set! (.-inset w) (if id inset 0))
  (labels/sync! (.-layer w) (label-items w (.-robot-list w))))

(defn stop! [^js w]
  (.setAnimationLoop (.-renderer w) nil)
  (some-> ^js (.-ro w) (.disconnect))
  (.dispose (.-controls w))
  (.dispose (.-renderer w))
  (.remove (.. w -renderer -domElement))
  (.remove (.-layer w)))
