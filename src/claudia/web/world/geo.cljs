(ns claudia.web.world.geo
  "The world's still parts as geometry: the Blender pieces (claudia.web.world.models) placed by the
  plan (islands, buildings, the lighthouse, trees and rocks) merged into one lit mesh, every vertex
  knowing its island (`isl`) and building (`bld`, -1 for none) so the chalk material
  (claudia.web.world.chalk) can colour it by health and light its windows; the bridges glow in a
  second mesh. The whole still world is two draw calls (plus its outline)."
  (:require ["three/webgpu" :as THREE]
            ["three/examples/jsm/utils/BufferGeometryUtils.js" :as bgu]
            [claudia.web.world.plan :as plan]))

(def lamp-height "The lighthouse lamp's height over its island." 4.6)
(def ^:private bridge-glow 0x86dcff)

(defn facing-yaw
  "The yaw that turns a building's front (+z) toward its island's middle, from its angle round it."
  [angle]
  (let [a (+ angle js/Math.PI)] (js/Math.atan2 (js/Math.cos a) (js/Math.sin a))))

(defn- pick
  "A variant of a piece, stable for seed."
  [variants seed]
  (nth variants (js/Math.floor (* (count variants) (plan/hash01 seed)))))

(defn- tagged
  "A copy of g scaled by [sx sy sz], turned by yaw, moved to pos, every vertex tagged with its island
  and building."
  [^js g {:keys [pos yaw scale isl bld]}]
  (let [^js c (.clone g)
        [sx sy sz] scale [x y z] pos
        n (.. c -attributes -position -count)
        fill (fn [v] (doto (js/Float32Array. n) (.fill v)))]
    (doto c (.scale sx sy sz) (.rotateY (or yaw 0)) (.translate x y z))
    (.setAttribute c "isl" (THREE/BufferAttribute. (fill isl) 1))
    (.setAttribute c "bld" (THREE/BufferAttribute. (fill (or bld -1)) 1))
    c))

(defn- island-pieces
  "An island: its body (radius 1 in Blender) scaled to its radius, and on the gate the lighthouse."
  [models i {:keys [id radius pos kind]}]
  (cond-> [(tagged (pick (:island models) id) {:pos pos :scale [radius (* 0.75 radius) radius] :isl i})]
    (= kind :gate) (conj (tagged (first (:lighthouse models)) {:pos pos :scale [1 1 1] :isl i}))))

(defn- prop-spots
  "Where an island's trees and rocks stand: near its rim, clear of the bridge's side."
  [{:keys [id radius pos angle]}]
  (let [n (+ 4 (js/Math.floor (* 4 (plan/hash01 (str id "/props")))))]
    (for [i (range n)
          :let [h (plan/hash01 (str id "/prop" i))
                a (+ angle js/Math.PI 0.7 (* (- (* 2 js/Math.PI) 1.4) (/ (+ i h) n)))
                r (* radius (+ 0.8 (* 0.1 h)))]]
      {:key (str id "/prop" i) :h h
       :pos [(+ (first pos) (* r (js/Math.cos a))) (second pos) (+ (last pos) (* r (js/Math.sin a)))]})))

(defn- props [models i isl]
  (when (= :server (:kind isl))
    (for [{k :key :keys [h pos]} (prop-spots isl)
          :let [rock? (< h 0.25) s (if rock? (+ 0.6 h) (+ 1.1 (* 0.6 h)))]]
      (tagged (pick (if rock? (:rock models) (:tree models)) k)
              {:pos pos :yaw (* 7 h) :scale [s s s] :isl i}))))

(defn- building-piece [models island-index j {:keys [root pos size angle island]}]
  (let [s (* 1.2 (first size))]
    (tagged (pick (:building models) root)
            {:pos pos :yaw (facing-yaw angle) :scale [s s s] :isl (island-index island) :bld j})))

(defn bridge-curve
  "A bridge's line, sagging a little in the middle."
  [{:keys [from-pos to-pos]}]
  (let [[ax ay az] from-pos [bx by bz] to-pos]
    (THREE/QuadraticBezierCurve3. (THREE/Vector3. ax (- ay 0.1) az)
                                  (THREE/Vector3. (/ (+ ax bx) 2) (- (/ (+ ay by) 2) 1.0) (/ (+ az bz) 2))
                                  (THREE/Vector3. bx (- by 0.1) bz))))

(defn- bridge-geometry [b]
  (let [^js g (.toNonIndexed (THREE/TubeGeometry. (bridge-curve b) 32 0.11 6 false))
        c (THREE/Color. bridge-glow)
        n (.. g -attributes -position -count)
        a (js/Float32Array. (* 3 n))]
    (dotimes [i n] (aset a (* 3 i) (.-r c)) (aset a (+ 1 (* 3 i)) (.-g c)) (aset a (+ 2 (* 3 i)) (.-b c)))
    (.setAttribute g "color" (THREE/BufferAttribute. a 3))
    g))

(defn- solid-only
  "g without its thin surfaces (grass, windows, lamps): pushed out as a chalk outline, their back
  faces would poke through the faces in front of them."
  [^js g]
  (let [^js kind (.getAttribute g "kind")
        keep? (fn [tri] (zero? (.getX kind (* 3 tri))))
        tris (filter keep? (range (/ (.-count kind) 3)))
        ^js src (.getAttribute g "position")
        ^js nrm (.getAttribute g "normal")
        pos (js/Float32Array. (* 9 (count tris)))
        nor (js/Float32Array. (* 9 (count tris)))]
    (doseq [[j tri] (map-indexed vector tris)]
      (.set pos (.subarray (.-array src) (* 9 tri) (* 9 (inc tri))) (* 9 j))
      (.set nor (.subarray (.-array nrm) (* 9 tri) (* 9 (inc tri))) (* 9 j)))
    (doto (THREE/BufferGeometry.)
      (.setAttribute "position" (THREE/BufferAttribute. pos 3))
      (.setAttribute "normal" (THREE/BufferAttribute. nor 3)))))

(defn still-world
  "The still world of a plan, from the loaded models: {:lit geometry :outline geometry :glow geometry}."
  [{:keys [islands buildings bridges]} models]
  (let [island-index (into {} (map-indexed (fn [i isl] [(:id isl) i]) islands))
        lit (bgu/mergeGeometries
             (into-array (concat (mapcat #(island-pieces models %1 %2) (range) islands)
                                 (mapcat #(props models %1 %2) (range) islands)
                                 (map-indexed #(building-piece models island-index %1 %2) buildings)))
             false)]
    {:lit lit
     :outline (solid-only lit)
     :glow (bgu/mergeGeometries (into-array (map bridge-geometry bridges)) false)}))
