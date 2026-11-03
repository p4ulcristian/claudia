(ns claudia.web.scene3d.overlap
  "Which solid parts of a scene3d intersect right now. Exact on the surfaces (three-mesh-bvh triangle
  tests) after a cheap bounding-box pass; a part wholly inside another is found too (ray parity).
  Housings and ghosts are not solid (translucent, wire, plane, ring, glass, or `ghost: true`); pairs
  meant to touch or overlap (a pin inside a rod end) are listed in the scene's `allow`."
  (:require ["three" :as THREE]
            ["three-mesh-bvh" :refer [MeshBVH]]
            [claudia.web.scene3d.data :as data]))

;; Parts may overlap by this much (scene units) before they count: surfaces that merely touch pass.
(def ^:private clearance 0.02)

(defn solid? [spec]
  (and (not (:ghost spec)) (not (:wire spec))
       (not (and (number? (:opacity spec)) (< (:opacity spec) 1)))
       (not (#{"plane" "ring" "group" "person"} (:shape spec)))
       (not= "glass" (:material spec))))

(defn- shrink-matrix
  "Scales a geometry about its box's centre so it is `clearance` smaller on every side."
  [^js g]
  (.computeBoundingBox g)
  (let [^js bb (.-boundingBox g)
        c (.getCenter bb (THREE/Vector3.))
        half (.multiplyScalar (.getSize bb (THREE/Vector3.)) 0.5)
        k #(data/clearance-scale % clearance)]
    (-> (.makeTranslation (THREE/Matrix4.) (.-x c) (.-y c) (.-z c))
        (.multiply (.makeScale (THREE/Matrix4.) (k (.-x half)) (k (.-y half)) (k (.-z half))))
        (.multiply (.makeTranslation (THREE/Matrix4.) (- (.-x c)) (- (.-y c)) (- (.-z c)))))))

(defn- checker-item
  "A solid part ready for the check: its triangle tree (made once per geometry) and its shrink."
  [{:keys [id ^js mesh]}]
  (let [^js g (.-geometry mesh)]
    (when-not (.-boundsTree g) (set! (.-boundsTree g) (MeshBVH. g)))
    (let [shrink (shrink-matrix g)]
      {:id id :mesh mesh :bvh (.-boundsTree g) :shrink shrink})))

(defn- inside?
  "Whether point p is inside a part: a ray from it crosses the surface an odd number of times."
  [^js p {:keys [^js mesh ^js bvh]}]
  (let [inv (.invert (.clone (.-matrixWorld mesh)))
        ray (THREE/Ray. (.applyMatrix4 (.clone p) inv)
                        (.normalize (THREE/Vector3. 1 0.0137 0.0071)))]
    (odd? (.-length (.raycast bvh ray THREE/DoubleSide)))))

(defn- touching?
  "Whether parts A and B (world boxes bi, bj) intersect: their surfaces cross, or one holds the other."
  [A B ^js bi ^js bj]
  (let [mx (-> (.invert (.clone (.-matrixWorld ^js (:mesh A))))
               (.multiply (.-matrixWorld ^js (:mesh B)))
               (.multiply (:shrink B)))]
    (or (.intersectsGeometry ^js (:bvh A) (.-geometry ^js (:mesh B)) mx)
        (and (.containsBox bi bj) (inside? (.getCenter bj (THREE/Vector3.)) A))
        (and (.containsBox bj bi) (inside? (.getCenter bi (THREE/Vector3.)) B)))))

(defn- check
  "The pairs [a b] of solid parts that overlap now; on?: which ids count (nil: all)."
  [^js root items skip on?]
  (.updateMatrixWorld root true)
  (let [boxes (mapv #(.setFromObject (THREE/Box3.) (:mesh %)) items)]
    (vec (for [i (range (count items)) j (range (inc i) (count items))
               :let [^js bi (boxes i) ^js bj (boxes j) A (items i) B (items j)]
               :when (and (not (skip [i j])) (.intersectsBox bi bj)
                          (or (nil? on?) (and (on? (:id A)) (on? (:id B)))))
               :when (touching? A B bi bj)]
           [(:id A) (:id B)]))))

(defn make-checker
  "parts: [{:id :mesh :spec}]; allow: [[a b]]. -> {:count n :check (fn [on?] [[a b]...])}."
  [^js root parts allow]
  (let [items (vec (for [part parts :when (solid? (:spec part))] (checker-item part)))
        skip (data/skipped-pairs (mapv :id items) (data/allow-pairs allow))]
    {:count (count items)
     :check (fn [on?] (check root items skip on?))}))
