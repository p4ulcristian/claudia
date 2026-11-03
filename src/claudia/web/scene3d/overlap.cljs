(ns claudia.web.scene3d.overlap
  "Which solid parts of a scene3d intersect right now. Exact on the surfaces (three-mesh-bvh triangle
  tests) after a cheap bounding-box pass; a part wholly inside another is found too (ray parity).
  Housings and ghosts are not solid (translucent, wire, plane, ring, glass, or `ghost: true`); pairs
  meant to touch or overlap (a pin inside a rod end) are listed in the scene's `allow`."
  (:require ["three" :as THREE]
            ["three-mesh-bvh" :refer [MeshBVH]]
            [claudia.web.scene3d.story :as story]))

;; Parts may overlap by this much (scene units) before they count: surfaces that merely touch pass.
(def ^:private clearance 0.02)

(defn solid? [spec]
  (and (not (:ghost spec)) (not (:wire spec))
       (not (and (number? (:opacity spec)) (< (:opacity spec) 1)))
       (not (#{"plane" "ring" "group" "person"} (:shape spec)))
       (not= "glass" (:material spec))))

(defn make-checker
  "parts: [{:id :mesh :spec}]; allow: [[a b]]. -> {:count n :check (fn [on?] [[a b]...])}."
  [^js root parts allow]
  (let [items (vec (for [{:keys [id ^js mesh spec]} parts :when (solid? spec)]
                     (let [^js g (.-geometry mesh)]
                       (when-not (.-boundsTree g) (set! (.-boundsTree g) (MeshBVH. g)))
                       (.computeBoundingBox g)
                       (let [^js bb (.-boundingBox g)
                             c (.getCenter bb (THREE/Vector3.))
                             half (.multiplyScalar (.getSize bb (THREE/Vector3.)) 0.5)
                             k (fn [h] (if (> h 1e-6) (max 0.5 (- 1 (/ clearance h))) 1))
                             shrink (-> (.makeTranslation (THREE/Matrix4.) (.-x c) (.-y c) (.-z c))
                                        (.multiply (.makeScale (THREE/Matrix4.) (k (.-x half)) (k (.-y half)) (k (.-z half))))
                                        (.multiply (.makeTranslation (THREE/Matrix4.) (- (.-x c)) (- (.-y c)) (- (.-z c)))))]
                         {:id id :mesh mesh :bvh (.-boundsTree g) :shrink shrink}))))
        pairs (filter #(and (sequential? %) (= 2 (count %))) allow)
        allowed? (fn [a b] (some (fn [[p q]] (or (and (story/matches? p a) (story/matches? q b))
                                                 (and (story/matches? p b) (story/matches? q a))))
                                 pairs))
        skip (set (for [i (range (count items)) j (range (inc i) (count items))
                        :when (allowed? (:id (items i)) (:id (items j)))]
                    [i j]))
        inside? (fn [^js p {:keys [^js mesh ^js bvh]}]
                  (let [inv (.invert (.clone (.-matrixWorld mesh)))
                        ray (THREE/Ray. (.applyMatrix4 (.clone p) inv)
                                        (.normalize (THREE/Vector3. 1 0.0137 0.0071)))]
                    (odd? (.-length (.raycast bvh ray THREE/DoubleSide)))))]
    {:count (count items)
     :check (fn [on?]
              (.updateMatrixWorld root true)
              (let [boxes (mapv #(.setFromObject (THREE/Box3.) (:mesh %)) items)]
                (vec (for [i (range (count items)) j (range (inc i) (count items))
                           :let [^js bi (boxes i) ^js bj (boxes j) A (items i) B (items j)]
                           :when (and (not (skip [i j])) (.intersectsBox bi bj)
                                      (or (nil? on?) (and (on? (:id A)) (on? (:id B)))))
                           :when (let [m (-> (.invert (.clone (.-matrixWorld ^js (:mesh A))))
                                             (.multiply (.-matrixWorld ^js (:mesh B)))
                                             (.multiply (:shrink B)))]
                                   (or (.intersectsGeometry ^js (:bvh A) (.-geometry ^js (:mesh B)) m)
                                       (and (.containsBox bi bj) (inside? (.getCenter bj (THREE/Vector3.)) A))
                                       (and (.containsBox bj bi) (inside? (.getCenter bi (THREE/Vector3.)) B))))]
                       [(:id A) (:id B)]))))}))
