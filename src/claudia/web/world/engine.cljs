(ns claudia.web.world.engine
  "The world in three.js: a WebGPURenderer (WebGL 2 where the browser has no WebGPU) with node
  materials in a chalk look (claudia.web.world.chalk), driven by setAnimationLoop. The still world
  (the Blender pieces placed by the plan) is one merged mesh, its outline and the bridges, rebuilt
  only when the servers or projects change; robots, screens, boards, clouds, smoke and packets are
  InstancedMeshes moved every frame. Each server's health (claudia.web.world.health) colours its
  island and fills its board. One running world is a JS object `w`; start! makes it, update! hands it
  the chats, stats! a server's numbers, stop! frees it."
  (:require ["three/webgpu" :as THREE]
            ["three/examples/jsm/controls/MapControls.js" :refer [MapControls]]
            [claudia.web.world.atmosphere :as atmo]
            [claudia.web.world.boards :as boards]
            [claudia.web.world.chalk :as chalk]
            [claudia.web.world.geo :as geo]
            [claudia.web.world.health :as health]
            [claudia.web.world.models :as models]
            [claudia.web.world.motion :as motion]
            [claudia.web.world.plan :as plan]
            [claudia.web.world.robots :as robots]
            [claudia.web.world.screens :as screens]
            [claudia.web.world.signs :as signs]))

;; ---- making it ----

(defn- make-renderer
  "WebGPU where the browser has it, else WebGL 2; force-webgl? after a WebGPU device was lost. Drawn
  at the screen's own sharpness (at most two pixels per CSS pixel)."
  [^js host force-webgl?]
  (let [r (THREE/WebGPURenderer. #js {:antialias true :forceWebGL (boolean force-webgl?)})]
    (.setPixelRatio r (min js/devicePixelRatio 2))
    (.setSize r (max 1 (.-clientWidth host)) (max 1 (.-clientHeight host)))
    (set! (.-toneMapping r) THREE/ACESFilmicToneMapping)
    (.appendChild host (.-domElement r))
    r))

(defn- make-controls
  "Map-style: a drag (one finger) slides the world and it glides on after letting go, the wheel (or a
  pinch) zooms toward the pointer; no turning, the view keeps its angle."
  [^js camera ^js dom]
  (doto (MapControls. camera dom)
    (set! -enableRotate false)
    (set! -enableDamping true)
    (set! -dampingFactor 0.06)
    (set! -zoomToCursor true)
    (set! -minDistance 8)
    (set! -screenSpacePanning false)
    (set! -touches #js {:ONE (.-PAN THREE/TOUCH) :TWO (.-DOLLY_PAN THREE/TOUCH)})))

(defn- make-still
  "The still world's meshes (empty until the plan comes): lit pieces, their chalk outline (the same
  geometry), the glowing bridges, and invisible cylinders to click islands by."
  [^js scene ^js u]
  (let [lit (THREE/Mesh. (THREE/BufferGeometry.) (chalk/world-material u))
        outline (THREE/Mesh. (THREE/BufferGeometry.) (chalk/outline-material (.-width u)))
        glow (THREE/Mesh. (THREE/BufferGeometry.) (THREE/MeshBasicNodeMaterial. #js {:vertexColors true}))
        colliders (THREE/Group.)]
    (doseq [^js m [lit outline glow]] (set! (.-visible m) false) (.add scene m))
    (.add scene colliders)
    #js {:lit lit :outline outline :glow glow :colliders colliders}))

(defn- make-world [^js host ^js renderer handlers pieces]
  (let [scene (THREE/Scene.)
        camera (THREE/PerspectiveCamera. 38 (/ (max 1 (.-clientWidth host)) (max 1 (.-clientHeight host))) 2 1500)
        u (chalk/make-uniforms)]
    #js {:host host :renderer renderer :scene scene :camera camera :models pieces :uniforms u
         :controls (make-controls camera (.-domElement renderer))
         :sky (atmo/make-sky! scene) :clouds (atmo/make-clouds! scene) :beam (atmo/make-beam! scene)
         :packets (atmo/make-packets! scene) :smoke (atmo/make-smoke! scene)
         :robots (robots/make scene (first (:robot pieces)) (.-width u)) :screens (screens/make scene)
         :boards (boards/make scene) :signs (signs/make scene) :still (make-still scene u)
         :handlers handlers :plan nil :robotList [] :stats (js/Map.) :hot [] :packetList [] :curves []
         :radius 30 :last nil :coveredAt nil :focus nil :inset 0 :offset 0 :pointer nil :down nil
         :raycaster (THREE/Raycaster.)}))

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
  (.set (.. w -camera -position) 0 (* 0.95 r) (* 1.75 r))
  (.set (.. w -controls -target) 0 2 (* 0.1 r))
  (set! (.. w -controls -maxDistance) (* 4.5 r))
  (.update (.-controls w)))

(defn- rebuild-still!
  "A new still world (servers or projects changed)."
  [^js w p]
  (let [{:keys [lit outline glow]} (geo/still-world p (.-models w))
        ^js still (.-still w)
        r (world-radius (:islands p))
        first? (nil? (.-plan w))]
    (doseq [^js m [(.-lit still) (.-outline still) (.-glow still)]] (.dispose (.-geometry m)))
    (set! (.. still -lit -geometry) lit)
    (set! (.. still -outline -geometry) outline)
    (set! (.. still -glow -geometry) glow)
    (doseq [^js m [(.-lit still) (.-outline still) (.-glow still)]] (set! (.-visible m) true))
    (replace-colliders! (.-colliders still) (:islands p))
    (set! (.-curves w) (mapv geo/bridge-curve (:bridges p)))
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

(defn- working-by-server [robot-list]
  (frequencies (keep #(when (= :working (:state %)) (first (:key %))) robot-list)))

(defn- info-of
  "An island's {:status :load} from its server's last numbers (the gate without numbers: the hub,
  up since this page loaded)."
  [^js w id]
  (if-let [{:keys [sample error at]} (.get ^js (.-stats w) id)]
    {:status (health/status {:sample sample :error error :age (- (js/Date.now) (or at 0))})
     :load (health/load-of sample)}
    {:status (if (= id "gate") :free :asking) :load nil}))

(defn- set-at! [^js arr i f]
  (when (< i (alength arr)) (f (aget arr i))))

(defn- paint-islands!
  "Each island's grass and greyness from its server's health; the overworked ones smoke."
  [^js w islands infos]
  (let [^js u (.-uniforms w)]
    (doseq [[i {:keys [status]}] (map-indexed vector infos)]
      (set-at! (.. u -grass -array) i #(.setHex ^js % (health/grass status)))
      (when (< i chalk/max-islands) (aset (.. u -dead -array) i (health/dead status))))
    (set! (.-hot w) (vec (keep (fn [[isl info]] (when (= :overworked (:status info)) isl)) (map vector islands infos))))))

(defn- light-windows!
  "Windows lit in buildings where a chat works."
  [^js w buildings working]
  (let [^js lit (.. w -uniforms -lit -array)]
    (doseq [[j b] (map-indexed vector (take chalk/max-buildings buildings))]
      (aset lit j (if (contains? working (:id b)) 1 0)))))

(defn- apply-health!
  "Islands, boards and the bridges' traffic from the servers' health and the robots."
  [^js w]
  (let [islands (:islands (.-plan w))
        infos (mapv #(info-of w (:id %)) islands)
        dead (set (keep (fn [[isl info]] (when (= :dead (:status info)) (:id isl))) (map vector islands infos)))
        robot-list (.-robotList w)]
    (paint-islands! w islands infos)
    (boards/sync! (.-boards w) islands #(info-of w %))
    (set! (.-packetList w) (atmo/packets (vec (remove dead (map :to (:bridges (.-plan w)))))
                                         (working-by-server robot-list)))))

(defn update!
  "Hands the world what to show: the servers (/hub/me) and every chat's row (with :server)."
  [^js w servers rows]
  (let [p (plan/world servers rows)
        robot-list (plan/robots p rows)]
    (when (not= p (.-plan w)) (rebuild-still! w p))
    (let [colour-of (chat-colours)]
      (robots/sync! (.-robots w) robot-list colour-of)
      (screens/sync! (.-screens w) robot-list colour-of))
    (set! (.-robotList w) robot-list)
    (light-windows! w (:buildings p) (plan/working-buildings robot-list))
    (signs/sync! (.-signs w) (:buildings p) (plan/working-buildings robot-list))
    (apply-health! w)))

(defn stats!
  "A server's (or the gate's) latest numbers: {:sample} on an answer, {:error} when it failed."
  [^js w id {:keys [sample error]}]
  (let [^js m (.-stats w)
        old (.get m id)]
    (.set m id (if sample {:sample sample :at (js/Date.now)} (assoc old :error error)))
    (when (.-plan w) (apply-health! w))))

;; ---- every frame ----

(defn- follow-focus!
  "While a robot's chat is open, the view's centre glides to it."
  [^js w dt]
  (when-let [[x y z] (some->> (.-focus w) (robots/position (.-robots w)))]
    (let [^js tg (.. w -controls -target)]
      (.set tg (motion/damp (.-x tg) x 2.5 dt) (motion/damp (.-y tg) (- y 0.6) 2.5 dt) (motion/damp (.-z tg) z 2.5 dt)))))

(defn screen-rect
  "Where robot id's screen is on the page (px), or nil: the chat panel flies out of it and back in."
  [^js w id]
  (screens/rect (.-screens w) (.-robots w) id (.-camera w) (.getBoundingClientRect (.. w -renderer -domElement))))

(defn board-rect
  "Where island id's board is on the page (px), or nil: its Systems card flies out of it and back."
  [^js w id]
  (boards/rect (.-boards w) id (.-camera w) (.getBoundingClientRect (.. w -renderer -domElement))))

(defn screen-image
  "A copy of robot id's screen as a canvas (the picture that flies before the chat is drawn), or nil."
  [^js w id]
  (screens/snapshot (.-screens w) id))

(defn board-image
  "A copy of island id's board as a canvas, or nil."
  [^js w id]
  (boards/snapshot (.-boards w) id))

(defn- pick-at
  "What is under the pointer: {:robot r :rect} (a robot or its screen), {:island id :kind}, or nil
  (the sky)."
  [^js w [px py]]
  (let [^js rc (.-raycaster w)
        ^js dom (.. w -renderer -domElement)
        rect (.getBoundingClientRect dom)]
    (.setFromCamera rc (THREE/Vector2. (dec (* 2 (/ (- px (.-left rect)) (.-width rect))))
                                       (- 1 (* 2 (/ (- py (.-top rect)) (.-height rect)))))
                    (.-camera w))
    (if-let [r (or (screens/pick (.-screens w) rc) (robots/pick (.-robots w) rc))]
      {:robot r :rect (screen-rect w (:id r))}
      (if-let [isl (boards/pick (.-boards w) rc)]
        {:board (:id isl) :rect (board-rect w (:id isl))}
        (when-let [^js hit (first (.intersectObjects rc (.. w -still -colliders -children) false))]
          {:island (.. hit -object -userData -island) :kind (.. hit -object -userData -kind)})))))

(defn- hover! [^js w]
  (when-let [pt (.-pointer w)]
    (set! (.-pointer w) nil)
    (let [id (some-> (pick-at w pt) :robot :id)]
      (set! (.. w -renderer -domElement -style -cursor) (if id "pointer" ""))
      (set! (.. w -screens -hovered) id))))

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

(def ^:private covered-fps
  "How often the world is drawn while a panel covers most of it (after the view's glide)."
  12)

(defn- skip-frame?
  "While a panel has covered the world for more than 1.5 s, only covered-fps frames a second are
  drawn: the panel, not the world, is what is being looked at."
  [^js w t]
  (let [since (.-coveredAt w)]
    (and since (> (- t since) 1.5) (< (- t (or (.-last w) 0)) (/ 1 covered-fps)))))

(defn- keep-outline!
  "The chalk outline stays outline-px wide on screen at the view's distance."
  [^js w]
  (let [^js cam (.-camera w)]
    (set! (.. w -uniforms -width -value)
          (chalk/outline-width (.distanceTo (.-position cam) (.. w -controls -target)) (.-fov cam)
                               (.. w -host -clientHeight)))))

(defn- draw-frame! [^js w t]
  (let [dt (if (.-last w) (min motion/max-dt (- t (.-last w))) 0)
        ^js host (.-host w)]
    (set! (.-last w) t)
    (hover! w)
    (robots/frame! (.-robots w) t dt (.getAzimuthalAngle (.-controls w)))
    (screens/frame! (.-screens w) (.-robots w) (.-camera w) (.-clientHeight host) (.getAzimuthalAngle (.-controls w)) dt)
    (atmo/tick-clouds! (.-clouds w) t (.-radius w))
    (atmo/tick-beam! (.-beam w) t)
    (atmo/tick-packets! (.-packets w) (.-curves w) (.-packetList w) t)
    (atmo/tick-smoke! (.-smoke w) (.-hot w) t)
    (boards/frame! (.-boards w) (.-camera w))
    (signs/frame! (.-signs w) (.-camera w))
    (follow-focus! w dt)
    (shift-view! w dt)
    (.update (.-controls w))
    (keep-outline! w)
    (.render (.-renderer w) (.-scene w) (.-camera w))))

(defn- frame! [^js w ms]
  (when-not (skip-frame? w (/ ms 1000))
    (draw-frame! w (/ ms 1000))))

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
                             ((:on-pick (.-handlers w)) (pick-at w (xy e)))))))))

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
    (-> (js/Promise.all #js [(.init renderer) (models/load!)])
        (.then (fn [[_ pieces]]
                 (let [w (make-world host renderer handlers pieces)]
                   (on-pointer! w)
                   (on-resize! w)
                   (.setAnimationLoop renderer #(frame! w %))
                   w))))))

(defn panel!
  "A panel over the world's right side: inset px wide (0: none); follow: the robot whose chat it
  shows (the view glides to it, its screen hidden: it flew out), or nil (the Systems card). While
  covered, the world is drawn at covered-fps."
  [^js w {:keys [follow inset]}]
  (set! (.-focus w) follow)
  (set! (.. w -screens -hidden) follow)
  (set! (.-inset w) (or inset 0))
  (set! (.-coveredAt w) (when (pos? (or inset 0)) (or (.-coveredAt w) (.-last w)))))

(defn stop! [^js w]
  (.setAnimationLoop (.-renderer w) nil)
  (some-> ^js (.-ro w) (.disconnect))
  (.dispose (.-controls w))
  (.dispose (.-renderer w))
  (.remove (.. w -renderer -domElement)))
