(ns claudia.web.scene3d.engine
  "The three.js side of the scene3d form: parts, people, arrows and labels from the form's data, an
  optional script (QuickJS) that adds parts and moves them every frame, the story (steps that show
  parts, aim the camera, light parts up, pose people, with a narrator), the overlap check, and the
  orbit view (drag turns, wheel or pinch zooms, right drag or two fingers pan). The page's UI state
  lives in the `ui` atom the view hands in; this writes to it.

  A running scene is one map (`sc`): its three.js objects, `st` (a JS object with the per-frame
  state: the story's clock, the script's time, what is focused…) and atoms for the parts and fades.
  Every step below takes it; the numbers come from claudia.web.scene3d.maths and .data."
  (:require ["three" :as THREE]
            ["three/examples/jsm/controls/OrbitControls.js" :refer [OrbitControls]]
            ["three/examples/jsm/renderers/CSS2DRenderer.js" :refer [CSS2DRenderer]]
            ["three/examples/jsm/environments/RoomEnvironment.js" :refer [RoomEnvironment]]
            [claudia.shared.narration :as narration]
            [claudia.web.scene3d.clock :as clock]
            [claudia.web.scene3d.code :as code]
            [claudia.web.scene3d.codepaint :as codepaint]
            [claudia.web.scene3d.data :as data]
            [claudia.web.scene3d.ink :as ink]
            [claudia.web.scene3d.callouts :as callouts]
            [claudia.web.scene3d.labels :as labels]
            [claudia.web.scene3d.maths :as m]
            [claudia.web.scene3d.overlap :as overlap]
            [claudia.web.scene3d.person :as person]
            [claudia.web.scene3d.sources :as sources]
            [claudia.web.scene3d.story :as story]
            [claudia.web.scene3d.vm :as vm]
            [claudia.web.scene3d.voice :as voice]))

(def ^:private deg m/deg)
(def speeds [0.25 0.5 1 2])
(def ^:private UP (THREE/Vector3. 0 1 0))
(def ^:private scan-seconds 12)
(def voice-key "claudia.scene3d.voice")

(defn- v3 [[x y z]] (THREE/Vector3. x y z))
(defn- arr [^js p] [(.-x p) (.-y p) (.-z p)])
(defn- set-xyz! [^js v [x y z]] (.set v x y z))

(defn color-of
  "A theme colour name (green, amber…) or any CSS colour."
  ([host c] (color-of host c "blue"))
  ([host c fallback]
   (let [s (if (and (string? c) (seq c)) c fallback)
         css (if (re-matches #"[a-z]+" s)
               (let [v (.trim (.getPropertyValue (js/getComputedStyle host) (str "--" s)))]
                 (if (seq v) v s))
               s)]
     (try (THREE/Color. css) (catch :default _ (THREE/Color. 0x888888))))))

(defn- make-geometry
  "Unit-sized geometry per shape; size scales it. Extrude, lathe and tube build from the points."
  [p]
  (let [pts2 (map (fn [q] (THREE/Vector2. (js/Number (nth q 0 0)) (js/Number (nth q 1 0)))) (data/lst (:points p)))]
    (case (:shape p)
      "group" (THREE/BufferGeometry.)
      "person" (THREE/BufferGeometry.)
      "sphere" (THREE/SphereGeometry. 0.5 40 24)
      "cylinder" (THREE/CylinderGeometry. 0.5 0.5 1 40)
      "rod" (THREE/CylinderGeometry. 1 1 1 16)
      "cone" (THREE/ConeGeometry. 0.5 1 40)
      "pyramid" (.rotateY (THREE/ConeGeometry. (* 0.5 js/Math.SQRT2) 1 4) (/ js/Math.PI 4))
      "torus" (THREE/TorusGeometry. 0.4 0.1 20 56)
      "capsule" (THREE/CapsuleGeometry. 0.25 0.5 8 24)
      "plane" (.rotateX (THREE/PlaneGeometry. 1 1) (/ js/Math.PI -2))
      "text" (cond-> (THREE/PlaneGeometry. 1 1) (= "left" (:align p)) (.translate 0.5 0 0))
      "names" (.rotateX (THREE/PlaneGeometry. 1 1) (/ js/Math.PI -2))
      "ring" (.rotateX (THREE/RingGeometry. 0.3 0.5 48) (/ js/Math.PI -2))
      "extrude" (if (< (count pts2) 3)
                  (THREE/BoxGeometry. 1 1 1)
                  (.center (THREE/ExtrudeGeometry. (THREE/Shape. (into-array pts2)) #js {:depth 1 :bevelEnabled false})))
      "lathe" (if (>= (count pts2) 2) (THREE/LatheGeometry. (into-array pts2) 48) (THREE/SphereGeometry. 0.5 32 16))
      "tube" (let [path (map #(v3 (data/vec3 %)) (data/lst (:points p)))]
               (if (< (count path) 2)
                 (THREE/BoxGeometry. 1 1 1)
                 (THREE/TubeGeometry. (THREE/CatmullRomCurve3. (into-array path)) (* 16 (count path))
                                      (or (js/Number (:radius p)) 0.08) 12 false)))
      (THREE/BoxGeometry. 1 1 1))))

(def ^:private shared-geometry
  "One geometry per shape that has no points, made once and shared by every such part (thousands of
  boxes cost one buffer, and the overlap check one triangle tree)."
  (into {} (for [shape ["box" "code" "sphere" "cylinder" "rod" "cone" "pyramid" "torus" "capsule" "plane"
                        "ring" "names" "group" "person"]]
             [shape (make-geometry {:shape shape})])))

(defn- geometry-of [p]
  (or (when-not (= "text" (:shape p)) (get shared-geometry (or (:shape p) "box")))
      (make-geometry p)))

(defn- label-el [text note color]
  (let [d (js/document.createElement "div")
        b (js/document.createElement "b")]
    (set! (.-className d) "s3-label s3-noted")
    (.setProperty (.-style d) "--c" color)
    (set! (.-textContent b) text)
    (.append d b)
    (when (seq note)
      (let [n (js/document.createElement "span")]
        (set! (.-textContent n) note)
        (.append d n)))
    d))

(defn- place-rod!
  "A rod is a cylinder between two points (a connecting rod, a strut): it follows its ends."
  [^js m from to radius]
  (let [a (v3 from) b (v3 to)
        d (.sub (.clone b) a)
        len (max (.length d) 1e-6)]
    (.multiplyScalar (.add (.copy (.-position m) a) b) 0.5)
    (.set (.-scale m) radius len radius)
    (.setFromUnitVectors (.-quaternion m) UP (.divideScalar d len))))

(defn- set-rot! [^js m r]
  (let [[x y z] (data/vec3 r)] (.set (.-rotation m) (* x deg) (* y deg) (* z deg))))

;; An entry: one part. A JS object, since the frame loop changes it in place:
;; mesh spec radius k (appeared 0..1) dim (faded back 0..1) lit notes outline label lift person poseKey.

(defn- joint-of
  "The joint a \"<person>.<joint>\" names (an Object3D riding on that person's bone), or nil."
  [by-id s]
  (when-let [[pid j] (data/joint-ref s)]
    (when-let [^js en (get by-id pid)]
      (when-let [p (.-person en)] ((:joint p) j)))))

(defn- centre
  "A part's centre now, a person's joint (\"<person>.<joint>\"), or a point."
  [by-id e]
  (cond
    (string? e)
    (if-let [j (joint-of @by-id e)]
      (.getWorldPosition ^js j (THREE/Vector3.))
      (when-let [^js en (get @by-id e)]
        (.getCenter (.setFromObject (THREE/Box3.) (.-mesh en)) (THREE/Vector3.))))
    (sequential? e) (v3 (data/vec3 e))
    :else nil))

(defn- surface
  "Where an arrow meets a part: the part's surface on the line towards the other end."
  [by-id e ^js toward]
  (if-not (string? e)
    (centre by-id e)
    (if-let [^js en (get @by-id e)]
      (let [box (.setFromObject (THREE/Box3.) (.-mesh en))
            c (.getCenter box (THREE/Vector3.))
            dir (.sub (.clone toward) c)]
        (if (< (.lengthSq dir) 1e-9)
          c
          (or (.intersectBox (THREE/Ray. c (.normalize dir)) box (THREE/Vector3.)) c)))
      (centre by-id e))))

;; ---- arrows ----

(defn- arrow-path
  "The arrow's path now, as [x y z] points: from the surface of its start through the via points to
  the surface of its end; [] while an end is missing."
  [by-id {:keys [from to]} via]
  (let [f0 (if (seq via) (v3 (first via)) (centre by-id to))
        t0 (if (seq via) (v3 (peek via)) (centre by-id from))
        start (when (and f0 (centre by-id from)) (surface by-id from f0))
        end (when (and t0 (centre by-id to)) (surface by-id to t0))]
    (if (and start end) (vec (concat [(arr start)] via [(arr end)])) [])))

(defn- place-piece!
  "One mesh of an arrow: centred at pos, scaled, its y axis along dir."
  [^js m pos scale dir]
  (set-xyz! (.-position m) pos)
  (set-xyz! (.-scale m) scale)
  (.setFromUnitVectors (.-quaternion m) UP (v3 dir)))

(defn- show-arrow! [{:keys [segs tip dashes on]} show]
  (doseq [^js piece (concat segs [tip] dashes)] (set! (.-visible piece) show))
  (reset! on show))

(defn- lay-arrow!
  "Puts the arrow's pieces on its path: the segments, the head, the dashes, the label's anchor
  halfway."
  [{:keys [segs tip dashes ^js anchor]} pts total seconds]
  (let [head (m/arrow-head total)]
    (doseq [[s {:keys [pos len dir]}] (map vector segs (m/arrow-segments pts head))]
      (place-piece! s pos [1 len 1] dir))
    (let [{:keys [pos dir]} (m/arrow-tip pts head)]
      (place-piece! tip pos [head head head] dir))
    (doseq [[i dash] (map-indexed vector dashes)]
      (let [{:keys [pos dir]} (m/dash-at pts total head i (count dashes) seconds)]
        (place-piece! dash pos [1 0.2 1] dir)))
    (set-xyz! anchor (:p (m/along pts (/ total 2))))))

(defn- update-arrow!
  "An arrow follows its ends; seconds (when given) move its dashes on."
  ([arrow] (update-arrow! arrow nil))
  ([{:keys [k now by-id spec via] :as arrow} seconds]
   (when seconds (reset! now seconds))
   (let [pts (arrow-path by-id spec via)
         total (m/path-length pts)
         show (and (> total 0.05) (> @k 0.003))]
     (show-arrow! arrow show)
     (when show (lay-arrow! arrow pts total @now)))))

(defn- set-arrow!
  "How far an arrow has appeared (0..1)."
  [{:keys [k ^js mat] :as arrow} v]
  (reset! k v)
  (set! (.-opacity mat) v)
  (update-arrow! arrow))

(defn- add-meshes!
  "n meshes of geometry (made by `geo`) sharing mat, added to root."
  [^js root geo mat n]
  (vec (for [_ (range n)] (let [piece (THREE/Mesh. (geo) mat)] (.add root piece) piece))))

(defn- callout-layer [^js host] (.querySelector host ".s3-callouts"))

(defn- arrow-label
  "An arrow's label, in the callout layer (its anchor is the arrow's middle)."
  [host a ^js color]
  (when (:label a)
    (let [l (label-el (str (:label a)) nil (str "#" (.getHexString color)))]
      (.add (.-classList l) "arrow")
      (callouts/add! (callout-layer host) l)
      l)))

(defn- make-arrow
  "An arrow follows its ends (a part, or a point), optionally through via points; flow: true makes
  it a stream of dashes running along it."
  [host ^js root by-id a]
  (let [color (color-of host (:color a) "fg")
        via (mapv data/vec3 (data/lst (:via a)))
        flow? (true? (:flow a))
        mat (THREE/MeshBasicMaterial. #js {:color color :transparent true})
        r (if flow? 0.02 0.035)
        segs (add-meshes! root #(THREE/CylinderGeometry. r r 1 10) mat (inc (count via)))
        tip (first (add-meshes! root #(THREE/ConeGeometry. 0.35 1 16) mat 1))
        dashes (add-meshes! root #(THREE/CylinderGeometry. 0.06 0.06 1 10) mat (if flow? 16 0))
        arrow {:id (str (or (:id a) "")) :flows? flow? :spec a :via via :by-id by-id :mat mat
               :segs segs :tip tip :dashes dashes :label (arrow-label host a color)
               :anchor (THREE/Vector3.) :on (atom false) :k (atom 1) :now (atom 0)}]
    (update-arrow! arrow)
    arrow))

;; ---- parts ----

(def ^:private white (THREE/Color. 1 1 1))
(def ^:private no-size (.makeScale (THREE/Matrix4.) 0 0 0))

(defn- batch-reveal!
  "A batched part's look in its instanced mesh: faded towards white as it is dimmed, gone when hidden."
  [^js e k]
  (when-let [^js b (.-batch e)]
    (let [^js im (.-im b)]
      (.setColorAt im (.-bi e) (.lerp (.clone (.-base b)) white (* 0.85 (- 1 (.-dim e)))))
      (.setMatrixAt im (.-bi e) (if (> k 0.5) (.-m b) no-size))
      (set! (.. im -instanceColor -needsUpdate) true)
      (set! (.. im -instanceMatrix -needsUpdate) true))))

(declare mesh-reveal!)

(defn- apply-reveal!
  "How far a part has appeared (0 hidden .. 1 there) and how far it is dimmed, on its mesh or, for a
  batched part, in its batch."
  [^js e k]
  (if (.-proxy e)
    (do (set! (.-k e) k) (batch-reveal! e k))
    (mesh-reveal! e k)))

(defn- mesh-reveal!
  "How far a part has appeared (0 hidden .. 1 there): a fade, with its outline. dim fades
  the part back (the spotlight leaves the others faint); lit thickens its outline."
  [^js e k]
  (set! (.-k e) k)
  (let [^js mesh (.-mesh e)
        spec (.-spec e)]
    (when (not= "group" (:shape spec))
      (let [^js mat (.-material mesh)
            op (let [o (.. mat -userData -op)] (if (number? o) o 1))
            dim (.-dim e)]
        (set! (.-visible mesh) (> k 0.003))
        (set! (.-castShadow mesh) (and (.. mesh -userData -casts) (> k 0.5) (> dim 0.5)))
        (set! (.-opacity mat) (* op k dim))
        (set! (.-transparent mat) (or (.. mat -userData -alpha) (< op 1) (< k 1) (< dim 1)))
        (when-let [p (.-person e)] ((:reveal! p) k dim (.-lit e) op))
        (when-let [o (.-outline e)] (ink/set-outline! o k dim (.-lit e) op))
        (doseq [^js c (.-children mesh)]
          (cond
            (.. c -userData -face) (let [^js fm (.-material c)]
                                     (set! (.-opacity fm) (* k dim))
                                     (set! (.-transparent fm) (< (* k dim) 1)))
            (.-isLineSegments c) (set! (.. c -material -opacity) (* 0.35 k))))))))

(defn- override-person!
  "What a script's frame() says about a person."
  [^js e o]
  (let [^js mesh (.-mesh e)
        p (.-person e)]
    (when (:pose o) ((:set-pose! p) (:pose o) 0))
    (when (or (:color o) (:look o)) ((:recolour! p) o))
    (when (:pos o) (set-xyz! (.-position mesh) (data/vec3 (:pos o))))
    (when (:rot o) (set-rot! mesh (:rot o)))
    (when (some? (:size o)) (.setScalar (.-scale mesh) (data/person-scale (:size o) person/height)))
    (when (number? (:opacity o)) (set! (.. mesh -material -userData -op) (:opacity o)))))

(defn- override-part!
  "What a script's frame() says about a part that is not a person."
  [host ^js e o]
  (let [^js mesh (.-mesh e)
        spec (.-spec e)]
    (if (= "rod" (:shape spec))
      (when (or (:from o) (:to o))
        (place-rod! mesh (data/vec3 (or (:from o) (:from spec))) (data/vec3 (or (:to o) (:to spec))) (.-radius e)))
      (do (when (:pos o) (set-xyz! (.-position mesh) (data/vec3 (:pos o))))
          (when (:rot o) (set-rot! mesh (:rot o)))
          (when (and (some? (:size o)) (not= "tube" (:shape spec)))
            (set-xyz! (.-scale mesh) (data/size-of (:size o)))
            (set! (.-lift e) js/NaN))))
    (when (:color o) (.copy (.. mesh -material -color) (color-of host (:color o))))
    (when (number? (:opacity o)) (set! (.. mesh -material -userData -op) (:opacity o)))))

(defn- apply-override!
  "What a script's frame() says about one part."
  [host ^js e o]
  (if (.-person e) (override-person! e o) (override-part! host e o))
  (apply-reveal! e (.-k e)))

(defn- part-material
  "A part's material: toon bands (the diagram style) or physical (the realistic one)."
  [host p style]
  (let [has-material? (contains? ink/presets (:material p))
        tint (when (or (:color p) (not has-material?)) (color-of host (:color p)))
        base-op (if (= "plane" (:shape p)) 0.35 1)]
    (if (= style "diagram")
      (let [^js phys (ink/material-for tint p base-op)
            t (ink/toon-material (.-color phys) (.. phys -userData -op) (:wire p))]
        (.dispose phys)
        t)
      (ink/material-for tint p base-op))))

(defn- place-part!
  "A part's place, turn and size from its data (a rod: between its ends; a person: by height)."
  [^js mesh p radius]
  (let [shape (:shape p)]
    (if (= "rod" shape)
      (place-rod! mesh (data/vec3 (:from p)) (data/vec3 (:to p)) radius)
      (do (cond (= "person" shape) (.setScalar (.-scale mesh) (data/person-scale (:size p) person/height))
                (not= "tube" shape) (set-xyz! (.-scale mesh) (data/size-of (:size p))))
          (set-xyz! (.-position mesh) (data/vec3 (:pos p)))
          (set-rot! mesh (:rot p))))))

(defn- canvas-texture [^js canvas]
  (let [t (THREE/CanvasTexture. canvas)]
    (set! (.-colorSpace t) THREE/SRGBColorSpace)
    (set! (.-anisotropy t) 8)
    t))

(defn- text-material [^js canvas]
  (let [m (THREE/MeshBasicMaterial. #js {:map (canvas-texture canvas) :transparent true :depthWrite false
                                         :side THREE/DoubleSide})]
    (set! (.. m -userData -op) 1)
    (set! (.. m -userData -alpha) true)
    m))

(defn- add-face!
  "A code slab's face: its painted canvas on a plane just in front of the box's +z side."
  [^js mesh ^js canvas]
  (let [face (THREE/Mesh. (THREE/PlaneGeometry. 1 1) (THREE/MeshBasicMaterial. #js {:map (canvas-texture canvas)}))]
    (set! (.. face -position -z) 0.502)
    (set! (.. face -userData -face) true)
    (set! (.. face -userData -id) (.. mesh -userData -id))
    (.add mesh face)
    face))

(defn text-canvas
  "A text part's canvas (sources/prepare paints texts with this)."
  [host p]
  (codepaint/paint-text host (str "#" (.getHexString (color-of host (:color p) "#1b1d22"))) p))

(defn- part-mesh [host p style face]
  (let [^js mat (if (and (#{"text" "names"} (:shape p)) face) (text-material (:canvas face)) (part-material host p style))
        ^js mesh (THREE/Mesh. (geometry-of p) mat)
        shape (:shape p)
        group? (= "group" shape)]
    (set! (.. mesh -userData -id) (str (:id p)))
    (set! (.. mesh -userData -casts) (and (not group?) (overlap/solid? p)))
    (set! (.-castShadow mesh) (.. mesh -userData -casts))
    (set! (.-receiveShadow mesh) (and (not group?) (not (:ghost p))))
    (when (or group? (= "person" shape)) (set! (.-visible mat) false))
    (place-part! mesh p (data/radius-of p))
    (when (and (= "code" shape) face) (add-face! mesh (:canvas face)))
    mesh))

(defn- add-outline! [^js e]
  (let [^js mesh (.-mesh e)
        ^js o (THREE/Mesh. (.-geometry mesh) (ink/outline-material))]
    (set! (.. o -userData -outline) true)
    (.add mesh o)
    (set! (.-outline e) o)))

(defn- add-edges! [^js mesh]
  (.add mesh (THREE/LineSegments. (THREE/EdgesGeometry. (.-geometry mesh) 30)
                                  (THREE/LineBasicMaterial. #js {:color 0x000000 :transparent true :opacity 0.35}))))

(defn- nearest-vertex
  "The point of geometry g nearest c (both local)."
  [^js g ^js c]
  (let [^js pos (.. g -attributes -position)
        v (THREE/Vector3.)]
    (reduce (fn [[best d] i]
              (.fromBufferAttribute v pos i)
              (let [di (.distanceToSquared v c)] (if (< di d) [(.clone v) di] [best d])))
            [(.clone c) js/Infinity] (range (.-count pos)))))

(defn- anchor-of
  "Where a part's callout line ends (local to its mesh): the centre of a plain shape, else the
  point of its geometry nearest the centre (the centre of a bent tube can lie off it)."
  [^js mesh p]
  (let [^js g (.-geometry mesh)
        _ (.computeBoundingBox g)
        c (.getCenter (.-boundingBox g) (THREE/Vector3.))]
    (if (or (= "text" (:shape p)) (contains? shared-geometry (:shape p "box")))
      c
      (first (nearest-vertex g c)))))

(defn- add-label! [host ^js e p]
  (let [^js mesh (.-mesh e)
        l (label-el (str (:label p)) (:note p) (str "#" (.getHexString (.. mesh -material -color))))]
    (callouts/add! (callout-layer host) l)
    (set! (.-anchor e) (anchor-of mesh p))
    (set! (.-label e) l)))

(def ^:private hidden-material
  (let [m (THREE/MeshBasicMaterial.)] (set! (.-visible m) false) m))

(defn- proxy-entry
  "A part its batch's instanced mesh draws (:batch): an invisible stand-in that clicks, boxes, focus
  and floating code use like any part's mesh."
  [p]
  (let [mesh (THREE/Mesh. (get shared-geometry "box") hidden-material)]
    (set! (.-visible mesh) false)
    (set! (.. mesh -userData -id) (str (:id p)))
    (place-part! mesh p 0.08)
    #js {:mesh mesh :spec p :radius 0.08 :k 1 :dim 1 :lit false :notes true :outline nil :label nil
         :lift js/NaN :person nil :poseKey nil :proxy true :batch nil :bi -1}))

(defn- part-entry
  "One part's entry: its mesh with outline, edges and label as its style and data ask."
  [host p style face]
  (let [mesh (part-mesh host p style face)
        ^js e #js {:mesh mesh :spec p :radius (data/radius-of p) :k 1 :dim 1 :lit false :notes true
                   :outline nil :label nil :lift js/NaN :person nil :poseKey nil}]
    (when (data/outline? p style) (add-outline! e))
    (when (data/edges? p style (contains? ink/presets (:material p))) (add-edges! mesh))
    (when (and (:label p) (not= "group" (:shape p))) (add-label! host e p))
    e))

(defn- attach-people!
  "A person: the model, posed (its hands may reach for parts, found once all are made)."
  [host by-id style people]
  (doseq [[id ^js e] @by-id
          :when (and (= "person" (:shape (.-spec e))) people)]
    (set! (.-person e) (person/make (.-mesh e) people id style #(color-of host %) #(centre by-id %) (.-spec e)))
    (set! (.-poseKey e) (data/pose-key (or (:pose (.-spec e)) "stand")))))

(defn- parent-parts!
  "A part with a parent moves, turns and scales with it; \"<person>.<joint>\" hangs it from a joint."
  [by-id ^js root]
  (doseq [[_ ^js e] by-id]
    (let [par (str (or (:parent (.-spec e)) ""))
          ^js parent (or (joint-of by-id par) (some-> ^js (get by-id par) .-mesh) root)]
      (.add parent (.-mesh e)))))

(defn- make-batch!
  "One instanced mesh drawing a batch's stand-ins (one draw call for thousands), beside them."
  [host entries]
  (let [im (THREE/InstancedMesh. (get shared-geometry "box") (THREE/MeshLambertMaterial.) (count entries))]
    (doseq [[i ^js e] (map-indexed vector entries)
            :let [^js mesh (.-mesh e)
                  c (color-of host (:color (.-spec e)))]]
      (.updateMatrix mesh)
      (.setMatrixAt im i (.-matrix mesh))
      (.setColorAt im i c)
      (set! (.-batch e) #js {:im im :base c :m (.clone (.-matrix mesh))})
      (set! (.-bi e) i))
    (set! (.-receiveShadow im) true)
    (.computeBoundingSphere im)
    (.add (.. ^js (first entries) -mesh -parent) im)))

(defn- make-batches! [host by-id]
  (doseq [[_ es] (group-by #(:batch (.-spec ^js %)) (filter #(.-proxy ^js %) (vals by-id)))]
    (make-batch! host (vec es))))

(defn- build-parts
  "Every part's entry (people need the loaded model), parented as the data says. -> an atom of
  {id entry}."
  [host ^js root parts style people faces]
  (let [entry #(if (:batch %) (proxy-entry %) (part-entry host % style (get faces (str (:id %)))))
        by-id (atom (reduce (fn [acc p] (assoc acc (str (:id p)) (entry p))) {} parts))]
    (attach-people! host by-id style people)
    (parent-parts! @by-id root)
    (make-batches! host @by-id)
    by-id))

(defn- dispose! [^js root]
  (.traverse root (fn [^js o]
                    (some-> (.-geometry o) .dispose)
                    (let [mat (.-material o)]
                      (if (array? mat) (doseq [^js x mat] (.dispose x)) (some-> ^js mat .dispose)))))
  (.clear root))

;; ---- slabs floating over parts ----

(defn- tether!
  "A thin line from a floating slab's bottom middle to the top middle of what it floats over (to,
  in the world); the old line goes."
  [^js mesh ^js to]
  (doseq [^js c (vec (.-children mesh)) :when (.. c -userData -tether)]
    (.remove mesh c) (.dispose (.-geometry c)) (.dispose (.-material c)))
  (let [g (.setFromPoints (THREE/BufferGeometry.) #js [(THREE/Vector3. 0 -0.5 0) (.worldToLocal mesh (.clone to))])
        line (THREE/LineSegments. g (THREE/LineBasicMaterial. #js {:color 0x555555 :transparent true :opacity 0.35}))]
    (set! (.. line -userData -tether) true)
    (.add mesh line)))

(defn- in-front?
  "Whether box b stands in the column in front (+z) of a slab of width w centred at x cx, z cz:
  where the camera reading it stands."
  [^js b cx cz w]
  (and (< (.. b -min -x) (+ cx (/ w 2))) (> (.. b -max -x) (- cx (/ w 2)))
       (< (.. b -min -z) (+ cz (* 3 w))) (> (.. b -max -z) (- cz 0.5))))

(defn- front-top
  "The highest top of the parts (not other floating slabs or texts) in front of the slab."
  [by-id ^js self cx cz w]
  (reduce (fn [top [_ ^js e]]
            (let [spec (.-spec e)]
              (if (or (identical? e self) (:over spec) (#{"code" "text" "group"} (:shape spec)))
                top
                (let [b (.setFromObject (THREE/Box3.) (.-mesh e))]
                  (if (in-front? b cx cz w) (max top (.. b -max -y)) top)))))
          js/-Infinity by-id))

(defn- over-wish
  "Where a slab floating over a part would sit alone: over its part's middle (pos as an offset), lift
  (default 1.5) over its top and clear of the parts in front of it. -> {:e :x :z :w :bottom :to}."
  [by-id ^js e]
  (let [spec (.-spec e)]
    (when-let [^js t (get by-id (str (:over spec)))]
      (let [b (.setFromObject (THREE/Box3.) (.-mesh t))
            c (.getCenter b (THREE/Vector3.))
            [ox oy oz] (data/vec3 (:pos spec))
            lift (let [l (js/Number (:lift spec))] (if (pos? l) l 1.5))
            x (+ (.-x c) ox) z (+ (.-z c) oz) w (.. e -mesh -scale -x)]
        {:e e :x x :z z :w w :to (THREE/Vector3. (.-x c) (.. b -max -y) (.-z c))
         :bottom (max (+ (.. b -max -y) lift oy) (+ 0.3 (front-top by-id e x z w)))}))))

(defn- place-overs!
  "Slabs floating over parts hang as one row (claudia.web.scene3d.maths/shelf): never over each
  other, in the order of their parts, at one height and depth, each with a line down to its part."
  [{:keys [^js root by-id]}]
  (.updateMatrixWorld root true)
  (let [ws (vec (keep #(when (:over (.-spec ^js %)) (over-wish @by-id %)) (vals @by-id)))
        xs (m/shelf ws (* 0.08 (/ (reduce + (map :w ws)) (max 1 (count ws)))))
        z (/ (reduce + (map :z ws)) (max 1 (count ws)))
        bottom (reduce max js/-Infinity (map :bottom ws))]
    (doseq [[{:keys [^js e to]} x] (map vector ws xs)]
      (let [^js mesh (.-mesh e)]
        (.copy (.-position mesh) (.worldToLocal (.-parent mesh) (THREE/Vector3. x (+ bottom (/ (.. mesh -scale -y) 2)) z)))
        (.updateMatrixWorld mesh true)
        (tether! mesh to))))
  (.updateMatrixWorld root true))

;; ---- making the scene ----

(defn- make-renderer
  "The WebGL renderer and the label layer over it, both in the stage."
  [^js stage]
  (let [renderer (THREE/WebGLRenderer. #js {:antialias true :alpha true})
        labels (CSS2DRenderer.)]
    (.setPixelRatio renderer (min js/devicePixelRatio 2))
    (set! (.-toneMapping renderer) THREE/ACESFilmicToneMapping)
    (set! (.-toneMappingExposure renderer) 1.05)
    (set! (.. renderer -shadowMap -enabled) true)
    (set! (.. renderer -shadowMap -type) THREE/PCFShadowMap)
    (set! (.. labels -domElement -className) "s3-labels")
    (.append stage (.-domElement renderer) (.-domElement labels))
    {:renderer renderer :labels labels}))

(defn- make-lights
  "Light: a soft room around everything (reflections), one sun for the shadows; a grid and a floor
  that catches them."
  [renderer]
  (let [pmrem (THREE/PMREMGenerator. renderer)
        sun (THREE/DirectionalLight. 0xffffff 2.1)
        grid (THREE/GridHelper. 20 20 0x888888 0x555555)
        ground (THREE/Mesh. (.rotateX (THREE/PlaneGeometry. 80 80) (/ js/Math.PI -2)) (THREE/ShadowMaterial. #js {:opacity 0.3}))]
    (.set (.. sun -shadow -mapSize) 2048 2048)
    (set! (.. sun -shadow -bias) -0.0004)
    (set! (.. sun -shadow -normalBias) 0.03)
    (set! (.. sun -shadow -radius) 4)
    (set! (.. grid -material -transparent) true)
    (set! (.. grid -material -opacity) 0.35)
    (set! (.-receiveShadow ground) true)
    {:pmrem pmrem :env (.-texture (.fromScene pmrem (RoomEnvironment.) 0.04))
     :hemi (THREE/HemisphereLight. 0xffffff 0x444444 0.35) :sun sun :grid grid :ground ground}))

(defn- make-world [{:keys [env hemi ^js sun grid ground]} root]
  (let [scene (THREE/Scene.)]
    (set! (.-environment scene) env)
    (set! (.-environmentIntensity scene) 0.9)
    (.add scene hemi)
    (.add scene sun (.-target sun))
    (.add scene grid)
    (.add scene ground)
    (.add scene root)
    scene))

(defn- make-controls [camera ^js labels]
  (let [controls (OrbitControls. camera (.-domElement labels))]
    (set! (.-enableDamping controls) true)
    (set! (.-dampingFactor controls) 0.12)
    (set! (.-zoomSpeed controls) 0.9)
    controls))

(defn- initial-state [ui data0]
  #js {:high (= "high" (:quality @ui)) :style (:style @ui) :data data0
       :box (THREE/Box3.) :fitted false
       :script nil :arrows [] :steps []
       :sIdx -1 :sPlaying false :sElapsed 0 :sDur 0 :sToken 0 :sTick 0 :sLast 0
       :sMuted (not= "on" (js/localStorage.getItem voice-key)) :sInstruction nil :sAutoplay false
       :world nil :faces {} :raised nil :reader nil
       :focus #{} :cam nil :checker nil :flagged "" :bad #{} :chosen nil
       :frames 0 :scanSeconds scan-seconds
       :t 0 :last 0 :rate (speeds 2) :on true :visible true :token 0 :raf 0
       :labelFocus #{} :labelShows #{} :hover nil :hoverAt 0 :cTick 0 :mrect nil})

(defn- make-scene
  "Everything a running scene holds. video: {:seconds {[instruction text] seconds}} when a video is
  made off screen (the narrator only notes what it would say), else nil."
  [^js el ui data0 video]
  (let [stage (.querySelector el ".s3-stage")
        camera (THREE/PerspectiveCamera. 45 1 0.05 500)
        {:keys [renderer labels]} (make-renderer stage)
        lights (make-lights renderer)
        root (THREE/Group.)
        scene (make-world lights root)
        controls (make-controls camera labels)]
    (merge lights
           {:el el :stage stage :ui ui :scene scene :camera camera :renderer renderer :labels labels
            :callouts (callouts/make! stage)
            :root root :controls controls :st (initial-state ui data0)
            :by-id (atom {}) :k-of (atom {}) :fades (atom []) :dim-target (atom {})
            :ever-seen (atom #{}) :pressed (atom nil)
            :narrator (if video (voice/given (:seconds video)) (voice/make))
            :voice-ready (if video (js/Promise.resolve true) (voice/available?))})))

;; ---- the running scene's steps ----

(declare draw! tick! move-parts! build! new-story! start-step! pickable)

(defn- want!
  "Draw on the next frame (once). A video's maker draws every frame itself."
  [{:keys [^js st] :as sc}]
  (when (and (zero? (.-raf st)) (not (clock/stepped?)))
    (set! (.-raf st) (js/requestAnimationFrame #(draw! sc (clock/now))))))

(defn- ui! [sc & kvs] (apply swap! (:ui sc) assoc kvs))
(defn- diagram? [{:keys [^js st]}] (= "diagram" (.-style st)))
(defn- update-arrows! [{:keys [^js st]}] (doseq [a (.-arrows st)] (update-arrow! a)))

(defn- size! [{:keys [^js stage ^js renderer ^js labels ^js camera callouts] :as sc}]
  (let [w (.-clientWidth stage) h (.-clientHeight stage)]
    (.setSize renderer w h)
    (.setSize labels w h)
    (callouts/size! callouts w h)
    (.set (.-value ^js ink/viewport) (max w 1) (max h 1))
    (set! (.-aspect camera) (/ w (max h 1)))
    (.updateProjectionMatrix camera)
    (want! sc)))

(defn- construct? [{:keys [^js st]}] (= "construct" (.-world st)))

(defn- apply-world!
  "The construct: an endless white room (white all round, fog into it, no grid, a floor as far as
  you look that only shows shadows); else the page shows through, with the grid."
  [{:keys [^js scene ^js grid ^js ground] :as sc}]
  (let [c (construct? sc)]
    (set! (.-background scene) (when c (THREE/Color. 0xffffff)))
    (when-not c (set! (.-fog scene) nil))
    (set! (.-visible grid) (not c))
    (.setScalar (.-scale ground) (if c 40 1))))

(defn- apply-style!
  "The diagram style: flat light, no reflections, no shadows (but in the construct); the realistic
  one: room, sun, shadows."
  [{:keys [^js st ^js renderer ^js scene ^js hemi ^js sun ^js ground env] :as sc}]
  (let [d (diagram? sc)
        shadows (and (.-high st) (or (not d) (construct? sc)))]
    (set! (.-toneMapping renderer) (if d THREE/NoToneMapping THREE/ACESFilmicToneMapping))
    (set! (.-environment scene) (if d nil env))
    (set! (.-intensity hemi) (if d 1.25 0.35))
    (set! (.-intensity sun) (if d 1.5 2.1))
    (set! (.-castShadow sun) shadows)
    (set! (.. ground -material -opacity) (if (construct? sc) 0.14 0.3))
    (set! (.-visible ground) shadows)
    (apply-world! sc)))

(defn- set-quality! [{:keys [^js st ^js renderer] :as sc} q]
  (set! (.-high st) (= q "high"))
  (apply-style! sc)
  (.setPixelRatio renderer (if (or (.-high st) (diagram? sc)) (min js/devicePixelRatio 2) 1))
  (size! sc))

(defn- fit! "The camera looks at the whole scene." [{:keys [^js st ^js camera ^js controls]}]
  (let [^js box (.-box st)]
    (when (.isEmpty box) (.set box (THREE/Vector3. -2 0 -2) (THREE/Vector3. 2 2 2)))
    (let [c (.getCenter box (THREE/Vector3.))
          dist (m/fit-distance (max (/ (.length (.getSize box (THREE/Vector3.))) 2) 1) (.-fov camera))]
      (.copy (.-target controls) c)
      (.add (.copy (.-position camera) c) (.multiplyScalar (.normalize (THREE/Vector3. 0.7 0.55 1)) dist))
      (.update controls))))

(defn- fit-light!
  "The sun and the floor follow the scene's size."
  [{:keys [^js st ^js sun ^js ground ^js grid]}]
  (let [^js box (.-box st)
        c (.getCenter box (THREE/Vector3.))
        r (max (/ (.length (.getSize box (THREE/Vector3.))) 2) 2)
        ^js cam (.. sun -shadow -camera)
        {:keys [left right top bottom near far]} (m/sun-frame r)
        y (- (.. box -min -y) 0.01)]
    (.add (.copy (.-position sun) c) (.multiplyScalar (.normalize (THREE/Vector3. 0.45 1 0.55)) (* r 2.2)))
    (.copy (.. sun -target -position) c)
    (set! (.-left cam) left) (set! (.-right cam) right)
    (set! (.-top cam) top) (set! (.-bottom cam) bottom)
    (set! (.-near cam) near) (set! (.-far cam) far)
    (.updateProjectionMatrix cam)
    (set! (.. ground -position -y) y)
    (set! (.. grid -position -y) y)))

(defn- fit-fog!
  "The construct's fog: clear around the scene, white far away."
  [{:keys [^js st ^js scene] :as sc}]
  (when (construct? sc)
    (let [r (max (/ (.length (.getSize (.-box st) (THREE/Vector3.))) 2) 2)]
      (set! (.-fog scene) (THREE/Fog. 0xffffff (* r 2.2) (* r 7))))))

(defn- stop-script! [{:keys [^js st]}]
  (when-let [s (.-script st)] ((:close s)) (set! (.-script st) nil)))

(defn- glow! [^js e {:keys [hex strength person-strength]}]
  (let [^js mat (.. e -mesh -material)]
    (when-let [em (.-emissive mat)] (.setHex em hex) (set! (.-emissiveIntensity mat) strength))
    (when-let [p (.-person e)] ((:emissive! p) hex person-strength))))

(defn- paint!
  "Red glow for overlapping parts, blue for the clicked one, amber pulse for focus (real)."
  ([sc] (paint! sc 0.5))
  ([{:keys [^js st by-id] :as sc} pulse]
   (doseq [[id ^js e] @by-id]
     (let [bad? ((.-bad st) id)
           chosen? (= id (.-chosen st))
           lit? (and (not (diagram? sc)) ((.-focus st) id) (not bad?) (not chosen?))]
       (glow! e (data/paint bad? chosen? lit? pulse))))
   (want! sc)))

(defn- run-check!
  "Which solid parts that are fully there overlap now; the ones seen ever are kept."
  [{:keys [^js st k-of ever-seen] :as sc}]
  (when-let [c (.-checker st)]
    (let [hits ((:check c) #(> (get @k-of % 1) 0.99))
          flagged (data/hits-key hits)]
      (when (not= flagged (.-flagged st))
        (set! (.-flagged st) flagged)
        (set! (.-bad st) (set (flatten hits)))
        (paint! sc)
        (let [fresh (remove @ever-seen (map data/pair-key hits))]
          (swap! ever-seen into fresh)
          (ui! sc :now (mapv data/pair-key hits))
          (when (seq fresh) (ui! sc :seen (vec @ever-seen))))
        (want! sc)))))

(defn- apply-frame! [{:keys [el by-id]} o]
  (doseq [[k v] o :let [^js e (get @by-id (data/id-str k))] :when (and e (map? v))]
    (apply-override! el e v)))

(defn- scan!
  "Runs a whole stretch of the script without drawing, to catch overlaps that come later."
  [{:keys [^js st] :as sc}]
  (let [s (.-script st)]
    (if (or (not (.-checker st)) (not (:frame s)))
      (run-check! sc)
      (do (try
            (doseq [t (range 0 (+ (.-scanSeconds st) 1e-9) (/ 1 30))]
              (apply-frame! sc ((:frame s) t (.-sIdx st)))
              (run-check! sc))
            (catch :default e (ui! sc :error (str "script: " (.-message e)))))
          (move-parts! sc)
          (run-check! sc)
          (ui! sc :scanned true)))))

(defn- move-parts!
  "The script's frame() at the scene's time; arrows follow; every other frame the overlap check."
  [{:keys [^js st ^js root] :as sc}]
  (when-let [f (:frame (.-script st))]
    (try
      (apply-frame! sc (f (.-t st) (.-sIdx st)))
      (.updateMatrixWorld root true)
      (update-arrows! sc)
      (set! (.-frames st) (inc (.-frames st)))
      (when (even? (.-frames st)) (run-check! sc))
      (catch :default e
        (ui! sc :error (str "script: " (.-message e)) :animated false)
        (stop-script! sc)))))

;; ---- new data ----

(defn- load-script!
  "A promise of what the data's script builds ({} without a script). The script stays while `mine`
  is still the latest data."
  [{:keys [^js st] :as sc} d mine]
  (if-let [source (data/script-of d)]
    (-> (vm/load-script source)
        (.then (fn [s]
                 (if (not= mine (.-token st))
                   (do ((:close s)) nil)
                   (do (set! (.-script st) s) ((:build s))))))
        (.catch (fn [e] (when (= mine (.-token st)) (ui! sc :error (str "script: " (.-message e)))) {})))
    (js/Promise.resolve {})))

(defn- load-people
  "A promise of the person model when some part is a person (nil otherwise, or when it fails)."
  [sc all]
  (if (some #(= "person" (:shape %)) all)
    (.catch (person/load-model)
            (fn [e] (ui! sc :error (str "person model: " (.-message e))) nil))
    (js/Promise.resolve nil)))

(defn- load-and-build!
  "The person model and the real code (cities, code slabs, texts) load, then the scene is built."
  [{:keys [^js st el] :as sc} d extra current?]
  (let [all (vec (filter map? (concat (data/lst (:parts d)) (data/lst (:parts extra)))))]
    ;; The screenshot waits while some scene is still reading its code.
    (set! (.-__scene3dLoading js/window) (inc (or (.-__scene3dLoading js/window) 0)))
    (-> (js/Promise.all #js [(load-people sc all) (sources/prepare el (.-reader st) d all text-canvas)])
        (.finally #(set! (.-__scene3dLoading js/window) (dec (.-__scene3dLoading js/window))))
        (.then (fn [[people {:keys [parts faces errors]}]]
                 (when (current?)
                   (when (seq errors) (ui! sc :error (str "code: " (first errors))))
                   (set! (.-faces st) faces)
                   (build! sc d extra parts people)))))))

(defn- set-data!
  "New data: the script runs, the model loads, then the scene is built again (only for the latest)."
  [{:keys [^js st] :as sc} d]
  (let [mine (inc (.-token st))
        current? #(= mine (.-token st))]
    (set! (.-token st) mine)
    (set! (.-data st) d)
    (set! (.-world st) (:world d))
    (set! (.-raised st) nil)
    (apply-style! sc)
    (ui! sc :legend (data/legend-of d) :error "")
    (stop-script! sc)
    (-> (load-script! sc d mine)
        (.then (fn [extra] (when (current?) (load-and-build! sc d (if (map? extra) extra {}) current?))))
        (.catch (fn [^js e] (js/console.error e) (ui! sc :error (str (.-message e))))))))

(defn- frame-scene!
  "The scene's box; the light follows it; the camera fits it the first time."
  [{:keys [^js st ^js root] :as sc}]
  (set! (.-box st) (.setFromObject (THREE/Box3.) root))
  (fit-light! sc)
  (fit-fog! sc)
  (when-not (.-fitted st) (fit! sc) (set! (.-fitted st) true)))

(defn- build!
  "The parts, arrows and overlap checker from the data and what the script built."
  [{:keys [^js st ^js root el by-id k-of fades ever-seen callouts] :as sc} d extra all people]
  (callouts/clear! callouts)
  (dispose! root)
  (reset! by-id @(build-parts el root (filterv map? all) (.-style st) people (.-faces st)))
  (.updateMatrixWorld root true)
  (place-overs! sc)
  (set! (.-arrows st) (mapv #(make-arrow el root by-id %)
                            (filter map? (concat (data/lst (:arrows d)) (data/lst (:arrows extra))))))
  (reset! k-of {})
  (reset! fades [])
  (set! (.-checker st) (overlap/make-checker root (for [[id ^js e] @by-id] {:id id :mesh (.-mesh e) :spec (.-spec e)})
                                             (concat (data/lst (:allow d)) (data/lst (:allow extra)))))
  (set! (.-scanSeconds st) (data/scan-seconds-of d scan-seconds))
  (reset! ever-seen #{})
  (ui! sc :solids (:count (.-checker st)) :seen [] :now [] :scanned false)
  (set! (.-flagged st) "")
  (run-check! sc)
  (frame-scene! sc)
  (ui! sc :animated (boolean (:frame (.-script st))))
  (set! (.-last st) 0)
  (new-story! sc d)
  (want! sc))

;; ---- the story ----

(defn- set-reveal! [{:keys [^js st by-id k-of]} id k]
  (swap! k-of assoc id k)
  (if-let [e (get @by-id id)]
    (apply-reveal! e k)
    (when-let [a (some #(when (= id (:id %)) %) (.-arrows st))] (set-arrow! a k))))

(defn- all-ids [{:keys [^js st by-id]}]
  (concat (keys @by-id) (filter seq (map :id (.-arrows st)))))

(defn- apply-story-state!
  "Which parts are on after step n; the ones that just came on fade in (animate?)."
  [{:keys [^js st ^js root k-of fades] :as sc} n animate?]
  (let [ids (all-ids sc)
        plan (data/reveal-plan ids (story/visible-at (.-steps st) n ids) @k-of @fades animate?
                               (clock/now))]
    (reset! k-of (:k-of plan))
    (reset! fades (:fades plan))
    (doseq [[id k] (:jump plan)] (set-reveal! sc id k))
    (.updateMatrixWorld root true)
    (update-arrows! sc)
    (want! sc)))

(defn- box-of [{:keys [by-id]} patterns]
  (let [b (THREE/Box3.)]
    (doseq [[id ^js e] @by-id
            :when (and (not= "group" (:shape (.-spec e))) (story/matches-any? patterns id))]
      (.union b (.setFromObject (THREE/Box3.) (.-mesh e))))
    b))

(defn- camera-goal
  "Where a step's camera looks (a Vector3) and from how far: [target dist]."
  [{:keys [^js camera ^js controls] :as sc} c]
  (let [dist (.distanceTo (.-position camera) (.-target controls))
        ^js fb (when (seq (:frame c)) (box-of sc (:frame c)))
        [target d] (cond
                     (and fb (not (.isEmpty fb)))
                     [(.getCenter fb (THREE/Vector3.))
                      (m/fit-distance (max (/ (.length (.getSize fb (THREE/Vector3.))) 2) 0.6) (.-fov camera))]
                     (string? (:target c)) [(let [^js b (box-of sc [(:target c)])]
                                              (if (.isEmpty b) (.clone (.-target controls)) (.getCenter b (THREE/Vector3.))))
                                            dist]
                     (sequential? (:target c)) [(v3 (data/vec3 (:target c))) dist]
                     :else [(.clone (.-target controls)) dist])]
    [target (or (:dist c) d)]))

(defn- camera-dir
  "Which way the camera looks from: as now, or from the step's azimuth and elevation."
  [{:keys [^js camera ^js controls]} c]
  (let [dir (.sub (.clone (.-position camera)) (.-target controls))]
    (when (< (.lengthSq dir) 1e-6) (.set dir 0.7 0.55 1))
    (when (or (some? (:az c)) (some? (:el c)))
      (set-xyz! dir (m/orbit-dir (or (:az c) 30) (or (:el c) 25))))
    (.normalize dir)))

(defn- read-goal
  "camera.read: straight in front of a part's face (+z), near enough that its width or height fills
  the view, a little above centre so the subtitles leave it free. -> [target dist dir] or nil."
  [{:keys [^js camera by-id]} c]
  (when-let [^js e (get @by-id (str (:read c)))]
    (let [^js mesh (.-mesh e)
          sc3 (.getWorldScale mesh (THREE/Vector3.))
          w (.-x sc3) h (.-y sc3)
          t (js/Math.tan (/ (* (.-fov camera) deg) 2))
          d (max (/ (* h 0.64) t) (/ (* w 0.56) (* t (.-aspect camera))))
          target (.getWorldPosition mesh (THREE/Vector3.))]
      (set! (.-y target) (- (.-y target) (* h 0.07)))
      [target (or (:dist c) d) (.getWorldDirection mesh (THREE/Vector3.))])))

(defn- move-camera!
  "A step's camera: glides there over 1.2 s (the frames ease it)."
  [{:keys [^js st ^js root ^js camera ^js controls] :as sc} c]
  (.updateMatrixWorld root true)
  (let [[^js target dist dir] (or (read-goal sc c) (conj (camera-goal sc c) (camera-dir sc c)))]
    (set! (.-cam st) {:at (clock/now) :dur 1200
                      :p0 (.clone (.-position camera)) :t0 (.clone (.-target controls))
                      :p1 (.addScaledVector (.clone target) dir dist) :t1 target}))
  (want! sc))

(defn- set-spotlight!
  "The spotlight: with a focus, the other parts fade back; the focused get a thick outline."
  [{:keys [^js st by-id dim-target] :as sc}]
  (let [on (and (diagram? sc) (seq (.-focus st)))]
    (doseq [[id ^js e] @by-id]
      (let [{:keys [lit notes dim]} (data/spot on (.-focus st) id (empty? (.-steps st)))]
        (swap! dim-target assoc id dim)
        (when (or (not= lit (.-lit e)) (not= notes (.-notes e)))
          (set! (.-lit e) lit)
          (set! (.-notes e) notes)
          (apply-reveal! e (.-k e)))))
    (want! sc)))

(defn- start-tick!
  "The story's clock ticks every 100 ms; a video's maker ticks it every frame (-1 marks it on)."
  [{:keys [^js st] :as sc}]
  (set! (.-sTick st) (if (clock/stepped?) -1 (js/setInterval #(tick! sc) 100))))
(defn- stop-tick! [{:keys [^js st]}] (js/clearInterval (.-sTick st)) (set! (.-sTick st) 0))
(defn- end-story! [{:keys [^js st] :as sc}] (set! (.-sPlaying st) false) (stop-tick! sc) (ui! sc :playing false))
(defn- focus-of [{:keys [by-id]} step] (set (filter #(story/matches-any? (:focus step) %) (keys @by-id))))

(defn- pose-people!
  "People take the pose their part gives, or the latest the story gave them up to step n."
  [{:keys [^js st by-id] :as sc} n seconds]
  (doseq [[id ^js e] @by-id :when (.-person e)]
    (let [spec (or (story/pose-at (.-steps st) n id) (:pose (.-spec e)) "stand")
          k (data/pose-key spec)]
      (when (not= k (.-poseKey e))
        (set! (.-poseKey e) k)
        ((:set-pose! (.-person e)) spec seconds))))
  (want! sc))

(defn- step-timed!
  "The step lasts `seconds` (plus the gap); the clock ticks while the story plays."
  [{:keys [^js st] :as sc} mine seconds]
  (when (= mine (.-sToken st))
    (set! (.-sDur st) (+ seconds story/gap-seconds))
    (set! (.-sLast st) (clock/now))
    (when (and (.-sPlaying st) (zero? (.-sTick st))) (start-tick! sc))
    (want! sc)))

(defn- narrate-step!
  "The narrator says the step's line (given 12 s to load); the step lasts as long as the clip."
  [{:keys [^js st narrator] :as sc} step mine est]
  (-> (js/Promise.race #js [((:load narrator) (:say step) (.-sInstruction st))
                            (js/Promise. (fn [r] (js/setTimeout #(r nil) 12000)))])
      (.then (fn [clip]
               (when (= mine (.-sToken st))
                 (if clip
                   (do (when (.-sPlaying st) ((:play narrator) clip))
                       (step-timed! sc mine (max (:seconds clip) (:hold step))))
                   (step-timed! sc mine est)))))))

(defn- time-step!
  "How long the step lasts: its clip when the voice is on and there, else the estimate."
  [{:keys [^js st voice-ready] :as sc} step mine]
  (let [est (story/estimate-seconds step)]
    (if (and (seq (:say step)) (not (.-sMuted st)))
      (.then voice-ready (fn [ready]
                           (if (and ready (= mine (.-sToken st)))
                             (narrate-step! sc step mine est)
                             (step-timed! sc mine est))))
      (step-timed! sc mine est))))

(defn- face-of [^js mesh] (some #(when (.. ^js % -userData -face) %) (.-children mesh)))

(defn- repaint-face!
  "A code slab painted again with these marks (its texture swapped)."
  [{:keys [^js st el by-id]} id marks]
  (when-let [^js e (get @by-id id)]
    (when-let [^js face (face-of (.-mesh e))]
      (let [^js mat (.-material face)
            canvas (sources/paint-code el (get-in (.-faces st) [id :input]) marks)]
        (some-> (.-map mat) .dispose)
        (set! (.-map mat) (canvas-texture canvas))
        (set! (.-needsUpdate mat) true)
        (set! (.-faces st) (assoc-in (.-faces st) [id :shown] marks))))))

(defn- mark-step!
  "Step marks: a slab's lines marked as the step says, else as the slab's own mark."
  [{:keys [^js st] :as sc} step]
  (doseq [[id f] (.-faces st) :when (:input f)]
    (let [given (some (fn [[k v]] (when (= (data/id-str k) id) v)) (:mark step))
          marks (code/marked (if (some? given) given (:mark f)))]
      (when (not= marks (get f :shown (code/marked (:mark f))))
        (repaint-face! sc id marks))))
  (want! sc))

(defn- label-step!
  "Which labels a step shows: the parts and arrows it focuses on, and the ones it brings in (nil:
  no step)."
  [{:keys [^js st] :as sc} step]
  (let [ids (all-ids sc)]
    (set! (.-labelFocus st) (if step (set (filter #(story/matches-any? (:focus step) %) ids)) #{}))
    (set! (.-labelShows st) (if step (set (filter #(story/matches-any? (:show step) %) ids)) #{}))))

(defn- show-step!
  "What step n shows: parts on, poses, the focus and spotlight, the labels, the camera."
  [{:keys [^js st] :as sc} n step animate?]
  (apply-story-state! sc n animate?)
  (pose-people! sc n (if animate? (:move step) 0))
  (set! (.-focus st) (focus-of sc step))
  (label-step! sc step)
  (set-spotlight! sc)
  (when (empty? (.-focus st)) (paint! sc))
  (mark-step! sc step)
  (when (:camera step) (move-camera! sc (:camera step))))

(defn- start-step! [{:keys [^js st narrator] :as sc} n animate?]
  (when (< -1 n (count (.-steps st)))
    (let [mine (inc (.-sToken st))
          step (nth (.-steps st) n)]
      (set! (.-sToken st) mine)
      ((:stop narrator))
      (set! (.-sIdx st) n) (set! (.-sElapsed st) 0) (set! (.-sDur st) 0)
      (ui! sc :prog 0 :idx n :sub (:say step))
      (show-step! sc n step animate?)
      (time-step! sc step mine))))

(defn- tick!
  "The story's clock, every 100 ms: progress, and the next step when this one has run out."
  [{:keys [^js st] :as sc}]
  (let [c (data/tick {:playing (.-sPlaying st) :dur (.-sDur st) :elapsed (.-sElapsed st) :last (.-sLast st)
                      :idx (.-sIdx st) :n (count (.-steps st))}
                     (clock/now))]
    (set! (.-sElapsed st) (:elapsed c))
    (set! (.-sLast st) (:last c))
    (when (some? (:prog c)) (ui! sc :prog (:prog c)))
    (case (:then c)
      :next (start-step! sc (inc (.-sIdx st)) true)
      :end (end-story! sc)
      nil)))

(defn- playing! [{:keys [^js st narrator] :as sc}]
  (set! (.-sPlaying st) true) (ui! sc :playing true) ((:resume narrator)))

(defn- resume-story! [{:keys [^js st narrator] :as sc}]
  ((:resume narrator))
  (set! (.-sLast st) (clock/now))
  (when (zero? (.-sTick st)) (start-tick! sc))
  (when (zero? (.-sDur st)) (start-step! sc (.-sIdx st) false)))

(defn- restart-story!
  "The story from its start: every story part hidden again, step 1 playing."
  [{:keys [^js st] :as sc}]
  (set! (.-sPlaying st) true)
  (ui! sc :playing true)
  (apply-story-state! sc -1 false)
  (start-step! sc 0 true))

(defn- story-play! [{:keys [^js st] :as sc}]
  (when (seq (.-steps st))
    (if (data/restart? (.-sIdx st) (count (.-steps st)) (.-sDur st) (.-sElapsed st))
      (restart-story! sc)
      (do (set! (.-sPlaying st) true)
          (ui! sc :playing true)
          (resume-story! sc)))))

(defn- story-pause! [{:keys [^js st narrator] :as sc}]
  (set! (.-sPlaying st) false) (ui! sc :playing false) ((:pause narrator)) (stop-tick! sc))

(defn- preload-line!
  "Loads line n of the story's lines, then the next, saying how far it got."
  [{:keys [^js st narrator] :as sc} lines n]
  (cond
    (.-sMuted st) nil
    (>= n (count lines)) (ui! sc :voice-state "voice ready")
    :else (do (ui! sc :voice-state (str "voice " n "/" (count lines)))
              (-> ((:load narrator) (nth lines n) (.-sInstruction st))
                  (.then (fn [clip]
                           (if clip
                             (preload-line! sc lines (inc n))
                             (ui! sc :voice-state (str "no voice: " @(:error narrator))))))))))

(defn- preload-voice! [{:keys [^js st voice-ready] :as sc}]
  (.then voice-ready (fn [ok] (when ok (preload-line! sc (data/voice-lines (.-steps st)) 0)))))

(defn- mute! [{:keys [^js st narrator] :as sc} muted]
  (set! (.-sMuted st) muted)
  (ui! sc :muted muted)
  (js/localStorage.setItem voice-key (if muted "off" "on"))
  (if muted (do ((:stop narrator)) (ui! sc :voice-state "voice off")) (preload-voice! sc)))

(defn- voice-state!
  "The voice is off until the user turns it on; with it on, the lines load ahead."
  [{:keys [^js st voice-ready] :as sc} d steps]
  (if (and (not= false (:narrate d)) (some (comp seq :say) steps))
    (.then voice-ready (fn [ok]
                         (cond (not ok) (ui! sc :voice-state "no voice here")
                               (.-sMuted st) (ui! sc :voice-state "voice off")
                               :else (preload-voice! sc))))
    (ui! sc :voice-state "")))

(defn- clear-story! [{:keys [^js st] :as sc}]
  (set! (.-sIdx st) -1)
  (ui! sc :idx -1 :sub "")
  (set! (.-focus st) #{})
  (label-step! sc nil)
  (set-spotlight! sc)
  (paint! sc))

(defn- keep-story!
  "A rebuilt scene stays on its step; a story that never played starts (unless autoplay is off)."
  [{:keys [^js st] :as sc} d steps]
  (when (>= (.-sIdx st) (count steps)) (set! (.-sIdx st) (dec (count steps))))
  (apply-story-state! sc (.-sIdx st) false)
  (pose-people! sc (.-sIdx st) 0)
  (set! (.-focus st) (if (>= (.-sIdx st) 0) (focus-of sc (nth steps (.-sIdx st))) #{}))
  (label-step! sc (when (>= (.-sIdx st) 0) (nth steps (.-sIdx st))))
  (set-spotlight! sc)
  (voice-state! sc d steps)
  (when (and (not= false (:autoplay d)) (not (.-sAutoplay st)) (neg? (.-sIdx st)))
    (set! (.-sAutoplay st) true)
    (story-play! sc)))

(defn- new-story!
  "After every build: the steps may have changed (Claude adds to the story while it plays)."
  [{:keys [^js st] :as sc} d]
  (let [steps (story/normalize (:story d))]
    (set! (.-steps st) steps)
    (set! (.-sInstruction st) (narration/voice-of d))
    (ui! sc :story-len (count steps) :story steps :autoplay (:autoplay d))
    (if (empty? steps) (clear-story! sc) (keep-story! sc d steps))))

;; ---- one frame ----

(defn- script-frame!
  "The script's clock runs while the scene plays and shows. -> whether it ran."
  [{:keys [^js st] :as sc} now]
  (when (:frame (.-script st))
    (let [running (and (.-on st) (.-visible st))]
      (when running (set! (.-t st) (m/advance-clock (.-t st) (.-last st) now (.-rate st))))
      (move-parts! sc)
      running)))

(defn- fade-frame! [{:keys [fades] :as sc} wall]
  (when (seq @fades)
    (doseq [f @fades] (set-reveal! sc (:id f) (data/fade-value f wall)))
    (swap! fades data/running-fades wall)
    (update-arrows! sc)
    true))

(defn- people-frame! [{:keys [by-id] :as sc} wall]
  (when (some identity (doall (for [[_ ^js e] @by-id :when (.-person e)] ((:update! (.-person e)) wall))))
    (update-arrows! sc)
    true))

(defn- camera-frame! [{:keys [^js st ^js camera ^js controls]} wall]
  (when-let [{:keys [at dur p0 t0 p1 t1]} (.-cam st)]
    (let [p (min 1 (/ (- wall at) dur))
          e (m/ease-in-out-quad p)]
      (.lerpVectors (.-position camera) p0 p1 e)
      (.lerpVectors (.-target controls) t0 t1 e)
      (when (>= p 1) (set! (.-cam st) nil))
      true)))

(defn- pulse-frame! [{:keys [^js st] :as sc} wall]
  (when (and (seq (.-focus st)) (not (diagram? sc)))
    (paint! sc (m/focus-pulse wall))
    true))

(defn- dim-frame!
  "Parts fade back or forth towards their spotlight dim. -> whether some are still on the way."
  [{:keys [by-id dim-target]}]
  (reduce (fn [again [id ^js e]]
            (let [to (get @dim-target id 1)
                  dim (m/dim-step (.-dim e) to)]
              (if (nil? dim)
                again
                (do (set! (.-dim e) dim) (apply-reveal! e (.-k e)) (or (not= dim to) again)))))
          false @by-id))

(defn- flow-frame! [{:keys [^js st]} wall]
  (when (and (.-visible st) (some :flows? (.-arrows st)))
    (doseq [a (.-arrows st) :when (:flows? a)] (update-arrow! a (/ wall 1000)))
    true))

(defn- render! [{:keys [^js scene ^js camera ^js renderer]}]
  (.updateMatrixWorld scene)
  (.render renderer scene camera))

;; ---- callouts ----

(def ^:private occluder (THREE/Raycaster.))
(def ^:private max-callouts 24)

(defn- screen-of
  "A world point on a stage w by h: [x y] in pixels, or nil when it is behind the camera."
  [^js camera ^js v w h]
  (let [^js s (.project (.clone v) camera)]
    (when (< (.-z s) 1) [(* (/ (+ (.-x s) 1) 2) w) (* (/ (- 1 (.-y s)) 2) h)])))

(defn- hidden-behind?
  "Whether a visible, opaque mesh (not one of own) lies between the camera and v."
  [^js camera meshes own ^js v]
  (let [dir (.sub (.clone v) (.-position camera))
        dist (.length dir)]
    (.set occluder (.-position camera) (.normalize dir))
    (set! (.-far occluder) (* dist 0.985))
    (boolean (some (fn [^js h]
                     (let [^js o (.-object h) ^js mat (.-material o)]
                       (and (not (own o)) (.-visible o) (not (array? mat)) (>= (.-opacity mat) 0.95))))
                   (array-seq (.intersectObjects occluder meshes false))))))

(defn- part-callouts
  "The parts' labels: {:id :el :anchor (world) :k :own}."
  [by-id]
  (for [[id ^js e] by-id :let [^js l (.-label e)] :when l]
    (let [p (.-person e)
          v (if p ((:label-point p) (THREE/Vector3.)) (.localToWorld (.-mesh e) (.clone (.-anchor e))))]
      (.toggle (.-classList l) "s3-noted" (boolean (.-notes e)))
      {:id id :el l :anchor v :k (if (> (.-dim e) 0.5) (.-k e) 0)
       :own (set (if p (:meshes p) [(.-mesh e)]))})))

(defn- arrow-callouts
  "The arrows' labels: {:id :el :anchor :k :own}."
  [arrows]
  (for [{:keys [id label anchor k on segs tip]} arrows :when label]
    {:id id :el label :anchor anchor :k (if @on @k 0) :own (set (cons tip segs))}))

(defn- model-rect
  "The scene's box on the screen: {:left :right :top :bottom} (pixels, inside the stage)."
  [{:keys [^js root ^js camera]} w h]
  (let [^js b (.setFromObject (THREE/Box3.) root)
        lo (.-min b) hi (.-max b)
        pts (keep #(screen-of camera % w h)
                  (for [x [(.-x lo) (.-x hi)] y [(.-y lo) (.-y hi)] z [(.-z lo) (.-z hi)]] (THREE/Vector3. x y z)))]
    (if (empty? pts)
      {:left (* 0.3 w) :right (* 0.7 w) :top 0 :bottom h}
      {:left (max 0 (apply min (map first pts))) :right (min w (apply max (map first pts)))
       :top (max 0 (apply min (map second pts))) :bottom (min h (apply max (map second pts)))})))

(defn- callout-item
  "A label to show, with its anchor on the screen, or nil (not wanted, not there, off the stage,
  or behind something solid)."
  [{:keys [^js camera]} meshes want w h {:keys [id el anchor k own]}]
  (when (and (> k 0.05) (labels/wanted? want id))
    (when-let [[x y] (screen-of camera anchor w h)]
      (when (and (< -1 x (inc w)) (< -1 y (inc h)) (not (hidden-behind? camera meshes own anchor)))
        {:id id :el el :x x :y y :opacity (min 1 k)
         :color (let [c (.getPropertyValue (.-style ^js el) "--c")] (if (seq c) c "#888"))}))))

(defn- obstacles
  "What labels must keep clear of, as rects on the stage: the legend, the subtitles, the bar."
  [^js el ^js stage]
  (let [^js sr (.getBoundingClientRect stage)]
    (for [sel [".s3-legend" ".s3-sub" ".s3-bar"]
          :let [^js x (.querySelector el sel)]
          :when x
          :let [^js r (.getBoundingClientRect x)]
          :when (pos? (.-width r))]
      {:left (- (.-left r) (.-left sr)) :right (- (.-right r) (.-left sr))
       :top (- (.-top r) (.-top sr)) :bottom (- (.-bottom r) (.-top sr))})))

(defn- callouts-frame!
  "The labels for this frame: which show, where their columns stand, their leader lines. -> whether
  any label still eases into place."
  [{:keys [^js st ^js stage el callouts by-id] :as sc}]
  (let [w (.-clientWidth stage) h (.-clientHeight stage)
        tick (inc (.-cTick st))
        _ (set! (.-cTick st) tick)
        _ (when (or (nil? (.-mrect st)) (zero? (mod tick 3))) (set! (.-mrect st) (model-rect sc w h)))
        all (concat (part-callouts @by-id) (arrow-callouts (.-arrows st)))
        want {:story? (boolean (seq (.-steps st))) :focus (.-labelFocus st) :shows (.-labelShows st)
              :hover (.-hover st)}
        meshes (pickable @by-id)
        items (vec (take max-callouts (keep #(callout-item sc meshes want w h %) all)))
        shown (set (map :el items))]
    (callouts/frame! callouts items (remove shown (map :el all)) (.-mrect st) w h (obstacles el stage))))

(defn- draw!
  "One frame: the script, fades, people, camera, focus pulse, dims, flows, the orbit, then drawing
  and labels; another frame comes while anything still moves."
  [{:keys [^js st ^js controls] :as sc} now]
  (set! (.-raf st) 0)
  (let [wall now
        ticking (script-frame! sc now)
        _ (set! (.-last st) (if ticking now 0))
        fading (fade-frame! sc wall)
        posing (people-frame! sc wall)
        flying (camera-frame! sc wall)
        pulsing (pulse-frame! sc wall)
        dimming (dim-frame! sc)
        flowing (flow-frame! sc wall)
        turning (.update controls)
        _ (render! sc)
        easing (callouts-frame! sc)]
    (when (or ticking fading posing flying pulsing dimming flowing turning easing) (want! sc))))

;; ---- input ----

(defn- select! [{:keys [^js st] :as sc} id]
  (set! (.-chosen st) id)
  (when-not id (ui! sc :picked nil))
  (paint! sc))

(defn- pickable "The meshes a click can hit: parts that are there (a person's body meshes)." [by-id]
  (into-array (mapcat (fn [[_ ^js e]]
                        (when (and (not= "group" (:shape (.-spec e))) (> (.-k e) 0.05))
                          (if-let [p (.-person e)] (:meshes p) [(.-mesh e)])))
                      by-id)))

(defn- hits-at
  "The meshes under a screen point; thin parts are hard to hit, so points around it are tried."
  [{:keys [^js labels ^js camera]} meshes cx cy]
  (let [^js r (.getBoundingClientRect (.-domElement labels))
        rect {:left (.-left r) :top (.-top r) :width (.-width r) :height (.-height r)}
        ray (THREE/Raycaster.)]
    (some (fn [[dx dy]]
            (let [[x y] (m/ndc (+ cx dx) (+ cy dy) rect)]
              (.setFromCamera ray (THREE/Vector2. x y) camera)
              (let [h (.intersectObjects ray meshes false)] (when (pos? (.-length h)) h))))
          m/pick-offsets)))

(defn- best-hit "A solid part first, else the nearest." [by-id hits]
  (when hits
    (or (some #(when (overlap/solid? (.-spec ^js (get by-id (str (.. ^js % -object -userData -id))))) %) hits)
        (aget hits 0))))

(defn- part-facts [{:keys [^js st by-id]} id]
  (let [^js e (get @by-id id)
        spec (.-spec e)
        w (.getWorldPosition (.-mesh e) (THREE/Vector3.))
        rod (when (= "rod" (:shape spec)) (m/vdist (data/vec3 (:from spec)) (data/vec3 (:to spec))))]
    [spec (data/part-rows id spec (arr w) rod (overlap/solid? spec) ((.-bad st) id))]))

(defn- drop-raised!
  "The code raised by a click goes away. -> the id it had."
  [{:keys [^js st by-id]}]
  (when-let [rid (.-raised st)]
    (when-let [^js e (get @by-id rid)]
      (let [^js mesh (.-mesh e)]
        (dispose! mesh)
        (some-> (.-parent mesh) (.remove mesh))))
    (swap! by-id dissoc rid)
    (set! (.-raised st) nil)
    rid))

(defn- show-raised! [{:keys [^js st ^js root el by-id] :as sc} rid p face]
  (let [^js e (part-entry el p (.-style st) face)]
    (.add root (.-mesh e))
    (swap! by-id assoc rid e)
    (set! (.-faces st) (assoc (.-faces st) rid face))
    (set! (.-raised st) rid)
    (place-overs! sc)
    (move-camera! sc {:read rid})))

(defn- raise!
  "A click on a file of a folders map: its code rises above it and the camera comes to read it; a second
  click on the same file puts it back."
  [{:keys [^js st el] :as sc} id spec]
  (let [rid (str id "#code")
        token (.-token st)
        {:keys [size lift]} (:slab spec)]
    (when-not (= rid (drop-raised! sc))
      (-> (sources/prepare el (.-reader st) (.-data st)
                           [{:id rid :shape "code" :file (:file spec) :over id :range "1-60" :size size :lift lift}]
                           text-canvas)
          (.then (fn [{[p] :parts faces :faces}]
                   (when (= token (.-token st)) (show-raised! sc rid p (get faces rid)))))))
    (want! sc)))

(defn- pick!
  "A click on a part opens its facts; on nothing, closes them."
  [{:keys [^js root el by-id] :as sc} cx cy]
  (.updateMatrixWorld root true)
  (let [hit (best-hit @by-id (hits-at sc (pickable @by-id) cx cy))]
    (if-not hit
      (do (select! sc nil) (ui! sc :hint "no part there"))
      (let [id (str (.. ^js hit -object -userData -id))
            [spec rows] (part-facts sc id)
            ^js box (.getBoundingClientRect el)]
        (select! sc id)
        (when (and (:file spec) (:slab spec)) (raise! sc id spec))
        (ui! sc :picked (data/picked id spec rows (- cx (.-left box)) (- cy (.-top box))))))))

(defn- dolly! [{:keys [^js camera ^js controls]} factor]
  (let [offset (.sub (.clone (.-position camera)) (.-target controls))
        dist (m/dolly-distance factor (.length offset))]
    (.addScaledVector (.copy (.-position camera) (.-target controls)) (.normalize offset) dist)))

(defn- pan! [{:keys [^js camera ^js controls ^js stage]} dx dy unit]
  (let [per-px (m/pan-per-pixel (.distanceTo (.-position camera) (.-target controls)) (.-fov camera)
                                (.-clientHeight stage))
        right (.setFromMatrixColumn (THREE/Vector3.) (.-matrix camera) 0)
        upward (.setFromMatrixColumn (THREE/Vector3.) (.-matrix camera) 1)
        move (.addScaledVector (.multiplyScalar right (* dx unit per-px)) upward (* -1 dy unit per-px))]
    (.add (.-position camera) move)
    (.add (.-target controls) move)))

(defn- wheel!
  "A trackpad: two fingers sliding pan, a pinch (ctrl + wheel) zooms; a mouse wheel zooms."
  [{:keys [^js st ^js controls] :as sc} ^js e]
  (.preventDefault e)
  (.stopPropagation e)
  (set! (.-cam st) nil)
  (let [kind (m/wheel-kind {:mode (.-deltaMode e) :dx (.-deltaX e) :dy (.-deltaY e) :ctrl (.-ctrlKey e)})]
    (if (= kind :pan)
      (pan! sc (.-deltaX e) (.-deltaY e) (if (= 1 (.-deltaMode e)) 16 1))
      (dolly! sc (m/zoom-factor kind (.-deltaY e)))))
  (.update controls)
  (want! sc))

(defn- press! [{:keys [pressed]} ^js e] (reset! pressed [(.-clientX e) (.-clientY e)]))

(defn- hover!
  "The part under the pointer shows its label (looked up at most every 60 ms, not while dragging)."
  [{:keys [^js st ^js root by-id pressed] :as sc} ^js e]
  (let [now (js/performance.now)]
    (when (and (nil? @pressed) (> (- now (.-hoverAt st)) 60))
      (set! (.-hoverAt st) now)
      (.updateMatrixWorld root true)
      (let [hit (best-hit @by-id (hits-at sc (pickable @by-id) (.-clientX e) (.-clientY e)))
            id (when hit (str (.. ^js hit -object -userData -id)))]
        (when (not= id (.-hover st))
          (set! (.-hover st) id)
          (want! sc))))))

(defn- unhover! [{:keys [^js st] :as sc}]
  (when (.-hover st) (set! (.-hover st) nil) (want! sc)))

(defn- release!
  "A click is a press and release in place; dragging to turn is not."
  [{:keys [pressed] :as sc} ^js e]
  (let [at @pressed]
    (reset! pressed nil)
    (when (and at (m/click? at (.-clientX e) (.-clientY e) (.-button e)))
      (pick! sc (.-clientX e) (.-clientY e)))))

(defn- listen!
  "The scene's listeners and observers. -> what destroy! removes."
  [{:keys [el ^js stage ^js labels ^js controls ^js st] :as sc}]
  (let [input {:wheel #(wheel! sc %) :down #(press! sc %) :click #(release! sc %)
               :move #(hover! sc %) :leave #(unhover! sc)
               :ro (js/ResizeObserver. #(size! sc))
               :io (js/IntersectionObserver.
                    (fn [entries] (set! (.-visible st) (.-isIntersecting ^js (aget entries 0))) (want! sc)))}]
    (.addEventListener controls "start" #(set! (.-cam st) nil))
    (.addEventListener controls "change" #(want! sc))
    (.addEventListener stage "wheel" (:wheel input) #js {:passive false :capture true})
    (.addEventListener (.-domElement labels) "pointerdown" (:down input))
    (.addEventListener (.-domElement labels) "click" (:click input))
    (.addEventListener (.-domElement labels) "pointermove" (:move input))
    (.addEventListener (.-domElement labels) "pointerleave" (:leave input))
    (.observe ^js (:ro input) el)
    (.observe ^js (:io input) el)
    input))

(defn- destroy!
  "Stops everything and frees the GPU's and the page's resources."
  [{:keys [^js st ^js stage ^js labels ^js controls ^js env ^js pmrem ^js renderer root narrator callouts] :as sc}
   {:keys [wheel down click move leave ^js ro ^js io]}]
  (set! (.-token st) (inc (.-token st)))
  (js/cancelAnimationFrame (.-raf st))
  (.disconnect ro)
  (.disconnect io)
  (.removeEventListener (.-domElement labels) "pointerdown" down)
  (.removeEventListener (.-domElement labels) "click" click)
  (.removeEventListener (.-domElement labels) "pointermove" move)
  (.removeEventListener (.-domElement labels) "pointerleave" leave)
  (.removeEventListener stage "wheel" wheel #js {:capture true})
  (.dispose controls)
  (.dispose env)
  (.dispose pmrem)
  (stop-tick! sc)
  ((:close narrator))
  (stop-script! sc)
  (dispose! root)
  (.dispose renderer)
  (.remove (.-domElement renderer))
  (.remove (.-domElement labels))
  (.remove (:layer callouts))
  (.remove (:canvas callouts)))

(defn- set-style! [{:keys [^js st] :as sc} s]
  (when (not= s (.-style st))
    (set! (.-style st) s)
    (apply-style! sc)
    (set-quality! sc (if (.-high st) "high" "low"))
    (set-data! sc (.-data st))))

(defn- video-begin!
  "A video starts: the story from step 1 (when there is one), the first frame drawn."
  [{:keys [^js st] :as sc}]
  (when (seq (.-steps st)) (restart-story! sc))
  (draw! sc (clock/now)))

(defn- video-step!
  "The video's next frame, ms after the last: the clock moves on, the story ticks, the frame is
  drawn (paint it into the video right after, while the drawing is there)."
  [{:keys [^js st] :as sc} ms]
  (clock/step! ms)
  (when-not (zero? (.-sTick st)) (tick! sc))
  (draw! sc (clock/now)))

(defn- video-api
  "What a video's maker drives: begin, step, whether the story still plays, what was said when."
  [{:keys [^js st ^js renderer narrator] :as sc}]
  {:begin! #(video-begin! sc)
   :step! #(video-step! sc %)
   :playing? #(boolean (.-sPlaying st))
   :steps #(.-steps st)
   :said #(deref (:said narrator))
   :gl (.-domElement renderer)})

(defn- story-api [{:keys [^js st] :as sc}]
  {:play #(story-play! sc)
   :pause #(story-pause! sc)
   :next (fn [] (when (< (inc (.-sIdx st)) (count (.-steps st))) (playing! sc) (start-step! sc (inc (.-sIdx st)) true)))
   :prev (fn [] (playing! sc) (start-step! sc (max 0 (dec (.-sIdx st))) false))
   :goto (fn [n] (playing! sc) (start-step! sc n (= n (inc (.-sIdx st)))))
   :mute #(mute! sc %)})

(defn start!
  "Builds the view inside `el` (which holds a .s3-stage). ui: the view's state atom; rd: the reads
  of real code (claudia.web.scene3d.sources/reader, nil outside a chat); video: for a video made off
  screen, {:seconds {[instruction text] seconds}} of the narrator's clips (the clock is stepped,
  the story waits to be begun, the api holds :video). -> the api map."
  [^js el ui data0 rd video]
  (when video (clock/start-stepping!))
  (let [{:keys [^js st] :as sc} (make-scene el ui data0 video)
        input (listen! sc)]
    (set! (.-reader st) rd)
    (when video
      (set! (.-sMuted st) (empty? (:seconds video)))
      (set! (.-sAutoplay st) true))
    (size! sc)
    (set-quality! sc (:quality @ui))
    (set-data! sc data0)
    {:set! #(set-data! sc %)
     :fit! (fn [] (fit! sc) (want! sc))
     :play! (fn [p] (set! (.-on st) p) (want! sc))
     :speed! (fn [s] (set! (.-rate st) s))
     :scan! #(scan! sc)
     :select! #(select! sc %)
     :quality! #(set-quality! sc %)
     :style! #(set-style! sc %)
     :video (when video (video-api sc))
     :story (story-api sc)
     :destroy! #(destroy! sc input)}))
