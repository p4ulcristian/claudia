(ns claudia.web.world.screens
  "Each robot's chat as a screen floating over it (a hologram from its antenna): the chat's title in
  its colour, the start of the last reply or the open question, and what it is doing. Every screen
  is drawn once into a slot of one canvas atlas (redrawn only when its text changes, the atlas sent
  to the card at most every 0.4 s) and shown by one InstancedMesh whose node material reads its slot
  by a per-instance offset, so all screens are one draw call. Screens face the camera, move apart so
  none covers another (claudia.web.world.stack, worked out ten times a second); the one whose chat
  is open is drawn bigger with a glowing frame behind it."
  (:require ["three/webgpu" :as THREE]
            ["three/tsl" :as tsl]
            [claudia.web.world.atlas :as atlas]
            [claudia.web.world.motion :as motion]
            [claudia.web.world.robots :as robots]
            [claudia.web.world.screen-text :as st]
            [claudia.web.world.stack :as stack]))

(def ^:private slot-w "A screen's width in drawing units (its pixels: times sharp)." 512)
(def ^:private slot-h 320)
(def sharp "Canvas pixels per drawing unit: text stays crisp up close." 1.5)
(def ^:private cols 4)
(def ^:private rows 8)
(def capacity (* cols rows))
(def width "A screen's size in the world, near the camera (far ones grow: far-scale)." 4.2)
(def height (* width (/ slot-h slot-w)))

(defn- slot-offset
  "The uv offset of slot n in the atlas (rows from the top; the canvas is flipped into the texture)."
  [n]
  [(/ (mod n cols) cols) (- 1 (/ (inc (quot n cols)) rows))])

(defn- screen-material [^js tex ^js slot-attr]
  (let [^js sample (tsl/texture tex (.add (.mul (tsl/uv) (tsl/vec2 (/ 1 cols) (/ 1 rows))) (tsl/instancedBufferAttribute slot-attr)))]
    (doto (THREE/MeshBasicNodeMaterial. #js {:side THREE/DoubleSide})
      (set! -colorNode (.-rgb sample))
      (set! -opacityNode (.-a sample))
      (set! -alphaTest 0.5))))

(defn- make-atlas []
  (let [canvas (js/document.createElement "canvas")]
    (set! (.-width canvas) (* sharp cols slot-w))
    (set! (.-height canvas) (* sharp rows slot-h))
    canvas))

(defn- make-texture [^js canvas]
  (doto (THREE/CanvasTexture. canvas)
    (set! -colorSpace THREE/SRGBColorSpace)
    (set! -anisotropy 8)))

(defn- make-beams
  "A faint beam from each robot's antenna up to its screen, in its chat colour."
  []
  (let [m (THREE/InstancedMesh. (doto (THREE/CylinderGeometry. 0.03 0.12 1 8 1 true) (.translate 0 0.5 0))
                                (THREE/MeshBasicNodeMaterial. #js {:transparent true :opacity 0.35 :depthWrite false
                                                                   :blending THREE/AdditiveBlending})
                                capacity)]
    (set! (.-count m) 0)
    (set! (.-frustumCulled m) false)
    (.setColorAt m 0 (THREE/Color.))
    m))

(defn place-halo!
  "The halo just behind a sign whose matrix is m (nil: none selected)."
  [^js halo ^js m]
  (set! (.-visible halo) (some? m))
  (when m (.multiplyMatrices (.-matrix halo) m (.makeTranslation (THREE/Matrix4.) 0 0 -0.04))))

(defn make
  "The screens' atlas, texture, InstancedMesh and beams, added to scene."
  [^js scene]
  (let [canvas (make-atlas)
        tex (make-texture canvas)
        slot-attr (THREE/InstancedBufferAttribute. (js/Float32Array. (* 2 capacity)) 2)
        mesh (THREE/InstancedMesh. (THREE/PlaneGeometry. width height) (screen-material tex slot-attr) capacity)
        beams (make-beams)
        halo (atlas/make-halo width height)]
    (set! (.-count mesh) 0)
    (set! (.-frustumCulled mesh) false)
    (.add scene mesh) (.add scene beams) (.add scene halo)
    #js {:canvas canvas :ctx (doto (.getContext canvas "2d") (.setTransform sharp 0 0 sharp 0 0)) :tex tex :mesh mesh :beams beams :slotAttr slot-attr
         :slots {} :drawn (js/Map.) :moves (js/Map.) :targets {} :laidOut 0 :list #js [] :dirty false :uploaded 0 :yaw 0
         :selected nil :hovered nil :halo halo}))

;; ---- drawing a screen ----

(def ^:private font "'Atkinson Hyperlegible Next', system-ui, sans-serif")

(defn- frame-path! [^js ctx x y]
  (.beginPath ctx)
  (.roundRect ctx (+ x 6) (+ y 6) (- slot-w 12) (- slot-h 12) 28))

(defn- draw-frame! [^js ctx x y colour]
  (.clearRect ctx x y slot-w slot-h)
  (frame-path! ctx x y)
  (set! (.-fillStyle ctx) "rgba(16,20,30,0.94)")
  (.fill ctx)
  (.save ctx)
  (.clip ctx)
  (set! (.-fillStyle ctx) colour)
  (.fillRect ctx x y slot-w 78)
  (.restore ctx)
  (frame-path! ctx x y)
  (set! (.-lineWidth ctx) 8)
  (set! (.-strokeStyle ctx) colour)
  (.stroke ctx))

(defn- text! [^js ctx s x y size weight colour]
  (set! (.-font ctx) (str weight " " size "px " font))
  (set! (.-fillStyle ctx) colour)
  (.fillText ctx s x y))

(defn- measure [^js ctx size weight]
  (set! (.-font ctx) (str weight " " size "px " font))
  (fn [s] (.-width (.measureText ctx s))))

(defn- title-bar!
  "The chat's title, and on the right the project it works on (iron-rainbow, claudia…)."
  [^js ctx x y inner r]
  (let [project (when (:project r) (first (st/wrap (measure ctx 24 700) 170 1 (:project r))))
        room (if project (- inner (.-width (.measureText ctx project)) 24) inner)]
    (text! ctx (first (st/wrap (measure ctx 34 700) room 1 (or (:title r) "Chat"))) (+ x 30) (+ y 44) 34 700 "#11141c")
    (when project
      (set! (.-textAlign ctx) "right")
      (text! ctx project (+ x 30 inner) (+ y 46) 24 700 "rgba(17,20,28,0.7)")
      (set! (.-textAlign ctx) "start"))))

(defn- draw-slot!
  "Screen n: robot r's chat, in its colour (CSS)."
  [^js ctx n r colour now]
  (let [x (* slot-w (mod n cols)) y (* slot-h (quot n cols))
        inner (- slot-w 60)
        [status status-colour] (st/status r now)]
    (draw-frame! ctx x y colour)
    (set! (.-textBaseline ctx) "middle")
    (title-bar! ctx x y inner r)
    (doseq [[i line] (map-indexed vector (st/wrap (measure ctx 27 400) inner 4 (st/body r)))]
      (text! ctx line (+ x 30) (+ y 116 (* 38 i)) 27 400 "#e6ebf3"))
    (text! ctx status (+ x 30) (+ y 278) 27 700 status-colour)))

;; ---- which robot has which slot ----

(defn- assign-slots!
  "Slots kept for robots still shown, free ones given to new robots (claudia.web.world.screen-text/
  slots); the robots that have a screen."
  [^js rt robot-list]
  (let [slots (st/slots (.-slots rt) (map :id robot-list) capacity)
        ^js drawn (.-drawn rt)]
    (doseq [id (js/Array.from (.keys drawn)) :when (not (contains? slots id))]
      (.delete drawn id))
    (set! (.-slots rt) slots)
    (filterv #(contains? slots (:id %)) robot-list)))

(defn sync!
  "The screens for robot-list: slots assigned, changed ones redrawn. colour-of: name -> THREE.Color."
  [^js rt robot-list colour-of]
  (let [shown (assign-slots! rt robot-list)
        now (js/Date.now)
        ^js attr (.-slotAttr rt)]
    (doseq [[i r] (map-indexed vector shown)
            :let [n (get (.-slots rt) (:id r))
                  k (st/content-key r now)
                  ^js c (colour-of (:color r))
                  [ox oy] (slot-offset n)]]
      (.setXY attr i ox oy)
      (.setColorAt ^js (.-beams rt) i c)
      (when (not= k (.get ^js (.-drawn rt) (:id r)))
        (draw-slot! (.-ctx rt) n r (str "#" (.getHexString c)) now)
        (.set ^js (.-drawn rt) (:id r) k)
        (set! (.-dirty rt) true)))
    (set! (.-needsUpdate attr) true)
    (some-> (.. rt -beams -instanceColor) (set! -needsUpdate true))
    (set! (.-list rt) (to-array shown))))

;; ---- every frame ----

(def ^:private scratch
  #js {:m (THREE/Matrix4.) :q (THREE/Quaternion.) :e (THREE/Euler. 0 0 0 "YXZ") :v (THREE/Vector3.) :s (THREE/Vector3.)})

(defn- screen-centre [[x y z] r]
  [x (+ y 3.0 (* 1.2 (:row r))) z])

(defn- screen-matrix
  "A screen's matrix at centre [x y z], tilted back a little, turned to the screens' yaw, scaled (into m)."
  [^js rt [x y z] scale ^js m]
  (let [sc scratch]
    (.set ^js (.-e sc) -0.1 (.-yaw rt) 0 "YXZ")
    (.compose m (.set ^js (.-v sc) x y z) (.setFromEuler ^js (.-q sc) (.-e sc))
              (.set ^js (.-s sc) scale scale scale))))

(def ^:private up-axis (THREE/Vector3. 0 1 0))

(defn- write-beam!
  "The beam from robot i's antenna (robot at p) to the bottom of its screen (centre c, scale)."
  [^js rt i [x y z] [cx cy cz] scale]
  (let [sc scratch
        ^js from (THREE/Vector3. x (+ y robots/tip-height) z)
        ^js dir (.sub (THREE/Vector3. cx (- cy (* scale (/ height 2))) cz) from)
        len (max 0.01 (.length dir))]
    (.setFromUnitVectors ^js (.-q sc) up-axis (.normalize dir))
    (.setMatrixAt ^js (.-beams rt) i
                  (.compose ^js (.-m sc) from (.-q sc) (.set ^js (.-s sc) (min scale 1) len (min scale 1))))))

(defn- upload! [^js rt]
  (let [now (js/Date.now)]
    (when (and (.-dirty rt) (> (- now (.-uploaded rt)) 400))
      (set! (.. rt -tex -needsUpdate) true)
      (set! (.-dirty rt) false)
      (set! (.-uploaded rt) now))))

(defn- scale-of [^js rt r [x y z] ^js cam-pos]
  (* (st/far-scale (.distanceTo cam-pos (.set ^js (.-v scratch) x y z)))
     (cond (= (:id r) (.-selected rt)) 1.25 (= (:id r) (.-hovered rt)) 1.1 :else 1)))

(defn- page-box
  "Screen c (world centre, drawn at scale) as a page box in px, y up, with its distance; nil behind
  the camera."
  [^js camera h c scale]
  (let [^js v (.set (THREE/Vector3.) (c 0) (c 1) (c 2))
        d (.distanceTo v (.-position camera))
        px-per-unit (/ h (* 2 d (js/Math.tan (/ (* (.-fov camera) js/Math.PI) 360))))]
    (.project v camera)
    (when (< (.-z v) 1)
      {:x (* (.-x v) (/ h 2) (.-aspect camera)) :y (* (.-y v) (/ h 2)) :d d :ppu px-per-unit
       :hw (* px-per-unit scale (/ width 2)) :hh (* px-per-unit scale (/ height 2))})))

(defn- target-moves
  "Each shown screen's move [side up] in world units so none covers another on the page: id -> move."
  [^js camera h placed]
  (let [boxes (->> placed (keep (fn [[r c scale]] (some-> (page-box camera h c scale) (assoc :id (:id r)))))
                   (sort-by :d))]
    (zipmap (map :id boxes)
            (map (fn [b [dx dy]] [(/ dx (:ppu b)) (/ dy (:ppu b))]) boxes (stack/moves boxes 8)))))

(defn- laid-out!
  "The screens' moves (claudia.web.world.stack), worked out again at most ten times a second: they
  glide toward them, so a tenth of a second late is never seen."
  [^js rt ^js camera h placed]
  (let [now (js/performance.now)]
    (when (> (- now (.-laidOut rt)) 100)
      (set! (.-targets rt) (target-moves camera h placed))
      (set! (.-laidOut rt) now))
    (.-targets rt)))

(defn- placed-screens
  "[robot centre scale] of each screen before lifting."
  [^js rt ^js robots-rt ^js camera]
  (for [r (.-list rt)
        :let [c (screen-centre (or (robots/position robots-rt (:id r)) [0 -100 0]) r)]]
    [r c (scale-of rt r c (.-position camera))]))

(defn- moved
  "c moved by [side up] (side along the camera's right, from the screens' yaw)."
  [^js rt [x y z] [side up]]
  (let [a (.-yaw rt)]
    [(+ x (* side (js/Math.cos a))) (+ y up) (- z (* side (js/Math.sin a)))]))

(defn- moved!
  "c moved by robot id's move, which glides toward target."
  [^js rt id c target dt]
  (let [[s0 u0] (or (.get ^js (.-moves rt) id) [0 0])
        [s1 u1] (or target [0 0])
        mv [(motion/damp s0 s1 6 dt) (motion/damp u0 u1 6 dt)]]
    (.set ^js (.-moves rt) id mv)
    (moved rt c mv)))

(defn frame!
  "Screens over their robots (robots-rt: claudia.web.world.robots), turned toward the camera, lifted
  so none covers another; h: the canvas's height in px."
  [^js rt ^js robots-rt ^js camera h cam-yaw dt]
  (set! (.-yaw rt) (motion/damp-angle (.-yaw rt) cam-yaw 4 dt))
  (let [placed (placed-screens rt robots-rt camera)
        targets (laid-out! rt camera h placed)]
    (place-halo! (.-halo rt) nil)
    (doseq [[i [r c scale]] (map-indexed vector placed)
            :let [mc (moved! rt (:id r) c (targets (:id r)) dt)
                  m (screen-matrix rt mc scale (.-m scratch))]]
      (.setMatrixAt ^js (.-mesh rt) i m)
      (when (= (:id r) (.-selected rt)) (place-halo! (.-halo rt) m))
      (write-beam! rt i (or (robots/position robots-rt (:id r)) [0 -100 0]) mc scale))
    (doseq [^js m [(.-mesh rt) (.-beams rt)]]
      (set! (.-count m) (count placed))
      (set! (.. m -instanceMatrix -needsUpdate) true)))
  (upload! rt))

(defn pick
  "The robot whose screen is under the raycaster, or nil."
  [^js rt ^js raycaster]
  (let [^js mesh (.-mesh rt)]
    (set! (.-boundingSphere mesh) nil)
    (when-let [^js hit (first (.intersectObject raycaster mesh false))]
      (aget (.-list rt) (.-instanceId hit)))))
