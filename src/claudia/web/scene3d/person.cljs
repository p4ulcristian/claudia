(ns claudia.web.scene3d.person
  "A person in a scene3d: a rigged human (a CC0 MakeHuman body, /person/person.glb) posed live. A pose
  says where the hips, chest, hands and feet go (claudia.web.scene3d.poses); this bends the skeleton
  to reach them: the spine leans towards the chest point, arms and legs are two-bone chains solved
  exactly (the elbow or knee bends towards its pole), the head turns to what it looks at, feet lie
  flat on the floor unless told where the toes point. A new pose glides: every target moves from
  where it is to where it goes, the solver runs each frame.

  A person is one map (`pp`): the model and its bones in order, each bone's rest matrix, the
  dressed body meshes, the rig (its measurements), and atoms for its look, its pose state and the
  frames riding on its joints."
  (:require ["three" :as THREE]
            ["three/examples/jsm/loaders/GLTFLoader.js" :refer [GLTFLoader]]
            ["three/examples/jsm/utils/SkeletonUtils.js" :as SkeletonUtils]
            [claudia.web.scene3d.ink :as ink]
            [claudia.web.scene3d.maths :as m]
            [claudia.web.scene3d.poses :as poses]))

(def height 1.75)
(def joints #{"hips" "chest" "head" "handL" "handR" "footL" "footR"})
(def ^:private deg m/deg)

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
(defn- perp [^js a ^js d] (.addScaledVector (.clone a) d (- (.dot a d))))

(defn- frame-turn
  "The rotation taking frame (d0, b0, d0 x b0) to (d1, b1, d1 x b1); b is made square to d first."
  [d0 b0 d1 b1]
  (let [basis (fn [^js d b]
                (let [^js bb (.normalize (perp b d))]
                  (.makeBasis (THREE/Matrix4.) d bb (.normalize (.cross (.clone d) bb)))))
        mx (.multiply (basis d1 b1) (.transpose (basis d0 b0)))]
    (.setFromRotationMatrix (THREE/Quaternion.) mx)))

(defn- clamp-turn [^js q max-angle]
  (let [angle (* 2 (js/Math.acos (min 1 (js/Math.abs (.-w q)))))]
    (if (> angle max-angle) (.slerp (ident) q (/ max-angle angle)) q)))

(defn- euler-q [[x y z]]
  (.setFromEuler (THREE/Quaternion.) (THREE/Euler. (* x deg) (* y deg) (* z deg) "XYZ")))

(defn- pos-of [^js b parent-m] (.applyMatrix4 (.clone (.. b -userData -rest -p)) parent-m))
(defn- rot-of [mx] (.setFromRotationMatrix (THREE/Quaternion.) (.extractRotation (THREE/Matrix4.) mx)))

;; ---- the skeleton at rest ----

(defn- bones-in "The model's bones, parents before children." [^js model]
  (let [out (array)]
    (.traverse model (fn [^js o] (when (.-isBone o) (.push out o))))
    (vec out)))

(defn- bone [pp bone-name]
  (or (get (:bones pp) bone-name) (throw (js/Error. (str "person model has no bone " bone-name)))))

(defn- rest-p [pp bone-name] (.setFromMatrixPosition (THREE/Vector3.) (get (:rest-m pp) (bone pp bone-name))))
(defn- rest-q [pp bone-name] (rot-of (get (:rest-m pp) (bone pp bone-name))))

(defn- face-front!
  "Face +z (the person's front), whatever way the model was exported."
  [{:keys [^js proxy ^js model] :as pp}]
  (.updateMatrixWorld proxy true)
  (let [wp #(.getWorldPosition ^js (bone pp %) (THREE/Vector3.))]
    (when (< (.-z (wp "ball_l")) (.-z (wp "foot_l"))) (set! (.. model -rotation -y) js/Math.PI))))

(defn- record-rest!
  "Each bone's rest matrix in the person's own space (and its rest turn and place on the bone).
  -> {:rest-m {bone matrix} :base-m (the skeleton's parent)}."
  [{:keys [^js proxy order]}]
  (.updateMatrixWorld proxy true)
  (let [inv (.invert (.clone (.-matrixWorld proxy)))]
    (doseq [^js b order]
      (set! (.. b -userData -rest) #js {:q (.clone (.-quaternion b)) :p (.clone (.-position b))}))
    {:rest-m (into {} (map (fn [^js b] [b (.multiply (.clone inv) (.-matrixWorld b))])) order)
     :base-m (.multiply (.clone inv) (.-matrixWorld (.-parent ^js (first order))))}))

(defn- make-body
  "A copy of the model on the proxy, facing front, its rest pose recorded."
  [^js proxy ^js source]
  (let [^js model (SkeletonUtils/clone source)
        _ (set! (.-name model) "person-model")
        _ (.add proxy model)
        order (bones-in model)
        pp {:proxy proxy :model model :order order
            :bones (into {} (map (fn [^js b] [(.-name b) b])) order)}]
    (face-front! pp)
    (merge pp (record-rest! pp))))

;; ---- the look ----

(def ^:private slots ["skin" "top" "bottom" "shoes" "hair" "eyes"])

(defn- first-material [^js mesh]
  (if (array? (.-material mesh)) (aget (.-material mesh) 0) (.-material mesh)))

(defn- slot-of [^js mesh]
  (let [^js mat (first-material mesh)
        n (.toLowerCase (str (some-> mat .-name) " " (.-name mesh)))]
    (or (some #(when (.includes n %) %) slots) "skin")))

(defn- skinned-in [^js root]
  (let [out (array)]
    (.traverse root (fn [^js o] (when (.-isSkinnedMesh o) (.push out o))))
    (vec out)))

(defn- default-look []
  {"skin" (THREE/Color. "#d9a78b") "top" (THREE/Color. "#5b7fa6") "bottom" (THREE/Color. "#3d4250")
   "shoes" (THREE/Color. "#2b2b2b") "hair" (THREE/Color. "#3b2a20") "eyes" (THREE/Color. "#2b2b2b")})

(defn- model-look
  "Skin, hair, shoes and eyes from the model; top and bottom stay the scene's."
  [^js proxy look]
  (reduce (fn [acc ^js mesh]
            (let [^js mat (first-material mesh)
                  s (slot-of mesh)]
              (if (and mat (.-color mat) (not (#{"top" "bottom"} s))) (assoc acc s (.clone (.-color mat))) acc)))
          look (skinned-in proxy)))

(defn- with-spec-look
  "The look with a part's colour (the top) and its `look` map (any slot) on it."
  [look spec tint]
  (let [given (if (map? (:look spec)) (:look spec) {})
        look (if (:color spec) (assoc look "top" (tint (:color spec))) look)]
    (reduce (fn [acc s] (if-let [c (get given (keyword s))] (assoc acc s (tint c)) acc)) look slots)))

(defn- slot-colour [look slot] (or (get look slot) (get look "top")))

(defn- recolour! [{:keys [look bodies tint]} spec]
  (swap! look with-spec-look spec tint)
  (doseq [{:keys [^js mesh slot]} bodies]
    (.copy (.. mesh -material -color) (slot-colour @look slot))))

;; ---- the body's meshes ----

(defn- body-material [color style]
  (if (= style "diagram")
    (ink/toon-material color 1 false)
    (THREE/MeshStandardMaterial. #js {:color color :roughness 0.8 :metalness 0})))

(defn- add-outline!
  "The chalk outline of a body mesh: a skinned copy that bends with the same bones."
  [^js mesh]
  (let [^js o (THREE/SkinnedMesh. (.-geometry mesh) (ink/outline-material))]
    (.bind o (.-skeleton mesh) (.-bindMatrix mesh))
    (set! (.-frustumCulled o) false)
    (set! (.. o -userData -outline) true)
    (.copy (.-position o) (.-position mesh))
    (.copy (.-quaternion o) (.-quaternion mesh))
    (.copy (.-scale o) (.-scale mesh))
    (.add (.-parent mesh) o)
    o))

(defn- dress!
  "A body mesh gets its own geometry (the asset's is shared by every person) and material.
  -> {:mesh :outline :slot}."
  [^js mesh id style spec look]
  (set! (.-geometry mesh) (.clone (.-geometry mesh)))
  (let [slot (slot-of mesh)
        diagram? (= style "diagram")]
    (set! (.-material mesh) (body-material (.clone (slot-colour look slot)) style))
    (set! (.-frustumCulled mesh) false)
    (set! (.-castShadow mesh) (not diagram?))
    (set! (.-receiveShadow mesh) (not diagram?))
    (set! (.. mesh -userData -id) id)
    {:mesh mesh :slot slot
     :outline (when (and diagram? (not= false (:outline spec))) (add-outline! mesh))}))

(defn- refresh-meshes! [{:keys [^js proxy meshes]}]
  (.updateMatrixWorld proxy true)
  (doseq [^js mesh meshes]
    (.update (.-skeleton mesh))
    (.computeBoundingBox mesh)
    (.computeBoundingSphere mesh)))

;; ---- the rig ----

(defn- body-top "The top of the body at rest (its height)." [{:keys [^js proxy meshes]}]
  (let [inv (.invert (.clone (.-matrixWorld proxy)))
        box (THREE/Box3.)]
    (.updateMatrixWorld proxy true)
    (doseq [^js mesh meshes]
      (.update (.-skeleton mesh))
      (.computeBoundingBox mesh)
      (.union box (.applyMatrix4 (.clone (.-boundingBox mesh)) (.multiply (.clone inv) (.-matrixWorld mesh)))))
    (if (.isEmpty box) height (.. box -max -y))))

(defn- left-side [p] (if (neg? (first p)) (poses/mirror p) p))

(defn- measure-rig
  "The body's measurements for the poses (claudia.web.scene3d.poses), left side."
  [pp]
  (let [P #(arr (rest-p pp %))
        gap #(.distanceTo (rest-p pp %1) (rest-p pp %2))
        top (body-top pp)]
    {:pelvis (P "pelvis") :neck (P "neck_01") :head (P "head")
     :top top
     :shoulder (left-side (P "upperarm_l")) :elbow (left-side (P "lowerarm_l")) :wrist (left-side (P "hand_l"))
     :hip (left-side (P "thigh_l")) :knee (left-side (P "calf_l")) :ankle (left-side (P "foot_l"))
     :ball (left-side (P "ball_l"))
     :upper (gap "upperarm_l" "lowerarm_l") :fore (gap "lowerarm_l" "hand_l")
     :arm (+ (gap "upperarm_l" "lowerarm_l") (gap "lowerarm_l" "hand_l"))
     :thigh (gap "thigh_l" "calf_l") :shin (gap "calf_l" "foot_l")}))

;; ---- a pose made concrete ----

(defn- point-of
  "A point, or a part's centre turned into the person's space."
  [{:keys [^js proxy find-part]} t fallback]
  (cond (vector? t) t
        (string? t) (if-let [w (find-part t)]
                      (do (.updateMatrixWorld proxy true)
                          (arr (.worldToLocal proxy (.clone ^js w))))
                      fallback)
        :else fallback))

(defn- spine-turn "The turn from the spine at rest to hips -> chest." [pp hips chest]
  (let [s0 (.normalize (.sub (rest-p pp "neck_01") (rest-p pp "pelvis")))
        s1 (.normalize (.sub (v chest) (v hips)))]
    (.setFromUnitVectors (THREE/Quaternion.) s0 s1)))

(defn- lean-angles "The pelvis takes 35% of the spine's turn (Euler degrees)." [full]
  (let [e (.setFromQuaternion (THREE/Euler.) (.slerp (ident) full 0.35) "XYZ")]
    [(/ (.-x e) deg) (/ (.-y e) deg) (/ (.-z e) deg)]))

(defn- turned-at "Where a bone's rest point goes when the body turns by R about the pelvis, at hips." [pp hips bone-name R]
  (.add (v hips) (.applyQuaternion (.sub (rest-p pp bone-name) (rest-p pp "pelvis")) R)))

(defn- elbow-pole [pp hips full side hand]
  (let [s (turned-at pp hips (if (pos? side) "upperarm_l" "upperarm_r") full)]
    (arr (.add (.multiplyScalar (.add s (v hand)) 0.5)
               (.applyQuaternion (THREE/Vector3. (* side 0.3) -0.25 -0.35) full)))))

(defn- knee-pole [pp hips Rp side foot]
  (let [h (turned-at pp hips (if (pos? side) "thigh_l" "thigh_r") Rp)]
    (arr (.add (.multiplyScalar (.add h (v foot)) 0.5)
               (.applyQuaternion (THREE/Vector3. (* side 0.05) 0 0.6) Rp)))))

(defn- resolve-pose
  "A whole pose (poses/pose-of) with every target a point in the person's space: parts it reaches
  for found, poles for elbows and knees, toes flat unless given."
  [{:keys [rig] :as pp} p]
  (let [hips (or (:hips p) [0 (second (:pelvis rig)) 0])
        chest (or (:chest p) (poses/chest-over hips rig))
        full (spine-turn pp hips chest)
        pelvis (or (:pelvis p) (lean-angles full))
        Rp (euler-q pelvis)
        hand-l (point-of pp (:handL p) (:wrist rig))
        hand-r (point-of pp (:handR p) (poses/mirror (:wrist rig)))
        foot-l (point-of pp (:footL p) (:ankle rig))
        foot-r (point-of pp (:footR p) (poses/mirror (:ankle rig)))]
    {:hips hips :chest chest :pelvis pelvis :twist (or (:twist p) 0)
     :look (when (some? (:look p)) (point-of pp (:look p) [0 1.6 3]))
     :handL hand-l :handR hand-r :footL foot-l :footR foot-r
     :elbowL (or (:elbowL p) (elbow-pole pp hips full 1 hand-l)) :elbowR (or (:elbowR p) (elbow-pole pp hips full -1 hand-r))
     :kneeL (or (:kneeL p) (knee-pole pp hips Rp 1 foot-l)) :kneeR (or (:kneeR p) (knee-pole pp hips Rp -1 foot-r))
     :toesL (poses/toes (:toesL p) foot-l rig) :toesR (poses/toes (:toesR p) foot-r rig)}))

;; ---- the solver ----

(defn- chest-q
  "The spine's turn k of the way up: the twist about the spine, the lean, the pelvis."
  [{:keys [s1 lean Rp twist]} k]
  (-> (.setFromAxisAngle (THREE/Quaternion.) s1 (* twist deg k))
      (.multiply (.slerp (ident) lean k))
      (.multiply Rp)))

(defn- spine-of [pp s]
  (let [s0 (.normalize (.sub (rest-p pp "neck_01") (rest-p pp "pelvis")))
        s1 (.normalize (.sub (v (:chest s)) (v (:hips s))))
        Rp (euler-q (:pelvis s))
        spine {:s1 s1 :Rp Rp :twist (:twist s)
               :lean (.setFromUnitVectors (THREE/Quaternion.) (.applyQuaternion (.clone s0) Rp) s1)}]
    (assoc spine :Rc (chest-q spine 1))))

(defn- spine-wants "The pelvis at the hips, the spine bones sharing the lean." [pp spine s]
  (into {(bone pp "pelvis") {:q (.multiply (.clone (:Rp spine)) (rest-q pp "pelvis")) :p (v (:hips s))}}
        (map-indexed (fn [i n] [(bone pp n) {:q (.multiply (chest-q spine (/ (inc i) 3)) (rest-q pp n))}])
                     ["spine_01" "spine_02" "spine_03"])))

(defn- look-wants
  "The neck and head turn towards what the person looks at (at most 75°, the neck 40% of it)."
  [pp {:keys [Rc]} s ^js b parent-m]
  (let [head-pos (-> (pos-of b parent-m)
                     (.add (.applyQuaternion (.sub (rest-p pp "head") (rest-p pp "neck_01")) Rc))
                     (.add (THREE/Vector3. 0 0.08 0)))
        f (.applyQuaternion (THREE/Vector3. 0 0 1) Rc)
        d (.normalize (.sub (v (:look s)) head-pos))
        R (clamp-turn (.setFromUnitVectors (THREE/Quaternion.) f d) (* 75 deg))]
    {b {:q (-> (.slerp (ident) R 0.4) (.multiply Rc) (.multiply (rest-q pp "neck_01")))}
     (bone pp "head") {:q (-> (.clone R) (.multiply Rc) (.multiply (rest-q pp "head")))}}))

(defn- rest-limb
  "A limb at rest: its bone lengths, the bones' directions and the side it bends to (b0)."
  [pp upper lower end swing]
  (let [A0 (rest-p pp upper) B0 (rest-p pp lower) C0 (rest-p pp end)
        d0 (.normalize (.sub (.clone B0) A0))
        d0b (.normalize (.sub (.clone C0) B0))
        b0 (let [b (perp d0b d0)] (.normalize (if (< (.length b) 0.05) (perp swing d0) b)))]
    {:l1 (.distanceTo A0 B0) :l2 (.distanceTo B0 C0) :d0 d0 :d0b d0b :b0 b0}))

(defn- bend-plane
  "From the limb's root S: how far the target is, the direction to it, and the unit vector
  square to that the joint bends towards (the pole)."
  [^js S target pole ^js b0 d0]
  (let [dir0 (.sub (v target) S)
        len (.length dir0)
        dir (if (< (.lengthSq dir0) 1e-9) (.clone d0) (.normalize dir0))
        u (let [u (perp (.sub (v pole) S) dir)
                u (if (< (.length u) 1e-4) (perp (.negate (.clone b0)) dir) u)
                u (if (< (.length u) 1e-4) (perp (THREE/Vector3. 0 0 1) dir) u)]
            (.normalize u))]
    {:len len :dir dir :u u}))

(defn- limb-wants
  "An arm or leg as an exact two-bone chain from S (where its upper bone starts now) to the target,
  bending towards the pole. -> the upper and lower bones' turns."
  [pp [upper lower end target pole swing] ^js S]
  (let [{:keys [l1 l2 ^js d0 d0b b0]} (rest-limb pp upper lower end swing)
        {:keys [len dir ^js u]} (bend-plane S target pole b0 d0)
        dist (m/chain-reach l1 l2 len)
        a (m/bend-angle l1 l2 dist)
        E (-> (.clone S) (.addScaledVector dir (* (js/Math.cos a) l1)) (.addScaledVector u (* (js/Math.sin a) l1)))
        Tc (.addScaledVector (.clone S) dir dist)
        ^js d1 (.normalize (.sub (.clone E) S))
        ^js d2 (.normalize (.sub (.clone Tc) E))
        b1 (let [b (perp d2 d1)] (.normalize (if (< (.length b) 1e-3) (perp (.negate (.clone u)) d1) b)))
        ^js h0 (.normalize (.cross (.clone d0) b0))
        ^js h1 (.normalize (.cross (.clone d1) b1))]
    {(bone pp upper) {:q (.multiply (frame-turn d0 b0 d1 b1) (rest-q pp upper))}
     (bone pp lower) {:q (.multiply (frame-turn d0b (.cross (.clone h0) d0b) d2 (.cross (.clone h1) d2))
                                    (rest-q pp lower))}}))

(defn- limb-of "The limb a bone starts: [upper lower end target pole swing], or nil." [bone-name s]
  (case bone-name
    "upperarm_l" ["upperarm_l" "lowerarm_l" "hand_l" (:handL s) (:elbowL s) (THREE/Vector3. 0 0 1)]
    "upperarm_r" ["upperarm_r" "lowerarm_r" "hand_r" (:handR s) (:elbowR s) (THREE/Vector3. 0 0 1)]
    "thigh_l" ["thigh_l" "calf_l" "foot_l" (:footL s) (:kneeL s) (THREE/Vector3. 0 0 -1)]
    "thigh_r" ["thigh_r" "calf_r" "foot_r" (:footR s) (:kneeR s) (THREE/Vector3. 0 0 -1)]
    nil))

(defn- foot-wants
  "A foot turns so its toes point at their target (nil when the target is on the ankle)."
  [pp ^js b ball toes parent-m]
  (let [foot (.-name b)
        fd0 (.normalize (.sub (rest-p pp ball) (rest-p pp foot)))
        fd1 (.sub (v toes) (pos-of b parent-m))]
    (when (>= (.lengthSq fd1) 1e-8)
      (.normalize fd1)
      (let [up0 (THREE/Vector3. 0 1 0)
            up1 (.applyQuaternion (.clone up0) (.setFromUnitVectors (THREE/Quaternion.) fd0 fd1))]
        {b {:q (.multiply (frame-turn fd0 up0 fd1 up1) (rest-q pp foot))}}))))

(defn- bone-wants
  "What reaching this bone adds: the gaze at the neck, a limb at its upper bone, toes at a foot."
  [pp spine s ^js b parent-m]
  (let [bone-name (.-name b)
        toes (case bone-name "foot_l" (:toesL s) "foot_r" (:toesR s) nil)]
    (merge (when (and (= bone-name "neck_01") (:look s)) (look-wants pp spine s b parent-m))
           (when-let [limb (limb-of bone-name s)] (limb-wants pp limb (pos-of b parent-m)))
           (when toes (foot-wants pp b (if (= bone-name "foot_l") "ball_l" "ball_r") toes parent-m)))))

(defn- set-bone!
  "A bone where it is wanted (in its parent's space), else at rest. -> its matrix in the person's space."
  [^js b w ^js parent-m]
  (let [^js at-rest (.. b -userData -rest)
        q (if (:q w) (.multiply (.invert (rot-of parent-m)) (:q w)) (.clone (.-q at-rest)))
        p (if (:p w) (.applyMatrix4 (.clone ^js (:p w)) (.invert (.clone parent-m))) (.clone (.-p at-rest)))]
    (.copy (.-quaternion b) q)
    (.copy (.-position b) p)
    (.multiply (.clone parent-m) (.compose (THREE/Matrix4.) p q (.-scale b)))))

(defn- solve!
  "Bends the skeleton into the resolved pose s, walking it from the top."
  [{:keys [order base-m] :as pp} s]
  (let [spine (spine-of pp s)]
    (reduce (fn [{:keys [want M]} ^js b]
              (let [^js par (.-parent b)
                    parent-m (if (and par (.-isBone par)) (get M par) base-m)
                    want (merge want (bone-wants pp spine s b parent-m))]
                {:want want :M (assoc M b (set-bone! b (get want b) parent-m))}))
            {:want (spine-wants pp spine s) :M {}}
            order)
    (refresh-meshes! pp)))

;; ---- the person's api ----

(defn- set-pose! [{:keys [state]} spec seconds]
  (swap! state poses/start-move spec seconds (js/performance.now)))

(defn- update!
  "One frame of the pose (a glide, or targets that follow parts). -> whether it still glides."
  [{:keys [state rig] :as pp} now]
  (let [{:keys [dirty from spec]} @state]
    (if (and (not dirty) (not from) (not (poses/refs? spec rig)))
      false
      (let [next-state (poses/glide @state (resolve-pose pp (poses/pose-of spec rig)) now)]
        (reset! state next-state)
        (solve! pp (:cur next-state))
        (boolean (:from next-state))))))

(defn- palm "The middle of a palm at rest." [pp side]
  (let [h (rest-p pp (str "hand_" side))
        mid (if (get (:bones pp) (str "middle_01_" side))
              (rest-p pp (str "middle_01_" side))
              (.add (.clone h) (.multiplyScalar (.normalize (.sub (.clone h) (rest-p pp (str "lowerarm_" side)))) 0.09)))]
    (.lerp h mid 0.6)))

(defn- joint-anchor "The bone a joint rides on and the point on it, at rest." [pp joint]
  (case joint
    "hips" ["pelvis" (rest-p pp "pelvis")]
    "chest" ["spine_03" (.lerp (rest-p pp "spine_03") (rest-p pp "neck_01") 0.4)]
    "head" ["head" (.add (rest-p pp "head") (THREE/Vector3. 0 0.1 0.02))]
    "handL" ["hand_l" (palm pp "l")]
    "handR" ["hand_r" (palm pp "r")]
    "footL" ["foot_l" (rest-p pp "foot_l")]
    "footR" ["foot_r" (rest-p pp "foot_r")]))

(defn- joint!
  "A frame riding on a joint, its axes the person's own at rest (x left, y up, z front), its origin
  on the joint: a part with parent \"<person>.<joint>\" hangs from it. Made once per joint."
  [{:keys [attach rest-m] :as pp} joint]
  (when (joints joint)
    (or (get @attach joint)
        (let [[bone-name ^js pt] (joint-anchor pp joint)
              ^js b (bone pp bone-name)
              ^js local (.multiply (.invert (.clone ^js (get rest-m b)))
                                   (.makeTranslation (THREE/Matrix4.) (.-x pt) (.-y pt) (.-z pt)))
              o (THREE/Object3D.)]
          (.decompose local (.-position o) (.-quaternion o) (.-scale o))
          (.add b o)
          (swap! attach assoc joint o)
          o))))

(defn- label-point! "Where its label goes: just over the head (world)." [{:keys [^js proxy] :as pp} ^js out]
  (.getWorldPosition ^js (bone pp "head") out)
  (set! (.-y out) (+ (.-y out) (* 0.32 (.-y (.getWorldScale proxy (THREE/Vector3.))))))
  out)

(defn- reveal! [{:keys [bodies style]} k dim lit? op]
  (doseq [{:keys [^js mesh outline]} bodies]
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

(defn- emissive! [{:keys [bodies]} hex intensity]
  (doseq [{:keys [^js mesh]} bodies]
    (when-let [^js e (.. mesh -material -emissive)]
      (.setHex e hex)
      (set! (.. mesh -material -emissiveIntensity) intensity))))

(defn make
  "A person on `proxy` (the part's Object3D), from the loaded model `source`. tint: a colour name ->
  THREE.Color; find-part: a part id -> its world centre (or nil). -> a map of functions for the view."
  [^js proxy ^js source id style tint find-part spec]
  (let [body (make-body proxy source)
        look (atom (with-spec-look (model-look proxy (default-look)) spec tint))
        bodies (mapv #(dress! % id style spec @look) (skinned-in (:model body)))
        pp (assoc body :look look :bodies bodies :meshes (mapv :mesh bodies) :style style :tint tint
                  :find-part find-part :attach (atom {})
                  :state (atom {:spec "stand" :cur nil :from nil :t0 0 :dur 0 :dirty true}))
        pp (assoc pp :rig (measure-rig pp))]
    (set-pose! pp (or (:pose spec) "stand") 0)
    (update! pp 0)
    {:meshes (:meshes pp)
     :set-pose! #(set-pose! pp %1 %2)
     :update! #(update! pp %)
     :recolour! #(recolour! pp %)
     :joint #(joint! pp %)
     :label-point #(label-point! pp %)
     :reveal! #(reveal! pp %1 %2 %3 %4)
     :emissive! #(emissive! pp %1 %2)}))
