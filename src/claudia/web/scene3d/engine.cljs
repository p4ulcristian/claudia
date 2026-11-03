(ns claudia.web.scene3d.engine
  "The three.js side of the scene3d form: parts, people, arrows and labels from the form's data, an
  optional script (QuickJS) that adds parts and moves them every frame, the story (steps that show
  parts, aim the camera, light parts up, pose people, with a narrator), the overlap check, and the
  orbit view (drag turns, wheel or pinch zooms, right drag or two fingers pan). The page's UI state
  lives in the `ui` atom the view hands in; this writes to it."
  (:require ["three" :as THREE]
            ["three/examples/jsm/controls/OrbitControls.js" :refer [OrbitControls]]
            ["three/examples/jsm/renderers/CSS2DRenderer.js" :refer [CSS2DObject CSS2DRenderer]]
            ["three/examples/jsm/environments/RoomEnvironment.js" :refer [RoomEnvironment]]
            [claudia.web.scene3d.ink :as ink]
            [claudia.web.scene3d.overlap :as overlap]
            [claudia.web.scene3d.person :as person]
            [claudia.web.scene3d.story :as story]
            [claudia.web.scene3d.vm :as vm]
            [claudia.web.scene3d.voice :as voice]))

(def ^:private deg (/ js/Math.PI 180))
(def speeds [0.25 0.5 1 2])
(def ^:private UP (THREE/Vector3. 0 1 0))
(def ^:private scan-seconds 12)
(def voice-key "claudia.scene3d.voice")

(defn- lst [v] (if (sequential? v) v []))
(defn- V
  ([v] (V v [0 0 0]))
  ([v d] (if (and (sequential? v) (>= (count v) 3))
           (mapv #(let [n (js/Number %)] (if (js/isNaN n) 0 n)) (take 3 v))
           d)))
(defn- v3 [[x y z]] (THREE/Vector3. x y z))
(defn- size-of [s] (if (number? s) [s s s] (V s [1 1 1])))
(defn- id-str [k] (if (keyword? k) (subs (str k) 1) (str k)))

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

(defn- geometry-of
  "Unit-sized geometry per shape; size scales it. Extrude, lathe and tube build from the points."
  [p]
  (let [pts2 (map (fn [q] (THREE/Vector2. (js/Number (nth q 0 0)) (js/Number (nth q 1 0)))) (lst (:points p)))]
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
      "ring" (.rotateX (THREE/RingGeometry. 0.3 0.5 48) (/ js/Math.PI -2))
      "extrude" (if (< (count pts2) 3)
                  (THREE/BoxGeometry. 1 1 1)
                  (.center (THREE/ExtrudeGeometry. (THREE/Shape. (into-array pts2)) #js {:depth 1 :bevelEnabled false})))
      "lathe" (if (>= (count pts2) 2) (THREE/LatheGeometry. (into-array pts2) 48) (THREE/SphereGeometry. 0.5 32 16))
      "tube" (let [path (map #(v3 (V %)) (lst (:points p)))]
               (if (< (count path) 2)
                 (THREE/BoxGeometry. 1 1 1)
                 (THREE/TubeGeometry. (THREE/CatmullRomCurve3. (into-array path)) (* 16 (count path))
                                      (or (js/Number (:radius p)) 0.08) 12 false)))
      (THREE/BoxGeometry. 1 1 1))))

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

(defn- person-scale
  "A person's size is their height; the model is person/height tall."
  [size]
  (let [h (cond (number? size) size (sequential? size) (js/Number (second size)) :else js/NaN)]
    (if (and (js/isFinite h) (pos? h)) (/ h person/height) 1)))

(defn- set-rot! [^js m r]
  (let [[x y z] (V r)] (.set (.-rotation m) (* x deg) (* y deg) (* z deg))))

;; An entry: one part. A JS object, since the frame loop changes it in place:
;; mesh spec radius k (appeared 0..1) dim (faded back 0..1) lit notes outline label lift person poseKey.

(defn- centre
  "A part's centre now, a person's joint (\"<person>.<joint>\"), or a point."
  [by-id e]
  (cond
    (string? e)
    (let [dot (.indexOf e ".")
          j (when (pos? dot)
              (when-let [^js en (get @by-id (subs e 0 dot))]
                (when-let [p (.-person en)] ((:joint p) (subs e (inc dot))))))]
      (if j
        (.getWorldPosition ^js j (THREE/Vector3.))
        (when-let [^js en (get @by-id e)]
          (.getCenter (.setFromObject (THREE/Box3.) (.-mesh en)) (THREE/Vector3.)))))
    (sequential? e) (v3 (V e))
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

(defn- along
  "Where a point s along a polyline is, and which way it runs there."
  [pts s]
  (loop [i 0 rest-s s]
    (if (>= i (dec (count pts)))
      {:p (.clone ^js (first pts)) :d (.clone UP)}
      (let [^js a (nth pts i)
            seg (.sub (.clone ^js (nth pts (inc i))) a)
            len (.length seg)]
        (if (or (<= rest-s len) (= i (- (count pts) 2)))
          {:p (.addScaledVector (.clone a) (.normalize (.clone seg)) (min rest-s len)) :d seg}
          (recur (inc i) (- rest-s len)))))))

(defn- make-arrow
  "An arrow follows its ends (a part, or a point), optionally through via points; flow: true makes
  it a stream of dashes running along it."
  [host ^js root by-id a]
  (let [k (atom 1)
        now (atom 0)
        color (color-of host (:color a) "fg")
        via (mapv #(v3 (V %)) (lst (:via a)))
        flow? (true? (:flow a))
        mat (THREE/MeshBasicMaterial. #js {:color color :transparent true})
        r (if flow? 0.02 0.035)
        segs (vec (for [_ (range (inc (count via)))]
                    (let [m (THREE/Mesh. (THREE/CylinderGeometry. r r 1 10) mat)] (.add root m) m)))
        tip (let [m (THREE/Mesh. (THREE/ConeGeometry. 0.35 1 16) mat)] (.add root m) m)
        dashes (vec (for [_ (range (if flow? 16 0))]
                      (let [m (THREE/Mesh. (THREE/CylinderGeometry. 0.06 0.06 1 10) mat)] (.add root m) m)))
        ^js label (when (:label a)
                    (let [l (CSS2DObject. (label-el (str (:label a)) nil (str "#" (.getHexString color))))]
                      (.add (.. l -element -classList) "arrow")
                      (.add root l)
                      l))
        update! (fn update!
                  ([] (update! nil))
                  ([seconds]
                   (when seconds (reset! now seconds))
                   (let [f0 (if (seq via) (first via) (centre by-id (:to a)))
                         t0 (if (seq via) (peek via) (centre by-id (:from a)))
                         start (when (and f0 (centre by-id (:from a))) (surface by-id (:from a) f0))
                         end (when (and t0 (centre by-id (:to a))) (surface by-id (:to a) t0))
                         pts (if (and start end) (vec (concat [start] via [end])) [])
                         total (reduce + 0 (map (fn [^js p q] (.distanceTo p q)) pts (rest pts)))
                         show (and (> total 0.05) (> @k 0.003))]
                     (doseq [^js m (concat segs [tip] dashes)] (set! (.-visible m) show))
                     (when label (set! (.-visible label) show))
                     (when show
                       (let [head (min 0.4 (* total 0.35))]
                         (doseq [[i ^js s] (map-indexed vector segs)]
                           (let [^js a0 (nth pts i)
                                 b0 (.clone ^js (nth pts (inc i)))
                                 dir (.sub (.clone b0) a0)
                                 len (.length dir)
                                 _ (.normalize dir)
                                 stop (if (= i (dec (count segs))) (max (- len head) 0.001) len)]
                             (.set (.-scale s) 1 stop 1)
                             (.addScaledVector (.copy (.-position s) a0) dir (/ stop 2))
                             (.setFromUnitVectors (.-quaternion s) UP dir)))
                         (let [td (.normalize (.sub (.clone ^js (peek pts)) (nth pts (- (count pts) 2))))]
                           (.set (.-scale tip) head head head)
                           (.addScaledVector (.copy (.-position tip) (peek pts)) td (/ head -2))
                           (.setFromUnitVectors (.-quaternion tip) UP td))
                         (doseq [[i ^js dm] (map-indexed vector dashes)]
                           (let [s (* (mod (+ (/ i (count dashes)) (* @now 0.3)) 1) (- total head))
                                 {:keys [p d]} (along pts s)]
                             (.set (.-scale dm) 1 0.2 1)
                             (.copy (.-position dm) p)
                             (.setFromUnitVectors (.-quaternion dm) UP (.normalize (.clone ^js d)))))
                         (when label (.copy (.-position label) (:p (along pts (/ total 2))))))))))]
    (update!)
    {:id (str (or (:id a) ""))
     :update! update!
     :flows? flow?
     :set! (fn [v]
             (reset! k v)
             (set! (.-opacity mat) v)
             (when label (set! (.. label -element -style -opacity) (str v)))
             (update!))}))

(defn- apply-reveal!
  "How far a part has appeared (0 hidden .. 1 there): a fade, with its label and outline. dim fades
  the part back (the spotlight leaves the others faint); lit thickens its outline."
  [^js e k]
  (set! (.-k e) k)
  (let [^js m (.-mesh e)
        spec (.-spec e)]
    (when (not= "group" (:shape spec))
      (let [^js mat (.-material m)
            op (let [o (.. mat -userData -op)] (if (number? o) o 1))
            dim (.-dim e)]
        (set! (.-visible m) (> k 0.003))
        (set! (.-castShadow m) (and (.. m -userData -casts) (> k 0.5) (> dim 0.5)))
        (set! (.-opacity mat) (* op k dim))
        (set! (.-transparent mat) (or (< op 1) (< k 1) (< dim 1)))
        (when-let [p (.-person e)] ((:reveal! p) k dim (.-lit e) op))
        (when-let [o (.-outline e)] (ink/set-outline! o k dim (.-lit e) op))
        (doseq [^js c (.-children m)]
          (cond
            (instance? CSS2DObject c)
            (do (set! (.-visible c) (and (> k 0.05) (> dim 0.5)))
                (set! (.. c -element -style -opacity) (str k))
                (.toggle (.. c -element -classList) "s3-noted" (boolean (.-notes e))))
            (.-isLineSegments c) (set! (.. c -material -opacity) (* 0.35 k))))))))

(defn- apply-override!
  "What a script's frame() says about one part."
  [host ^js e o]
  (let [^js m (.-mesh e)
        spec (.-spec e)]
    (if-let [p (.-person e)]
      (do (when (:pose o) ((:set-pose! p) (:pose o) 0))
          (when (or (:color o) (:look o)) ((:recolour! p) o))
          (when (:pos o) (let [[x y z] (V (:pos o))] (.set (.-position m) x y z)))
          (when (:rot o) (set-rot! m (:rot o)))
          (when (some? (:size o)) (.setScalar (.-scale m) (person-scale (:size o))))
          (when (number? (:opacity o)) (set! (.. m -material -userData -op) (:opacity o))))
      (do (if (= "rod" (:shape spec))
            (when (or (:from o) (:to o))
              (place-rod! m (V (or (:from o) (:from spec))) (V (or (:to o) (:to spec))) (.-radius e)))
            (do (when (:pos o) (let [[x y z] (V (:pos o))] (.set (.-position m) x y z)))
                (when (:rot o) (set-rot! m (:rot o)))
                (when (and (some? (:size o)) (not= "tube" (:shape spec)))
                  (let [[x y z] (size-of (:size o))] (.set (.-scale m) x y z))
                  (set! (.-lift e) js/NaN))))
          (when (:color o) (.copy (.. m -material -color) (color-of host (:color o))))
          (when (number? (:opacity o)) (set! (.. m -material -userData -op) (:opacity o)))))
    (apply-reveal! e (.-k e))))

(defn- build-parts
  "Every part's mesh (people need the loaded model), parented as the data says."
  [host ^js root parts style people]
  (let [by-id (atom {})]
    (doseq [p parts]
      (let [has-material? (contains? ink/presets (:material p))
            tint (when (or (:color p) (not has-material?)) (color-of host (:color p)))
            base-op (if (= "plane" (:shape p)) 0.35 1)
            ^js mat (if (= style "diagram")
                      (let [^js m (ink/material-for tint p base-op)
                            t (ink/toon-material (.-color m) (.. m -userData -op) (:wire p))]
                        (.dispose m)
                        t)
                      (ink/material-for tint p base-op))
            color (.-color mat)
            ^js mesh (THREE/Mesh. (geometry-of p) mat)
            shape (:shape p)
            group? (= "group" shape)
            radius (or (let [r (js/Number (:radius p))] (when (pos? r) r)) 0.08)]
        (set! (.. mesh -userData -id) (str (:id p)))
        (set! (.. mesh -userData -casts) (and (not group?) (overlap/solid? p)))
        (set! (.-castShadow mesh) (.. mesh -userData -casts))
        (set! (.-receiveShadow mesh) (and (not group?) (not (:ghost p))))
        (when (or group? (= "person" shape)) (set! (.-visible mat) false))
        (if (= "rod" shape)
          (place-rod! mesh (V (:from p)) (V (:to p)) radius)
          (do (cond (= "person" shape) (.setScalar (.-scale mesh) (person-scale (:size p)))
                    (not= "tube" shape) (let [[x y z] (size-of (:size p))] (.set (.-scale mesh) x y z)))
              (let [[x y z] (V (:pos p))] (.set (.-position mesh) x y z))
              (set-rot! mesh (:rot p))))
        (let [^js e #js {:mesh mesh :spec p :radius radius :k 1 :dim 1 :lit false :notes true
                         :outline nil :label nil :lift js/NaN :person nil :poseKey nil}]
          (swap! by-id assoc (str (:id p)) e)
          (when (and (= style "diagram") (not group?) (not= "person" shape) (not= false (:outline p))
                     (not (and (:ghost p) (= "sphere" shape))))
            (let [^js o (THREE/Mesh. (.-geometry mesh) (ink/outline-material))]
              (set! (.. o -userData -outline) true)
              (.add mesh o)
              (set! (.-outline e) o)))
          (when (and (= style "real") (not has-material?) (not (:wire p))
                     (not (#{"plane" "ring" "rod" "tube" "group" "person"} shape)))
            (.add mesh (THREE/LineSegments. (THREE/EdgesGeometry. (.-geometry mesh) 30)
                                            (THREE/LineBasicMaterial. #js {:color 0x000000 :transparent true :opacity 0.35}))))
          (when (and (:label p) (not group?))
            (.computeBoundingBox (.-geometry mesh))
            (let [l (CSS2DObject. (label-el (str (:label p)) (:note p) (str "#" (.getHexString color))))]
              (.add mesh l)
              (set! (.-label e) l))))))
    ;; a person: the model, posed (its hands may reach for parts, found once all are made)
    (doseq [[id ^js e] @by-id
            :when (and (= "person" (:shape (.-spec e))) people)]
      (set! (.-person e) (person/make (.-mesh e) people id style #(color-of host %) #(centre by-id %) (.-spec e)))
      (set! (.-poseKey e) (pr-str (or (:pose (.-spec e)) "stand"))))
    ;; a part with a parent moves, turns and scales with it; "<person>.<joint>" hangs it from a joint
    (doseq [[_ ^js e] @by-id]
      (let [par (str (or (:parent (.-spec e)) ""))
            dot (.indexOf par ".")
            joint (when (pos? dot)
                    (when-let [^js pe (get @by-id (subs par 0 dot))]
                      (when-let [p (.-person pe)] ((:joint p) (subs par (inc dot))))))
            ^js parent (or joint (some-> ^js (get @by-id par) .-mesh) root)]
        (.add parent (.-mesh e))))
    by-id))

(def ^:private lc (THREE/Vector3.))
(def ^:private lb (THREE/Box3.))

(defn- place-label!
  "A part's label sits above its centre by half its height, measured once upright, so a turning part
  does not swing its label around."
  [^js e]
  (let [^js l (.-label e)
        ^js m (.-mesh e)]
    (when (and l (.-visible m))
      (if-let [p (.-person e)]
        (.copy (.-position l) (.worldToLocal m ((:label-point p) lc)))
        (let [^js box (.. m -geometry -boundingBox)]
          (when (js/isNaN (.-lift e))
            (set! (.-lift e) (+ 0.2 (/ (.-y (.getSize (.applyMatrix4 (.copy lb box) (.-matrixWorld m)) lc)) 2))))
          (.localToWorld m (.getCenter box lc))
          (set! (.-y lc) (+ (.-y lc) (.-lift e)))
          (.copy (.-position l) (.worldToLocal m lc)))))))

(defn- dispose! [^js root]
  (.traverse root (fn [^js o]
                    (some-> (.-geometry o) .dispose)
                    (let [mat (.-material o)]
                      (if (array? mat) (doseq [^js x mat] (.dispose x)) (some-> ^js mat .dispose)))
                    (when (instance? CSS2DObject o) (.remove (.-element o)))))
  (.clear root))

(defn- pair-key [[a b]] (str a " × " b))

(defn start!
  "Builds the view inside `el` (which holds a .s3-stage). ui: the view's state atom. -> the api map."
  [^js el ui data0]
  (let [stage (.querySelector el ".s3-stage")
        scene (THREE/Scene.)
        camera (THREE/PerspectiveCamera. 45 1 0.05 500)
        renderer (THREE/WebGLRenderer. #js {:antialias true :alpha true})
        labels (CSS2DRenderer.)
        _ (do (.setPixelRatio renderer (min js/devicePixelRatio 2))
              (set! (.-toneMapping renderer) THREE/ACESFilmicToneMapping)
              (set! (.-toneMappingExposure renderer) 1.05)
              (set! (.. renderer -shadowMap -enabled) true)
              (set! (.. renderer -shadowMap -type) THREE/PCFShadowMap)
              (set! (.. labels -domElement -className) "s3-labels")
              (.append stage (.-domElement renderer) (.-domElement labels)))
        ;; light: a soft room around everything (reflections), one sun for the shadows
        pmrem (THREE/PMREMGenerator. renderer)
        env-texture (.-texture (.fromScene pmrem (RoomEnvironment.) 0.04))
        hemi (THREE/HemisphereLight. 0xffffff 0x444444 0.35)
        sun (THREE/DirectionalLight. 0xffffff 2.1)
        grid (THREE/GridHelper. 20 20 0x888888 0x555555)
        ground (THREE/Mesh. (.rotateX (THREE/PlaneGeometry. 80 80) (/ js/Math.PI -2)) (THREE/ShadowMaterial. #js {:opacity 0.3}))
        root (THREE/Group.)
        controls (OrbitControls. camera (.-domElement labels))
        ^js st #js {:high (= "high" (:quality @ui)) :style (:style @ui) :data data0
                :box (THREE/Box3.) :fitted false
                :script nil :arrows [] :steps []
                :sIdx -1 :sPlaying false :sElapsed 0 :sDur 0 :sToken 0 :sTick 0 :sLast 0
                :sMuted (not= "on" (js/localStorage.getItem voice-key)) :sInstruction nil :sAutoplay false
                :focus #{} :cam nil :checker nil :flagged "" :bad #{} :chosen nil
                :frames 0 :scanSeconds scan-seconds
                :t 0 :last 0 :rate (speeds 2) :on true :visible true :token 0 :raf 0}
        by-id (atom {})
        k-of (atom {})
        fades (atom [])
        dim-target (atom {})
        ever-seen (atom #{})
        shifts (atom {})
        the-voice (voice/make)
        voice-ready (voice/available?)
        ui! (fn [& kvs] (apply swap! ui assoc kvs))
        raf-want (atom nil)
        want! (fn [] (@raf-want))]
    (set! (.-environment scene) env-texture)
    (set! (.-environmentIntensity scene) 0.9)
    (.add scene hemi)
    (.set (.. sun -shadow -mapSize) 2048 2048)
    (set! (.. sun -shadow -bias) -0.0004)
    (set! (.. sun -shadow -normalBias) 0.03)
    (set! (.. sun -shadow -radius) 4)
    (.add scene sun (.-target sun))
    (set! (.. grid -material -transparent) true)
    (set! (.. grid -material -opacity) 0.35)
    (.add scene grid)
    (set! (.-receiveShadow ground) true)
    (.add scene ground)
    (.add scene root)
    (set! (.-enableDamping controls) true)
    (set! (.-dampingFactor controls) 0.12)
    (set! (.-zoomSpeed controls) 0.9)
    (letfn [(diagram? [] (= "diagram" (.-style st)))
            (size! []
              (let [w (.-clientWidth stage) h (.-clientHeight stage)]
                (.setSize renderer w h)
                (.setSize labels w h)
                (.set (.-value ^js ink/viewport) (max w 1) (max h 1))
                (set! (.-aspect camera) (/ w (max h 1)))
                (.updateProjectionMatrix camera)
                (want!)))
            ;; the diagram style: flat light, no reflections, no shadows; the realistic one: room, sun, shadows
            (apply-style! []
              (let [d (diagram?)]
                (set! (.-toneMapping renderer) (if d THREE/NoToneMapping THREE/ACESFilmicToneMapping))
                (set! (.-environment scene) (if d nil env-texture))
                (set! (.-intensity hemi) (if d 1.25 0.35))
                (set! (.-intensity sun) (if d 1.5 2.1))
                (set! (.-castShadow sun) (and (not d) (.-high st)))
                (set! (.-visible ground) (and (not d) (.-high st)))))
            (set-quality! [q]
              (set! (.-high st) (= q "high"))
              (apply-style!)
              (.setPixelRatio renderer (if (or (.-high st) (diagram?)) (min js/devicePixelRatio 2) 1))
              (size!))
            (fit! []
              (let [^js box (.-box st)]
                (when (.isEmpty box) (.set box (THREE/Vector3. -2 0 -2) (THREE/Vector3. 2 2 2)))
                (let [c (.getCenter box (THREE/Vector3.))
                      r (max (/ (.length (.getSize box (THREE/Vector3.))) 2) 1)
                      dist (* 0.95 (/ r (js/Math.sin (/ (* (.-fov camera) deg) 2))))]
                  (.copy (.-target controls) c)
                  (.add (.copy (.-position camera) c) (.multiplyScalar (.normalize (THREE/Vector3. 0.7 0.55 1)) dist))
                  (.update controls))))
            ;; the sun and the floor follow the scene's size
            (fit-light! []
              (let [^js box (.-box st)
                    c (.getCenter box (THREE/Vector3.))
                    r (max (/ (.length (.getSize box (THREE/Vector3.))) 2) 2)
                    ^js cam (.. sun -shadow -camera)]
                (.add (.copy (.-position sun) c) (.multiplyScalar (.normalize (THREE/Vector3. 0.45 1 0.55)) (* r 2.2)))
                (.copy (.. sun -target -position) c)
                (set! (.-left cam) (* r -1.15)) (set! (.-right cam) (* r 1.15))
                (set! (.-top cam) (* r 1.15)) (set! (.-bottom cam) (* r -1.15))
                (set! (.-near cam) 0.1) (set! (.-far cam) (* r 5))
                (.updateProjectionMatrix cam)
                (let [y (- (.. box -min -y) 0.01)]
                  (set! (.. ground -position -y) y)
                  (set! (.. grid -position -y) y))))
            (stop-script! [] (when-let [s (.-script st)] ((:close s)) (set! (.-script st) nil)))
            ;; red glow for overlapping parts, blue for the clicked one, amber pulse for focus (real)
            (paint! ([] (paint! 0.5))
              ([pulse]
               (doseq [[id ^js e] @by-id]
                 (let [^js m (.. e -mesh -material)
                       bad? ((.-bad st) id)
                       chosen? (= id (.-chosen st))
                       lit? (and (not (diagram?)) ((.-focus st) id) (not bad?) (not chosen?))
                       hex (cond bad? 0xff2a2a chosen? 0x2f6dff lit? 0xffc247 :else 0x000000)
                       strength (cond bad? 0.9 lit? (+ 0.2 (* 0.6 pulse)) :else 0.7)]
                   (when-let [em (.-emissive m)] (.setHex em hex) (set! (.-emissiveIntensity m) strength))
                   (when-let [p (.-person e)] ((:emissive! p) hex (if (= hex 0x2f6dff) 0.35 strength)))))
               (want!)))
            (run-check! []
              (when-let [c (.-checker st)]
                (let [hits ((:check c) #(> (get @k-of % 1) 0.99))
                      key (apply str (interpose "|" (map pair-key hits)))]
                  (when (not= key (.-flagged st))
                    (set! (.-flagged st) key)
                    (set! (.-bad st) (set (flatten hits)))
                    (paint!)
                    (let [new (remove @ever-seen (map pair-key hits))]
                      (swap! ever-seen into new)
                      (ui! :now (mapv pair-key hits))
                      (when (seq new) (ui! :seen (vec @ever-seen))))
                    (want!)))))
            (apply-frame! [o]
              (doseq [[k v] o :let [^js e (get @by-id (id-str k))] :when (and e (map? v))]
                (apply-override! el e v)))
            ;; run a whole stretch of the script without drawing, to catch overlaps that come later
            (scan! []
              (let [s (.-script st)]
                (if (or (not (.-checker st)) (not (:frame s)))
                  (run-check!)
                  (do (try
                        (doseq [t (range 0 (+ (.-scanSeconds st) 1e-9) (/ 1 30))]
                          (apply-frame! ((:frame s) t (.-sIdx st)))
                          (run-check!))
                        (catch :default e (ui! :error (.-message e))))
                      (move-parts!)
                      (run-check!)
                      (ui! :scanned true)))))
            (move-parts! []
              (when-let [f (:frame (.-script st))]
                (try
                  (apply-frame! (f (.-t st) (.-sIdx st)))
                  (.updateMatrixWorld root true)
                  (doseq [a (.-arrows st)] ((:update! a)))
                  (set! (.-frames st) (inc (.-frames st)))
                  (when (even? (.-frames st)) (run-check!))
                  (catch :default e
                    (ui! :error (.-message e) :animated false)
                    (stop-script!)))))
            (set-data! [d]
              (let [mine (inc (.-token st))]
                (set! (.-token st) mine)
                (set! (.-data st) d)
                (ui! :legend (filterv #(and (map? %) (string? (:label %))) (lst (:legend d))) :error "")
                (stop-script!)
                (-> (if (and (string? (:script d)) (seq (.trim (:script d))))
                      (-> (vm/load-script (:script d))
                          (.then (fn [s]
                                   (if (not= mine (.-token st))
                                     (do ((:close s)) nil)
                                     (do (set! (.-script st) s) ((:build s))))))
                          (.catch (fn [e] (when (= mine (.-token st)) (ui! :error (.-message e))) {})))
                      (js/Promise.resolve {}))
                    (.then (fn [extra]
                             (when (= mine (.-token st))
                               (let [extra (if (map? extra) extra {})
                                     all (vec (concat (lst (:parts d)) (lst (:parts extra))))]
                                 (-> (if (some #(= "person" (:shape %)) all)
                                       (.catch (person/load-model)
                                               (fn [e] (ui! :error (str "person model: " (.-message e))) nil))
                                       (js/Promise.resolve nil))
                                     (.then (fn [people]
                                              (when (= mine (.-token st))
                                                (build! d extra all people)))))))))
                    (.catch (fn [^js e] (js/console.error e) (ui! :error (str (.-message e))))))))
            (build! [d extra all people]
              (dispose! root)
              (reset! by-id @(build-parts el root (filterv map? all) (.-style st) people))
              (.updateMatrixWorld root true)
              (let [by-id-atom by-id]
                (set! (.-arrows st) (mapv #(make-arrow el root by-id-atom %)
                                          (filter map? (concat (lst (:arrows d)) (lst (:arrows extra)))))))
              (reset! k-of {})
              (reset! fades [])
              (set! (.-checker st) (overlap/make-checker root (for [[id ^js e] @by-id] {:id id :mesh (.-mesh e) :spec (.-spec e)})
                                                         (concat (lst (:allow d)) (lst (:allow extra)))))
              (set! (.-scanSeconds st) (if (pos? (js/Number (:scan d))) (js/Number (:scan d)) scan-seconds))
              (reset! ever-seen #{})
              (ui! :solids (:count (.-checker st)) :seen [] :now [] :scanned false)
              (set! (.-flagged st) "")
              (run-check!)
              (set! (.-box st) (.setFromObject (THREE/Box3.) root))
              (fit-light!)
              (when-not (.-fitted st) (fit!) (set! (.-fitted st) true))
              (ui! :animated (boolean (:frame (.-script st))))
              (set! (.-last st) 0)
              (new-story! d)
              (want!))
            ;; ---- the story
            (set-reveal! [id k]
              (swap! k-of assoc id k)
              (if-let [e (get @by-id id)]
                (apply-reveal! e k)
                (when-let [a (some #(when (= id (:id %)) %) (.-arrows st))] ((:set! a) k))))
            (all-ids [] (concat (keys @by-id) (filter seq (map :id (.-arrows st)))))
            ;; which parts are on after step n; the ones that just came on fade in
            (apply-story-state! [n animate?]
              (let [ids (all-ids)
                    on (story/visible-at (.-steps st) n ids)
                    wall (js/performance.now)
                    order (atom 0)]
                (doseq [id ids]
                  (let [to (if (on id) 1 0)
                        now (get @k-of id 1)
                        queued? (some #(= id (:id %)) @fades)]
                    (when queued? (swap! fades (fn [fs] (filterv #(not= id (:id %)) fs))))
                    (cond
                      (and (= to now) (not queued?)) (swap! k-of assoc id to)
                      animate? (do (swap! fades conj {:id id :from now :to to
                                                      :at (+ wall (min (* 35 @order) 700))
                                                      :dur (if (pos? to) 700 400)})
                                   (swap! order inc))
                      :else (set-reveal! id to))))
                (.updateMatrixWorld root true)
                (doseq [a (.-arrows st)] ((:update! a)))
                (want!)))
            (box-of [patterns]
              (let [b (THREE/Box3.)]
                (doseq [[id ^js e] @by-id
                        :when (and (not= "group" (:shape (.-spec e))) (story/matches-any? patterns id))]
                  (.union b (.setFromObject (THREE/Box3.) (.-mesh e))))
                b))
            (move-camera! [c]
              (.updateMatrixWorld root true)
              (let [target (THREE/Vector3.)
                    dist (atom (.distanceTo (.-position camera) (.-target controls)))
                    ^js fb (when (seq (:frame c)) (box-of (:frame c)))]
                (cond
                  (and fb (not (.isEmpty fb)))
                  (do (.getCenter fb target)
                      (reset! dist (* 0.95 (/ (max (/ (.length (.getSize fb (THREE/Vector3.))) 2) 0.6)
                                              (js/Math.sin (/ (* (.-fov camera) deg) 2))))))
                  (string? (:target c)) (let [^js b (box-of [(:target c)])]
                                          (if (.isEmpty b) (.copy target (.-target controls)) (.getCenter b target)))
                  (sequential? (:target c)) (let [[x y z] (V (:target c))] (.set target x y z))
                  :else (.copy target (.-target controls)))
                (when (:dist c) (reset! dist (:dist c)))
                (let [dir (.sub (.clone (.-position camera)) (.-target controls))]
                  (when (< (.lengthSq dir) 1e-6) (.set dir 0.7 0.55 1))
                  (when (or (some? (:az c)) (some? (:el c)))
                    (let [az (* (or (:az c) 30) deg) elv (* (or (:el c) 25) deg)]
                      (.set dir (* (js/Math.sin az) (js/Math.cos elv)) (js/Math.sin elv) (* (js/Math.cos az) (js/Math.cos elv)))))
                  (.normalize dir)
                  (set! (.-cam st) {:at (js/performance.now) :dur 1200
                                    :p0 (.clone (.-position camera)) :t0 (.clone (.-target controls))
                                    :p1 (.addScaledVector (.clone target) dir @dist) :t1 target}))
                (want!)))
            ;; the spotlight: with a focus, the other parts fade back; the focused get a thick outline
            (set-spotlight! []
              (let [on (and (diagram?) (seq (.-focus st)))]
                (doseq [[id ^js e] @by-id]
                  (let [lit (boolean (and on ((.-focus st) id)))
                        notes (or (empty? (.-steps st)) lit)]
                    (swap! dim-target assoc id (if (and on (not lit)) 0.16 1))
                    (when (or (not= lit (.-lit e)) (not= notes (.-notes e)))
                      (set! (.-lit e) lit)
                      (set! (.-notes e) notes)
                      (apply-reveal! e (.-k e)))))
                (want!)))
            (stop-tick! [] (js/clearInterval (.-sTick st)) (set! (.-sTick st) 0))
            (end-story! [] (set! (.-sPlaying st) false) (stop-tick!) (ui! :playing false))
            (focus-of [step] (set (filter #(story/matches-any? (:focus step) %) (keys @by-id))))
            (start-step! [n animate?]
              (when (< -1 n (count (.-steps st)))
                (let [mine (inc (.-sToken st))
                      step (nth (.-steps st) n)]
                  (set! (.-sToken st) mine)
                  ((:stop the-voice))
                  (set! (.-sIdx st) n) (set! (.-sElapsed st) 0) (set! (.-sDur st) 0)
                  (ui! :prog 0 :idx n :sub (:say step))
                  (apply-story-state! n animate?)
                  (pose-people! n (if animate? (:move step) 0))
                  (set! (.-focus st) (focus-of step))
                  (set-spotlight!)
                  (when (empty? (.-focus st)) (paint!))
                  (when (:camera step) (move-camera! (:camera step)))
                  (let [est (story/estimate-seconds step)
                        go (fn [seconds]
                             (when (= mine (.-sToken st))
                               (set! (.-sDur st) (+ seconds story/gap-seconds))
                               (set! (.-sLast st) (js/performance.now))
                               (when (and (.-sPlaying st) (zero? (.-sTick st)))
                                 (set! (.-sTick st) (js/setInterval tick! 100)))
                               (want!)))]
                    (if (and (seq (:say step)) (not (.-sMuted st)))
                      (-> voice-ready
                          (.then (fn [ready]
                                   (if (and ready (= mine (.-sToken st)))
                                     (-> (js/Promise.race #js [((:load the-voice) (:say step) (.-sInstruction st))
                                                               (js/Promise. (fn [r] (js/setTimeout #(r nil) 12000)))])
                                         (.then (fn [clip]
                                                  (when (= mine (.-sToken st))
                                                    (if clip
                                                      (do (when (.-sPlaying st) ((:play the-voice) clip))
                                                          (go (max (:seconds clip) (:hold step))))
                                                      (go est))))))
                                     (go est)))))
                      (go est))))))
            (tick! []
              (let [now (js/performance.now)]
                (when (and (.-sPlaying st) (pos? (.-sDur st)))
                  (set! (.-sElapsed st) (+ (.-sElapsed st) (/ (- now (.-sLast st)) 1000))))
                (set! (.-sLast st) now)
                (when (pos? (.-sDur st)) (ui! :prog (min 1 (/ (.-sElapsed st) (.-sDur st)))))
                (when (and (.-sPlaying st) (pos? (.-sDur st)) (>= (.-sElapsed st) (.-sDur st)))
                  (if (< (inc (.-sIdx st)) (count (.-steps st)))
                    (start-step! (inc (.-sIdx st)) true)
                    (end-story!)))))
            (playing! [] (set! (.-sPlaying st) true) (ui! :playing true) ((:resume the-voice)))
            (story-play! []
              (when (seq (.-steps st))
                (set! (.-sPlaying st) true)
                (ui! :playing true)
                (if (or (neg? (.-sIdx st))
                        (and (>= (.-sIdx st) (dec (count (.-steps st)))) (pos? (.-sDur st)) (>= (.-sElapsed st) (.-sDur st))))
                  (do (apply-story-state! -1 false) (start-step! 0 true))
                  (do ((:resume the-voice))
                      (set! (.-sLast st) (js/performance.now))
                      (when (zero? (.-sTick st)) (set! (.-sTick st) (js/setInterval tick! 100)))
                      (when (zero? (.-sDur st)) (start-step! (.-sIdx st) false))))))
            (preload-voice! []
              (-> voice-ready
                  (.then (fn [ok]
                           (when ok
                             (let [lines (vec (distinct (filter seq (map :say (.-steps st)))))]
                               (letfn [(next! [n]
                                         (cond
                                           (.-sMuted st) nil
                                           (>= n (count lines)) (ui! :voice-state "voice ready")
                                           :else (do (ui! :voice-state (str "voice " n "/" (count lines)))
                                                     (-> ((:load the-voice) (nth lines n) (.-sInstruction st))
                                                         (.then (fn [clip]
                                                                  (if clip
                                                                    (next! (inc n))
                                                                    (ui! :voice-state (str "no voice: " @(:error the-voice))))))))))]
                                 (next! 0))))))))
            ;; people take the pose their part gives, or the latest the story gave them up to step n
            (pose-people! [n seconds]
              (doseq [[id ^js e] @by-id :when (.-person e)]
                (let [spec (or (story/pose-at (.-steps st) n id) (:pose (.-spec e)) "stand")
                      key (pr-str spec)]
                  (when (not= key (.-poseKey e))
                    (set! (.-poseKey e) key)
                    ((:set-pose! (.-person e)) spec seconds))))
              (want!))
            ;; after every build: the steps may have changed (Claude adds to the story while it plays)
            (new-story! [d]
              (let [steps (story/normalize (:story d))]
                (set! (.-steps st) steps)
                (set! (.-sInstruction st) (when (string? (:voice d)) (:voice d)))
                (ui! :story-len (count steps) :story steps :autoplay (:autoplay d))
                (if (empty? steps)
                  (do (set! (.-sIdx st) -1) (ui! :idx -1 :sub "")
                      (set! (.-focus st) #{}) (set-spotlight!) (paint!))
                  (do (when (>= (.-sIdx st) (count steps)) (set! (.-sIdx st) (dec (count steps))))
                      (apply-story-state! (.-sIdx st) false)
                      (pose-people! (.-sIdx st) 0)
                      (set! (.-focus st) (if (>= (.-sIdx st) 0) (focus-of (nth steps (.-sIdx st))) #{}))
                      (set-spotlight!)
                      ;; the voice is off until the user turns it on
                      (if (and (not= false (:narrate d)) (some (comp seq :say) steps))
                        (.then voice-ready (fn [ok]
                                             (cond (not ok) (ui! :voice-state "no voice here")
                                                   (.-sMuted st) (ui! :voice-state "voice off")
                                                   :else (preload-voice!))))
                        (ui! :voice-state ""))
                      (when (and (not= false (:autoplay d)) (not (.-sAutoplay st)) (neg? (.-sIdx st)))
                        (set! (.-sAutoplay st) true)
                        (story-play!))))))
            ;; labels that would sit on each other move apart, easing frame by frame
            (declutter! []
              (let [els (filter #(not= "none" (.. ^js % -style -display))
                                (array-seq (.querySelectorAll (.-domElement labels) ".s3-label")))
                    els-set (set els)
                    _ (doseq [[^js x] @shifts :when (not (els-set x))]
                        (swap! shifts dissoc x)
                        (set! (.. x -style -translate) ""))
                    rects (mapv #(.getBoundingClientRect ^js %) els)
                    placed (atom [])
                    easing (atom false)]
                (doseq [[i ^js x] (map-indexed vector els)]
                  (let [^js r (rects i)
                        s (or (get @shifts x) {:y 0 :to 0 :side 0})
                        top (- (.-top r) (:y s))
                        bottom (- (.-bottom r) (:y s))
                        [dy side] (loop [guard 0 dy 0 side (:side s)]
                                    (let [hit (when (< guard 10)
                                                (some #(when (and (< (.-left r) (:right %)) (> (.-right r) (:left %))
                                                                  (< (+ top dy) (:bottom %)) (> (+ bottom dy) (:top %)))
                                                         %)
                                                      @placed))]
                                      (if-not hit
                                        [dy side]
                                        (let [side (if (zero? side) (if (< (+ top bottom) (+ (:top hit) (:bottom hit))) -1 1) side)]
                                          (recur (inc guard)
                                                 (if (pos? side) (+ (- (:bottom hit) top) 3) (- (:top hit) bottom 3))
                                                 side)))))
                        to (max -90 (min 90 dy))]
                    (swap! shifts assoc x (assoc s :to to :side (if (zero? dy) 0 side)))
                    (swap! placed conj {:left (.-left r) :right (.-right r) :top (+ top to) :bottom (+ bottom to)})))
                (doseq [^js x els]
                  (let [s (get @shifts x)
                        y (if (< (js/Math.abs (- (:to s) (:y s))) 0.5)
                            (:to s)
                            (do (reset! easing true) (+ (:y s) (* 0.25 (- (:to s) (:y s))))))]
                    (swap! shifts assoc-in [x :y] y)
                    (set! (.. x -style -translate) (if (zero? y) "" (str "0 " (.toFixed y 1) "px")))))
                @easing))
            (draw! [now]
              (set! (.-raf st) 0)
              (let [again (atom false)
                    wall (js/performance.now)]
                (when (:frame (.-script st))
                  (when (and (.-on st) (.-visible st))
                    (set! (.-t st) (+ (.-t st) (if (pos? (.-last st)) (* (min (/ (- now (.-last st)) 1000) 0.1) (.-rate st)) 0)))
                    (reset! again true))
                  (move-parts!))
                (set! (.-last st) (if @again now 0))
                (when (seq @fades)
                  (doseq [f @fades]
                    (let [p (min 1 (max 0 (/ (- wall (:at f)) (:dur f))))]
                      (set-reveal! (:id f) (+ (:from f) (* (- (:to f) (:from f)) (- 1 (js/Math.pow (- 1 p) 3)))))))
                  (swap! fades (fn [fs] (filterv #(< (- wall (:at %)) (:dur %)) fs)))
                  (doseq [a (.-arrows st)] ((:update! a)))
                  (reset! again true))
                (when (some identity (doall (for [[_ ^js e] @by-id :when (.-person e)] ((:update! (.-person e)) wall))))
                  (doseq [a (.-arrows st)] ((:update! a)))
                  (reset! again true))
                (when-let [{:keys [at dur p0 t0 p1 t1]} (.-cam st)]
                  (let [p (min 1 (/ (- wall at) dur))
                        e (if (< p 0.5) (* 2 p p) (- 1 (/ (js/Math.pow (+ (* -2 p) 2) 2) 2)))]
                    (.lerpVectors (.-position camera) p0 p1 e)
                    (.lerpVectors (.-target controls) t0 t1 e)
                    (when (>= p 1) (set! (.-cam st) nil))
                    (reset! again true)))
                (when (and (seq (.-focus st)) (not (diagram?)))
                  (paint! (+ 0.5 (* 0.5 (js/Math.sin (/ wall 260)))))
                  (reset! again true))
                (doseq [[id ^js e] @by-id]
                  (let [to (get @dim-target id 1)]
                    (cond (> (js/Math.abs (- (.-dim e) to)) 0.01)
                          (do (set! (.-dim e) (+ (.-dim e) (* 0.2 (- to (.-dim e))))) (apply-reveal! e (.-k e)) (reset! again true))
                          (not= (.-dim e) to) (do (set! (.-dim e) to) (apply-reveal! e (.-k e))))))
                (when (and (.-visible st) (some :flows? (.-arrows st)))
                  (doseq [a (.-arrows st) :when (:flows? a)] ((:update! a) (/ wall 1000)))
                  (reset! again true))
                (when (.update controls) (reset! again true))
                (.updateMatrixWorld scene)
                (doseq [[_ e] @by-id] (place-label! e))
                (.render renderer scene camera)
                (.render labels scene camera)
                (when (declutter!) (reset! again true))
                (when @again (want!))))
            (pick! [cx cy]
              (let [^js r (.getBoundingClientRect (.-domElement labels))
                    ray (THREE/Raycaster.)
                    at (fn [dx dy] (.setFromCamera ray (THREE/Vector2. (- (* 2 (/ (- (+ cx dx) (.-left r)) (.-width r))) 1)
                                                                       (+ (* -2 (/ (- (+ cy dy) (.-top r)) (.-height r))) 1))
                                                   camera))
                    _ (.updateMatrixWorld root true)
                    meshes (into-array (mapcat (fn [[_ ^js e]]
                                                 (when (and (not= "group" (:shape (.-spec e))) (> (.-k e) 0.05))
                                                   (if-let [p (.-person e)] (:meshes p) [(.-mesh e)])))
                                               @by-id))
                    ;; thin parts are hard to hit: if the exact click finds nothing, try around it
                    hits (some (fn [[dx dy]] (at dx dy) (let [h (.intersectObjects ray meshes false)] (when (pos? (.-length h)) h)))
                               [[0 0] [6 0] [-6 0] [0 6] [0 -6] [10 10] [-10 10] [10 -10] [-10 -10]])
                    hit (when hits (or (some #(when (overlap/solid? (.-spec ^js (get @by-id (str (.. ^js % -object -userData -id))))) %) hits)
                                       (aget hits 0)))]
                (if-not hit
                  (do (select! nil) (ui! :hint "no part there"))
                  (let [id (str (.. ^js hit -object -userData -id))
                        ^js e (get @by-id id)
                        spec (.-spec e)
                        w (.getWorldPosition (.-mesh e) (THREE/Vector3.))
                        sz (if (= "rod" (:shape spec))
                             (str "length " (.toFixed (.distanceTo (v3 (V (:from spec))) (v3 (V (:to spec)))) 2))
                             (js/JSON.stringify (clj->js (or (:size spec) "—"))))
                        rows (cond-> [["id" id] ["shape" (str (or (:shape spec) "box"))] ["color" (str (or (:color spec) "blue"))]
                                      ["size" sz] ["at" (str (.toFixed (.-x w) 2) ", " (.toFixed (.-y w) 2) ", " (.toFixed (.-z w) 2))]]
                               (:parent spec) (conj ["part of" (str (:parent spec))])
                               true (conj ["solid" (if (overlap/solid? spec)
                                                     (if ((.-bad st) id) "yes, overlapping now" "yes")
                                                     "no (ghost/housing)")]))
                        ^js box (.getBoundingClientRect el)]
                    (select! id)
                    (ui! :picked {:id id :title (str (or (:label spec) id)) :x (- cx (.-left box)) :y (- cy (.-top box))
                                  :rows rows :note (:note spec) :shape (str (or (:shape spec) "box"))
                                  :color (str (or (:color spec) "blue")) :parent (:parent spec)})))))
            (select! [id]
              (set! (.-chosen st) id)
              (when-not id (ui! :picked nil))
              (paint!))]
      (reset! raf-want (fn [] (when (zero? (.-raf st)) (set! (.-raf st) (js/requestAnimationFrame draw!)))))
      (.addEventListener controls "start" #(set! (.-cam st) nil))
      (.addEventListener controls "change" want!)
      ;; a trackpad: two fingers sliding pan, a pinch (ctrl + wheel) zooms; a mouse wheel zooms
      (let [dolly! (fn [factor]
                     (let [offset (.sub (.clone (.-position camera)) (.-target controls))
                           dist (min 80 (max 0.5 (* factor (.length offset))))]
                       (.addScaledVector (.copy (.-position camera) (.-target controls)) (.normalize offset) dist)))
            on-wheel (fn [^js e]
                       (.preventDefault e)
                       (.stopPropagation e)
                       (set! (.-cam st) nil)
                       (let [lines? (= 1 (.-deltaMode e))
                             mouse? (or (not= 0 (.-deltaMode e))
                                        (and (zero? (.-deltaX e)) (js/Number.isInteger (.-deltaY e)) (>= (js/Math.abs (.-deltaY e)) 50)))]
                         (cond
                           (.-ctrlKey e) (dolly! (js/Math.exp (* 0.012 (.-deltaY e))))
                           mouse? (dolly! (js/Math.exp (* 0.1 (js/Math.sign (.-deltaY e)))))
                           :else (let [unit (if lines? 16 1)
                                       per-px (/ (* 2 (.distanceTo (.-position camera) (.-target controls)) (js/Math.tan (/ (* (.-fov camera) deg) 2)))
                                                 (max (.-clientHeight stage) 1))
                                       right (.setFromMatrixColumn (THREE/Vector3.) (.-matrix camera) 0)
                                       up (.setFromMatrixColumn (THREE/Vector3.) (.-matrix camera) 1)
                                       move (.addScaledVector (.multiplyScalar right (* (.-deltaX e) unit per-px)) up (* -1 (.-deltaY e) unit per-px))]
                                   (.add (.-position camera) move)
                                   (.add (.-target controls) move))))
                       (.update controls)
                       (want!))
            down (atom nil)
            on-down (fn [^js e] (reset! down [(.-clientX e) (.-clientY e)]))
            ;; a click is a press and release in place; dragging to turn is not
            on-click (fn [^js e]
                       (let [d @down]
                         (reset! down nil)
                         (when (and d (zero? (.-button e))
                                    (< (js/Math.hypot (- (.-clientX e) (first d)) (- (.-clientY e) (second d))) 5))
                           (pick! (.-clientX e) (.-clientY e)))))
            ro (js/ResizeObserver. size!)
            io (js/IntersectionObserver. (fn [entries] (set! (.-visible st) (.-isIntersecting ^js (aget entries 0))) (want!)))]
        (.addEventListener stage "wheel" on-wheel #js {:passive false :capture true})
        (.addEventListener (.-domElement labels) "pointerdown" on-down)
        (.addEventListener (.-domElement labels) "click" on-click)
        (.observe ro el)
        (.observe io el)
        (size!)
        (set-quality! (:quality @ui))
        (set-data! data0)
        {:set! set-data!
         :fit! (fn [] (fit!) (want!))
         :play! (fn [p] (set! (.-on st) p) (want!))
         :speed! (fn [s] (set! (.-rate st) s))
         :scan! scan!
         :select! select!
         :quality! set-quality!
         :style! (fn [s] (when (not= s (.-style st))
                           (set! (.-style st) s)
                           (apply-style!)
                           (set-quality! (if (.-high st) "high" "low"))
                           (set-data! (.-data st))))
         :story {:play story-play!
                 :pause (fn [] (set! (.-sPlaying st) false) (ui! :playing false) ((:pause the-voice)) (stop-tick!))
                 :next (fn [] (when (< (inc (.-sIdx st)) (count (.-steps st))) (playing!) (start-step! (inc (.-sIdx st)) true)))
                 :prev (fn [] (playing!) (start-step! (max 0 (dec (.-sIdx st))) false))
                 :goto (fn [n] (playing!) (start-step! n (= n (inc (.-sIdx st)))))
                 :mute (fn [m]
                         (set! (.-sMuted st) m)
                         (ui! :muted m)
                         (js/localStorage.setItem voice-key (if m "off" "on"))
                         (if m (do ((:stop the-voice)) (ui! :voice-state "voice off")) (preload-voice!)))}
         :destroy! (fn []
                     (set! (.-token st) (inc (.-token st)))
                     (js/cancelAnimationFrame (.-raf st))
                     (.disconnect ro)
                     (.disconnect io)
                     (.removeEventListener (.-domElement labels) "pointerdown" on-down)
                     (.removeEventListener (.-domElement labels) "click" on-click)
                     (.removeEventListener stage "wheel" on-wheel #js {:capture true})
                     (.dispose controls)
                     (.dispose env-texture)
                     (.dispose pmrem)
                     (stop-tick!)
                     ((:close the-voice))
                     (stop-script!)
                     (dispose! root)
                     (.dispose renderer)
                     (.remove (.-domElement renderer))
                     (.remove (.-domElement labels)))}))))
