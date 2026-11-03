(ns claudia.web.scene3d.person
  "A person in a scene3d: a rigged human (a CC0 MakeHuman body, /person/person.glb) posed live. A pose
  says where the hips, chest, hands and feet go (claudia.web.scene3d.poses); this bends the skeleton
  to reach them: the spine leans towards the chest point, arms and legs are two-bone chains solved
  exactly (the elbow or knee bends towards its pole), the head turns to what it looks at, feet lie
  flat on the floor unless told where the toes point. A new pose glides: every target moves from
  where it is to where it goes, the solver runs each frame."
  (:require ["three" :as THREE]
            ["three/examples/jsm/loaders/GLTFLoader.js" :refer [GLTFLoader]]
            ["three/examples/jsm/utils/SkeletonUtils.js" :as SkeletonUtils]
            [claudia.web.scene3d.ink :as ink]
            [claudia.web.scene3d.poses :as poses]))

(def height 1.75)
(def joints #{"hips" "chest" "head" "handL" "handR" "footL" "footR"})
(def ^:private deg (/ js/Math.PI 180))

(defonce ^:private asset (atom nil))

(defn load-model
  "The body, loaded once (a promise of the glTF scene)."
  []
  (or @asset
      (let [p (-> (.loadAsync (GLTFLoader.) "/person/person.glb")
                  (.then (fn [^js g] (.-scene g))))]
        (.catch p (fn [_] (reset! asset nil)))
        (reset! asset p))))

;; ---- small vector helpers ----

(defn- v [[x y z]] (THREE/Vector3. x y z))
(defn- arr [^js p] [(.-x p) (.-y p) (.-z p)])
(defn- ident [] (THREE/Quaternion.))
(defn- ease [p] (if (< p 0.5) (* 4 p p p) (- 1 (/ (js/Math.pow (+ (* -2 p) 2) 3) 2))))
(defn- lerp3 [[a b c] [x y z] t] [(+ a (* t (- x a))) (+ b (* t (- y b))) (+ c (* t (- z c)))])
(defn- perp [^js a ^js d] (.addScaledVector (.clone a) d (- (.dot a d))))

(defn- frame-turn
  "The rotation taking frame (d0, b0, d0 x b0) to (d1, b1, d1 x b1); b is made square to d first."
  [d0 b0 d1 b1]
  (let [basis (fn [^js d b]
                (let [^js bb (.normalize (perp b d))]
                  (.makeBasis (THREE/Matrix4.) d bb (.normalize (.cross (.clone d) bb)))))
        m (.multiply (basis d1 b1) (.transpose (basis d0 b0)))]
    (.setFromRotationMatrix (THREE/Quaternion.) m)))

(defn- clamp-turn [^js q max-angle]
  (let [angle (* 2 (js/Math.acos (min 1 (js/Math.abs (.-w q)))))]
    (if (> angle max-angle) (.slerp (ident) q (/ max-angle angle)) q)))

(defn- euler-q [[x y z]]
  (.setFromEuler (THREE/Quaternion.) (THREE/Euler. (* x deg) (* y deg) (* z deg) "XYZ")))

(def ^:private slots ["skin" "top" "bottom" "shoes" "hair" "eyes"])

(defn- slot-of [^js m]
  (let [^js mat (if (array? (.-material m)) (aget (.-material m) 0) (.-material m))
        n (.toLowerCase (str (some-> mat .-name) " " (.-name m)))]
    (or (some #(when (.includes n %) %) slots) "skin")))

(defn make
  "A person on `proxy` (the part's Object3D), from the loaded model `source`. tint: a colour name ->
  THREE.Color; find: a part id -> its world centre (or nil). -> a map of functions for the view."
  [^js proxy ^js source id style tint find spec]
  (let [^js model (SkeletonUtils/clone source)
        bones (atom {})
        order (atom [])
        rest-m (atom {})
        base-m (atom nil)
        attach (atom {})
        bodies (atom [])
        meshes (atom [])
        state (atom {:spec "stand" :cur nil :from nil :t0 0 :dur 0 :dirty true})
        bone (fn [name] (or (get @bones name) (throw (js/Error. (str "person model has no bone " name)))))
        rest-p (fn [name] (.setFromMatrixPosition (THREE/Vector3.) (get @rest-m (bone name))))
        rest-q (fn [name] (.setFromRotationMatrix (THREE/Quaternion.) (.extractRotation (THREE/Matrix4.) (get @rest-m (bone name)))))]
    (set! (.-name model) "person-model")
    (.add proxy model)
    (.traverse model (fn [^js o] (when (.-isBone o) (swap! bones assoc (.-name o) o) (swap! order conj o))))
    ;; face +z (the person's front), whatever way the model was exported
    (.updateMatrixWorld proxy true)
    (let [wp #(.getWorldPosition ^js (bone %) (THREE/Vector3.))]
      (when (< (.-z (wp "ball_l")) (.-z (wp "foot_l"))) (set! (.. model -rotation -y) js/Math.PI)))
    ;; each bone's rest matrix in the person's own space
    (.updateMatrixWorld proxy true)
    (let [inv (.invert (.clone (.-matrixWorld proxy)))]
      (doseq [^js b @order]
        (swap! rest-m assoc b (.multiply (.clone inv) (.-matrixWorld b)))
        (set! (.. b -userData -rest) #js {:q (.clone (.-quaternion b)) :p (.clone (.-position b))}))
      (reset! base-m (.multiply (.clone inv) (.-matrixWorld (.-parent ^js (first @order))))))
    ;; the look: skin, hair, shoes and eyes from the model; top and bottom from the scene
    (let [look (atom {"skin" (THREE/Color. "#d9a78b") "top" (THREE/Color. "#5b7fa6") "bottom" (THREE/Color. "#3d4250")
                      "shoes" (THREE/Color. "#2b2b2b") "hair" (THREE/Color. "#3b2a20") "eyes" (THREE/Color. "#2b2b2b")})
          _ (.traverse proxy (fn [^js m]
                               (when (.-isSkinnedMesh m)
                                 (let [^js mat (if (array? (.-material m)) (aget (.-material m) 0) (.-material m))
                                       s (slot-of m)]
                                   (when (and mat (.-color mat) (not (#{"top" "bottom"} s)))
                                     (swap! look assoc s (.clone (.-color mat))))))))
          recolour! (fn [spec]
                      (when (:color spec) (swap! look assoc "top" (tint (:color spec))))
                      (let [l (if (map? (:look spec)) (:look spec) {})]
                        (doseq [s slots :let [c (get l (keyword s))] :when c] (swap! look assoc s (tint c))))
                      (doseq [{:keys [^js mesh slot]} @bodies]
                        (.copy (.. mesh -material -color) (or (get @look slot) (get @look "top")))))]
      (recolour! spec)
      ;; own materials (and geometry: the asset's is shared by every person)
      (let [skinned (atom [])]
        (.traverse model (fn [^js m] (when (.-isSkinnedMesh m) (swap! skinned conj m))))
        (doseq [^js m @skinned]
          (set! (.-geometry m) (.clone (.-geometry m)))
          (let [slot (slot-of m)
                color (.clone (or (get @look slot) (get @look "top")))
                diagram? (= style "diagram")]
            (set! (.-material m) (if diagram?
                                   (ink/toon-material color 1 false)
                                   (THREE/MeshStandardMaterial. #js {:color color :roughness 0.8 :metalness 0})))
            (set! (.-frustumCulled m) false)
            (set! (.-castShadow m) (not diagram?))
            (set! (.-receiveShadow m) (not diagram?))
            (set! (.. m -userData -id) id)
            (swap! meshes conj m)
            (let [^js outline (when (and diagram? (not= false (:outline spec)))
                                (let [^js o (THREE/SkinnedMesh. (.-geometry m) (ink/outline-material))]
                                  (.bind o (.-skeleton m) (.-bindMatrix m))
                                  (set! (.-frustumCulled o) false)
                                  (set! (.. o -userData -outline) true)
                                  (.copy (.-position o) (.-position m))
                                  (.copy (.-quaternion o) (.-quaternion m))
                                  (.copy (.-scale o) (.-scale m))
                                  (.add (.-parent m) o)
                                  o))]
              (swap! bodies conj {:mesh m :outline outline :slot slot})))))
      (let [rig (let [P #(arr (rest-p %))
                      d #(.distanceTo (rest-p %1) (rest-p %2))
                      L (fn [p] (if (neg? (first p)) (poses/mirror p) p))
                      inv (.invert (.clone (.-matrixWorld proxy)))
                      box (THREE/Box3.)]
                  (.updateMatrixWorld proxy true)
                  (doseq [^js m @meshes]
                    (.update (.-skeleton m))
                    (.computeBoundingBox m)
                    (.union box (.applyMatrix4 (.clone (.-boundingBox m)) (.multiply (.clone inv) (.-matrixWorld m)))))
                  {:pelvis (P "pelvis") :neck (P "neck_01") :head (P "head")
                   :top (if (.isEmpty box) height (.. box -max -y))
                   :shoulder (L (P "upperarm_l")) :elbow (L (P "lowerarm_l")) :wrist (L (P "hand_l"))
                   :hip (L (P "thigh_l")) :knee (L (P "calf_l")) :ankle (L (P "foot_l")) :ball (L (P "ball_l"))
                   :upper (d "upperarm_l" "lowerarm_l") :fore (d "lowerarm_l" "hand_l")
                   :arm (+ (d "upperarm_l" "lowerarm_l") (d "lowerarm_l" "hand_l"))
                   :thigh (d "thigh_l" "calf_l") :shin (d "calf_l" "foot_l")})
            ;; a point, or a part's centre turned into the person's space
            point (fn [t fallback]
                    (cond (vector? t) t
                          (string? t) (if-let [w (find t)]
                                        (do (.updateMatrixWorld proxy true)
                                            (arr (.worldToLocal proxy (.clone ^js w))))
                                        fallback)
                          :else fallback))
            resolve
            (fn [p]
              (let [r rig
                    hips (or (:hips p) [0 (second (:pelvis r)) 0])
                    chest (or (:chest p) [(first hips) (+ (second hips) (- (second (:neck r)) (second (:pelvis r)))) (nth hips 2)])
                    s0 (.normalize (.sub (rest-p "neck_01") (rest-p "pelvis")))
                    s1 (.normalize (.sub (v chest) (v hips)))
                    full (.setFromUnitVectors (THREE/Quaternion.) s0 s1)
                    pelvis (or (:pelvis p)
                               (let [e (.setFromQuaternion (THREE/Euler.) (.slerp (ident) full 0.35) "XYZ")]
                                 [(/ (.-x e) deg) (/ (.-y e) deg) (/ (.-z e) deg)]))
                    Rp (euler-q pelvis)
                    p-rest (rest-p "pelvis")
                    at (fn [name R] (.add (v hips) (.applyQuaternion (.sub (rest-p name) p-rest) R)))
                    hand-l (point (:handL p) (:wrist r))
                    hand-r (point (:handR p) (poses/mirror (:wrist r)))
                    foot-l (point (:footL p) (:ankle r))
                    foot-r (point (:footR p) (poses/mirror (:ankle r)))
                    elbow-pole (fn [side hand]
                                 (let [s (at (if (pos? side) "upperarm_l" "upperarm_r") full)]
                                   (arr (.add (.multiplyScalar (.add s (v hand)) 0.5)
                                              (.applyQuaternion (THREE/Vector3. (* side 0.3) -0.25 -0.35) full)))))
                    knee-pole (fn [side foot]
                                (let [h (at (if (pos? side) "thigh_l" "thigh_r") Rp)]
                                  (arr (.add (.multiplyScalar (.add h (v foot)) 0.5)
                                             (.applyQuaternion (THREE/Vector3. (* side 0.05) 0 0.6) Rp)))))
                    foot-len (- (nth (:ball r) 2) (nth (:ankle r) 2))
                    foot-drop (- (second (:ball r)) (second (:ankle r)))
                    toes (fn [t [fx fy fz]] (or t (when (< fy 0.25) [fx (+ fy foot-drop) (+ fz (max foot-len 0.1))])))]
                {:hips hips :chest chest :pelvis pelvis :twist (or (:twist p) 0)
                 :look (when (some? (:look p)) (point (:look p) [0 1.6 3]))
                 :handL hand-l :handR hand-r :footL foot-l :footR foot-r
                 :elbowL (or (:elbowL p) (elbow-pole 1 hand-l)) :elbowR (or (:elbowR p) (elbow-pole -1 hand-r))
                 :kneeL (or (:kneeL p) (knee-pole 1 foot-l)) :kneeR (or (:kneeR p) (knee-pole -1 foot-r))
                 :toesL (toes (:toesL p) foot-l) :toesR (toes (:toesR p) foot-r)}))
            blend (fn [a b t]
                    (into {} (for [[k y] b :let [x (get a k)]]
                               [k (cond (and (number? x) (number? y)) (+ x (* t (- y x)))
                                        (and (vector? x) (vector? y)) (lerp3 x y t)
                                        :else (if (< t 0.5) x y))])))
            solve!
            (fn [s]
              (let [want (atom {})
                    M (atom {})
                    s0 (.normalize (.sub (rest-p "neck_01") (rest-p "pelvis")))
                    s1 (.normalize (.sub (v (:chest s)) (v (:hips s))))
                    Rp (euler-q (:pelvis s))
                    rem (.setFromUnitVectors (THREE/Quaternion.) (.applyQuaternion (.clone s0) Rp) s1)
                    chest-q (fn [k] (-> (.setFromAxisAngle (THREE/Quaternion.) s1 (* (:twist s) deg k))
                                        (.multiply (.slerp (ident) rem k))
                                        (.multiply Rp)))
                    Rc (chest-q 1)
                    pos-of (fn [^js b parent-m] (.applyMatrix4 (.clone (.. b -userData -rest -p)) parent-m))
                    rot-of (fn [m] (.setFromRotationMatrix (THREE/Quaternion.) (.extractRotation (THREE/Matrix4.) m)))
                    limb (fn [upper lower end target pole swing ^js S]
                           (let [A0 (rest-p upper) B0 (rest-p lower) C0 (rest-p end)
                                 l1 (.distanceTo A0 B0) l2 (.distanceTo B0 C0)
                                 d0 (.normalize (.sub (.clone B0) A0))
                                 d0b (.normalize (.sub (.clone C0) B0))
                                 b0 (let [b (perp d0b d0)] (.normalize (if (< (.length b) 0.05) (perp swing d0) b)))
                                 dir0 (.sub (v target) S)
                                 dist (min (max (.length dir0) (+ (js/Math.abs (- l1 l2)) 1e-3)) (* 0.9995 (+ l1 l2)))
                                 dir (if (< (.lengthSq dir0) 1e-9) (.clone d0) (.normalize dir0))
                                 u (let [u (perp (.sub (v pole) S) dir)
                                         u (if (< (.length u) 1e-4) (perp (.negate (.clone b0)) dir) u)
                                         u (if (< (.length u) 1e-4) (perp (THREE/Vector3. 0 0 1) dir) u)]
                                     (.normalize u))
                                 cos-a (min 1 (max -1 (/ (- (+ (* l1 l1) (* dist dist)) (* l2 l2)) (* 2 l1 dist))))
                                 a (js/Math.acos cos-a)
                                 E (-> (.clone S) (.addScaledVector dir (* (js/Math.cos a) l1)) (.addScaledVector u (* (js/Math.sin a) l1)))
                                 Tc (.addScaledVector (.clone S) dir dist)
                                 ^js d1 (.normalize (.sub (.clone E) S))
                                 ^js d2 (.normalize (.sub (.clone Tc) E))
                                 b1 (let [b (perp d2 d1)] (.normalize (if (< (.length b) 1e-3) (perp (.negate (.clone u)) d1) b)))
                                 up (frame-turn d0 b0 d1 b1)
                                 h0 (.normalize (.cross (.clone d0) b0))
                                 ^js h1 (.normalize (.cross (.clone d1) b1))
                                 lo (frame-turn d0b (.cross (.clone h0) d0b) d2 (.cross (.clone h1) d2))]
                             (swap! want assoc (bone upper) {:q (.multiply up (rest-q upper))})
                             (swap! want assoc (bone lower) {:q (.multiply lo (rest-q lower))})))]
                (swap! want assoc (bone "pelvis") {:q (.multiply (.clone Rp) (rest-q "pelvis")) :p (v (:hips s))})
                (doseq [[i n] (map-indexed vector ["spine_01" "spine_02" "spine_03"])]
                  (swap! want assoc (bone n) {:q (.multiply (chest-q (/ (inc i) 3)) (rest-q n))}))
                ;; walk the skeleton from the top: each bone's matrix in the person's space
                (doseq [^js b @order]
                  (let [^js par (.-parent b)
                        ^js parent-m (if (and par (.-isBone par)) (get @M par) @base-m)
                        name (.-name b)]
                    (when (and (= name "neck_01") (:look s))
                      (let [neck-pos (pos-of b parent-m)
                            head-pos (-> neck-pos (.add (.applyQuaternion (.sub (rest-p "head") (rest-p "neck_01")) Rc))
                                         (.add (THREE/Vector3. 0 0.08 0)))
                            f (.applyQuaternion (THREE/Vector3. 0 0 1) Rc)
                            d (.normalize (.sub (v (:look s)) head-pos))
                            R (clamp-turn (.setFromUnitVectors (THREE/Quaternion.) f d) (* 75 deg))]
                        (swap! want assoc b {:q (-> (.slerp (ident) R 0.4) (.multiply Rc) (.multiply (rest-q "neck_01")))})
                        (swap! want assoc (bone "head") {:q (-> (.clone R) (.multiply Rc) (.multiply (rest-q "head")))})))
                    (case name
                      "upperarm_l" (limb "upperarm_l" "lowerarm_l" "hand_l" (:handL s) (:elbowL s) (THREE/Vector3. 0 0 1) (pos-of b parent-m))
                      "upperarm_r" (limb "upperarm_r" "lowerarm_r" "hand_r" (:handR s) (:elbowR s) (THREE/Vector3. 0 0 1) (pos-of b parent-m))
                      "thigh_l" (limb "thigh_l" "calf_l" "foot_l" (:footL s) (:kneeL s) (THREE/Vector3. 0 0 -1) (pos-of b parent-m))
                      "thigh_r" (limb "thigh_r" "calf_r" "foot_r" (:footR s) (:kneeR s) (THREE/Vector3. 0 0 -1) (pos-of b parent-m))
                      nil)
                    (doseq [[foot ball toes] [["foot_l" "ball_l" (:toesL s)] ["foot_r" "ball_r" (:toesR s)]]
                            :when (and (= name foot) toes)]
                      (let [A (pos-of b parent-m)
                            fd0 (.normalize (.sub (rest-p ball) (rest-p foot)))
                            fd1 (.sub (v toes) A)]
                        (when (>= (.lengthSq fd1) 1e-8)
                          (.normalize fd1)
                          (let [up0 (THREE/Vector3. 0 1 0)
                                up1 (.applyQuaternion (.clone up0) (.setFromUnitVectors (THREE/Quaternion.) fd0 fd1))]
                            (swap! want assoc b {:q (.multiply (frame-turn fd0 up0 fd1 up1) (rest-q foot))})))))
                    (let [w (get @want b)
                          ^js rest (.. b -userData -rest)
                          q (if (:q w) (.multiply (.invert (rot-of parent-m)) (:q w)) (.clone (.-q rest)))
                          p (if (:p w) (.applyMatrix4 (.clone ^js (:p w)) (.invert (.clone parent-m))) (.clone (.-p rest)))]
                      (.copy (.-quaternion b) q)
                      (.copy (.-position b) p)
                      (swap! M assoc b (.multiply (.clone parent-m) (.compose (THREE/Matrix4.) p q (.-scale b)))))))
                (.updateMatrixWorld proxy true)
                (doseq [^js m @meshes]
                  (.update (.-skeleton m))
                  (.computeBoundingBox m)
                  (.computeBoundingSphere m))))
            refs? (fn []
                    (let [p (poses/pose-of (:spec @state) rig)]
                      (some #(string? (get p %)) [:look :handL :handR :footL :footR])))
            set-pose! (fn [spec seconds]
                        (swap! state (fn [st] (assoc st :spec spec
                                                     :from (when (and (pos? seconds) (:cur st)) (:cur st))
                                                     :t0 (js/performance.now) :dur (* 1000 seconds) :dirty true))))
            update! (fn [now]
                      (let [{:keys [dirty from t0 dur spec]} @state]
                        (if (and (not dirty) (not from) (not (refs?)))
                          false
                          (let [goal (resolve (poses/pose-of spec rig))
                                p (min 1 (/ (- now t0) (max dur 1)))
                                cur (if from (blend from goal (ease p)) goal)
                                from (when (and from (< p 1)) from)]
                            (swap! state assoc :cur cur :from from :dirty false)
                            (solve! cur)
                            (boolean from)))))
            palm (fn [side]
                   (let [h (rest-p (str "hand_" side))
                         mid (if (get @bones (str "middle_01_" side))
                               (rest-p (str "middle_01_" side))
                               (.add (.clone h) (.multiplyScalar (.normalize (.sub (.clone h) (rest-p (str "lowerarm_" side)))) 0.09)))]
                     (.lerp h mid 0.6)))]
        (set-pose! (or (:pose spec) "stand") 0)
        (update! 0)
        {:meshes @meshes
         :set-pose! set-pose!
         :update! update!
         :recolour! recolour!
         ;; A frame riding on a joint, its axes the person's own at rest (x left, y up, z front),
         ;; its origin on the joint: a part with parent "<person>.<joint>" hangs from it.
         :joint (fn [name]
                  (when (joints name)
                    (or (get @attach name)
                        (let [[bone-name ^js pt] (case name
                                                   "hips" ["pelvis" (rest-p "pelvis")]
                                                   "chest" ["spine_03" (.lerp (rest-p "spine_03") (rest-p "neck_01") 0.4)]
                                                   "head" ["head" (.add (rest-p "head") (THREE/Vector3. 0 0.1 0.02))]
                                                   "handL" ["hand_l" (palm "l")]
                                                   "handR" ["hand_r" (palm "r")]
                                                   "footL" ["foot_l" (rest-p "foot_l")]
                                                   "footR" ["foot_r" (rest-p "foot_r")])
                              ^js b (bone bone-name)
                              ^js local (.multiply (.invert (.clone ^js (get @rest-m b)))
                                               (.makeTranslation (THREE/Matrix4.) (.-x pt) (.-y pt) (.-z pt)))
                              o (THREE/Object3D.)]
                          (.decompose local (.-position o) (.-quaternion o) (.-scale o))
                          (.add b o)
                          (swap! attach assoc name o)
                          o))))
         ;; where its label goes: just over the head (world)
         :label-point (fn [^js out]
                        (.getWorldPosition ^js (bone "head") out)
                        (set! (.-y out) (+ (.-y out) (* 0.32 (.-y (.getWorldScale proxy (THREE/Vector3.))))))
                        out)
         :reveal! (fn [k dim lit? op]
                    (doseq [{:keys [^js mesh outline]} @bodies]
                      (let [^js mat (.-material mesh)
                            o (* op k dim)]
                        (set! (.-opacity mat) o)
                        (set! (.-transparent mat) (or (< op 1) (< k 1) (< dim 1)))
                        (set! (.-depthWrite mat) (or (not (.-transparent mat)) (> o 0.5)))
                        (set! (.-visible mesh) (> k 0.003))
                        (set! (.-castShadow mesh) (and (not= style "diagram") (> k 0.5) (> dim 0.5)))
                        (when outline
                          (set! (.-visible ^js outline) (> k 0.003))
                          (ink/set-outline! outline k dim lit? op)))))
         :emissive! (fn [hex intensity]
                      (doseq [{:keys [^js mesh]} @bodies]
                        (when-let [^js e (.. mesh -material -emissive)]
                          (.setHex e hex)
                          (set! (.. mesh -material -emissiveIntensity) intensity))))}))))
