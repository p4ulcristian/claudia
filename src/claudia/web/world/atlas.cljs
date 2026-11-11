(ns claudia.web.world.atlas
  "Flat signs in the world drawn from a canvas: every sign is a slot of one canvas atlas, redrawn
  only when what it shows changes and sent to the card at most twice a second, all shown by one
  InstancedMesh whose node material reads each instance's slot. The islands' boards and the
  buildings' name signs are made with it."
  (:require ["three/webgpu" :as THREE]
            ["three/tsl" :as tsl]))

(defn- material [^js tex ^js slot-attr cols rows]
  (let [^js sample (tsl/texture tex (.add (.mul (tsl/uv) (tsl/vec2 (/ 1 cols) (/ 1 rows)))
                                         (tsl/instancedBufferAttribute slot-attr)))]
    (doto (THREE/MeshBasicNodeMaterial. #js {:side THREE/DoubleSide})
      (set! -colorNode (.-rgb sample))
      (set! -opacityNode (.-a sample))
      (set! -alphaTest 0.5))))

(defn make-halo
  "The glowing frame behind the selected screen or board (w x h: what it frames)."
  [w h]
  (let [m (THREE/Mesh. (THREE/PlaneGeometry. (* 1.08 w) (+ h (* 0.08 w)))
                       (THREE/MeshBasicNodeMaterial. #js {:color 0xffc247 :side THREE/DoubleSide}))]
    (set! (.-matrixAutoUpdate m) false)
    (set! (.-visible m) false)
    m))

(def sharp "Canvas pixels per drawing unit: text stays crisp up close." 1.5)

(defn make
  "An atlas of cols x rows slots of px-w x px-h drawing units (times sharp in pixels), shown as signs
  width wide, added to scene."
  [^js scene {:keys [px-w px-h cols rows width]}]
  (let [canvas (doto (js/document.createElement "canvas")
                 (set! -width (* sharp cols px-w)) (set! -height (* sharp rows px-h)))
        tex (doto (THREE/CanvasTexture. canvas) (set! -colorSpace THREE/SRGBColorSpace) (set! -anisotropy 4))
        slot-attr (THREE/InstancedBufferAttribute. (js/Float32Array. (* 2 cols rows)) 2)
        mesh (THREE/InstancedMesh. (THREE/PlaneGeometry. width (* width (/ px-h px-w)))
                                   (material tex slot-attr cols rows) (* cols rows))]
    (set! (.-count mesh) 0)
    (set! (.-frustumCulled mesh) false)
    (.add scene mesh)
    #js {:canvas canvas :ctx (doto (.getContext canvas "2d") (.setTransform sharp 0 0 sharp 0 0)) :tex tex :mesh mesh
         :slotAttr slot-attr :drawn #js [] :items #js [] :dirty false :uploaded 0 :pxW px-w :pxH px-h
         :cols cols :rows rows :width width :height (* width (/ px-h px-w)) :selected nil}))

(defn sync!
  "Signs for items (up to the atlas's slots): slot n redrawn by (draw! ctx x y w h item) when
  (key-of item) changed."
  [^js rt items key-of draw!]
  (let [^js attr (.-slotAttr rt)
        cols (.-cols rt) rows (.-rows rt) w (.-pxW rt) h (.-pxH rt)
        shown (take (* cols rows) items)]
    (doseq [[n item] (map-indexed vector shown)
            :let [k (key-of item) x (* w (mod n cols)) y (* h (quot n cols))]]
      (.setXY attr n (/ (mod n cols) cols) (- 1 (/ (inc (quot n cols)) rows)))
      (when (not= k (aget (.-drawn rt) n))
        (.clearRect ^js (.-ctx rt) x y w h)
        (draw! (.-ctx rt) x y w h item)
        (aset (.-drawn rt) n k)
        (set! (.-dirty rt) true)))
    (set! (.-needsUpdate attr) true)
    (set! (.-items rt) (to-array shown))))

(defn frame!
  "Every sign placed by (matrix-of item) (a Matrix4), the one whose :id is selected framed by halo (a
  mesh, or nil); the atlas sent to the card at most twice a second."
  [^js rt matrix-of selected ^js halo]
  (let [items (.-items rt) now (js/performance.now)]
    (when halo (set! (.-visible halo) false))
    (dotimes [n (alength items)]
      (let [item (aget items n) ^js m (matrix-of item)]
        (.setMatrixAt ^js (.-mesh rt) n m)
        (when (and halo (= selected (:id item)))
          (set! (.-visible halo) true)
          (.multiplyMatrices (.-matrix halo) m (.makeTranslation (THREE/Matrix4.) 0 0 -0.04)))))
    (set! (.. rt -mesh -count) (alength items))
    (set! (.. rt -mesh -instanceMatrix -needsUpdate) true)
    (when (and (.-dirty rt) (> (- now (.-uploaded rt)) 500))
      (set! (.. rt -tex -needsUpdate) true)
      (set! (.-dirty rt) false)
      (set! (.-uploaded rt) now))))

(defn pick
  "The item whose sign is under the raycaster, or nil."
  [^js rt ^js raycaster]
  (let [^js mesh (.-mesh rt)]
    (set! (.-boundingSphere mesh) nil)
    (when-let [^js hit (first (.intersectObject raycaster mesh false))]
      (aget (.-items rt) (.-instanceId hit)))))

(def ^:private scratch #js {:m (THREE/Matrix4.) :q (THREE/Quaternion.) :v (THREE/Vector3.) :s (THREE/Vector3. 1 1 1)
                            :e (THREE/Euler. 0 0 0 "YXZ")})

(defn facing
  "A sign's matrix at [x y z], turned toward the camera at cam (round y), tilted back by tilt."
  [[x y z] ^js cam tilt]
  (let [sc scratch]
    (.set ^js (.-e sc) tilt (js/Math.atan2 (- (.-x cam) x) (- (.-z cam) z)) 0 "YXZ")
    (.compose ^js (.-m sc) (.set ^js (.-v sc) x y z) (.setFromEuler ^js (.-q sc) (.-e sc)) (.-s sc))))
