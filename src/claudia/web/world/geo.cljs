(ns claudia.web.world.geo
  "The world's still parts as geometry: every island, building, tree, the lighthouse and the bridges
  merged into two meshes (one lit, one glowing), colours in their vertices, so the whole still world
  is two draw calls. Low-poly with flat shading; the rock under each island is jittered by its
  position (the same at every load)."
  (:require ["three/webgpu" :as THREE]
            ["three/examples/jsm/utils/BufferGeometryUtils.js" :as bgu]
            [claudia.web.world.plan :as plan]))

(def ^:private grass 0x7cbf5c)
(def ^:private soil 0x9b7552)
(def ^:private rock 0x8a8178)
(def ^:private trunk 0x7a5636)
(def ^:private leaves 0x4f9a4a)
(def ^:private bridge-glow 0x86dcff)

(defn- painted
  "g with every vertex coloured hex (a colour attribute, linear, as three.js lights it)."
  [^js g hex]
  (let [c (THREE/Color. hex)
        n (.. g -attributes -position -count)
        a (js/Float32Array. (* 3 n))]
    (dotimes [i n]
      (aset a (* 3 i) (.-r c)) (aset a (+ 1 (* 3 i)) (.-g c)) (aset a (+ 2 (* 3 i)) (.-b c)))
    (.setAttribute g "color" (THREE/BufferAttribute. a 3))
    g))

(defn- placed
  "g turned by yaw (radians round y) and moved to [x y z]."
  [^js g [x y z] yaw]
  (doto g (.rotateY yaw) (.translate x y z)))

(defn- jittered
  "g's vertices pushed in or out round y by a noise of their own position."
  [^js g seed]
  (let [p (.. g -attributes -position)]
    (dotimes [i (.-count p)]
      (let [x (.getX p i) z (.getZ p i)
            k (+ 1 (* 0.16 (js/Math.sin (+ (* 1.7 x) (* 2.3 z) (* 9 seed)))))]
        (.setX p i (* x k)) (.setZ p i (* z k))))
    (.computeVertexNormals g)
    g))

(defn- shade [hex k] (.getHex (.multiplyScalar (THREE/Color. hex) k)))

(defn- island-parts
  "An island's lit pieces: grass on top (its top at the island's y), a band of soil, the rock below."
  [{:keys [radius pos id kind]}]
  (let [[x y z] pos
        r radius
        top (if (= kind :gate) 0xb9c48a grass)]
    [(placed (painted (THREE/CylinderGeometry. r r 0.5 16) top) [x (- y 0.25) z] 0)
     (placed (painted (THREE/CylinderGeometry. (* 0.99 r) (* 0.85 r) 0.7 16) soil) [x (- y 0.85) z] 0)
     (placed (painted (jittered (THREE/CylinderGeometry. (* 0.86 r) 0.35 (* 1.4 r) 11 3) (plan/hash01 id)) rock)
             [x (- y 1.2 (* 0.7 r)) z] 0)]))

(defn- tree [[x y z] h]
  (let [s (+ 0.75 (* 0.5 h))]
    [(placed (painted (THREE/CylinderGeometry. (* 0.07 s) (* 0.11 s) (* 0.55 s) 5) trunk) [x (+ y (* 0.27 s)) z] 0)
     (placed (painted (THREE/IcosahedronGeometry. (* 0.48 s) 0) (shade leaves (+ 0.85 (* 0.3 h))))
             [x (+ y (* 0.85 s)) z] (* 6 h))]))

(defn- island-trees
  "A few trees near a server island's rim, clear of its bridge."
  [{:keys [radius pos id angle kind]}]
  (when (= kind :server)
    (let [n (+ 3 (js/Math.floor (* 4 (plan/hash01 (str id "/trees")))))]
      (mapcat (fn [i]
                (let [h (plan/hash01 (str id "/tree" i))
                      a (+ angle js/Math.PI 0.7 (* (- (* 2 js/Math.PI) 1.4) (/ (+ i h) n)))
                      r (* radius (+ 0.82 (* 0.08 h)))]
                  (tree [(+ (first pos) (* r (js/Math.cos a))) (second pos) (+ (last pos) (* r (js/Math.sin a)))] h)))
              (range n)))))

(defn facing-yaw
  "The yaw that turns a building's front (+z) toward its island's middle, from its angle round it."
  [angle]
  (let [a (+ angle js/Math.PI)] (js/Math.atan2 (js/Math.cos a) (js/Math.sin a))))

(defn- building-parts [{:keys [pos size angle hue]}]
  (let [[x y z] pos
        [w h d] size
        yaw (facing-yaw angle)
        wall (.getHex (.setHSL (THREE/Color.) hue 0.45 0.72))
        roof (.getHex (.setHSL (THREE/Color.) (mod (+ hue 0.5) 1) 0.5 0.45))]
    [(placed (painted (THREE/BoxGeometry. w h d) wall) [x (+ y (/ h 2)) z] yaw)
     (placed (painted (.rotateY (THREE/ConeGeometry. (* 0.78 (max w d)) 1.1 4) (/ js/Math.PI 4)) roof)
             [x (+ y h 0.55) z] yaw)
     (placed (painted (.translate (THREE/BoxGeometry. 0.5 0.85 0.08) 0 0.425 (/ d 2)) 0x3b2f2a) [x y z] yaw)]))

(defn window-matrices
  "Each building's two front windows as [building-id Matrix4]."
  [building-list]
  (for [{:keys [id pos size angle]} building-list
        side [-1 1]
        :let [[x y z] pos [w h d] size
              m (THREE/Matrix4.)]]
    [id (-> m
            (.makeRotationY (facing-yaw angle))
            (.multiply (.makeTranslation (THREE/Matrix4.) (* side 0.27 w) (* 0.62 h) (+ 0.03 (/ d 2))))
            (.premultiply (.makeTranslation (THREE/Matrix4.) x y z)))]))

(defn- lighthouse-parts
  "The gate's lighthouse: white tower with red bands, the gallery and roof (lit), the lamp (glow)."
  []
  {:lit [(placed (painted (THREE/CylinderGeometry. 0.55 0.85 4 14) 0xf2efe6) [0 2 0] 0)
         (placed (painted (THREE/CylinderGeometry. 0.76 0.81 0.55 14) 0xd2553f) [0 1 0] 0)
         (placed (painted (THREE/CylinderGeometry. 0.62 0.68 0.55 14) 0xd2553f) [0 2.9 0] 0)
         (placed (painted (THREE/CylinderGeometry. 0.9 0.9 0.15 14) 0x4a4f5a) [0 4.08 0] 0)
         (placed (painted (THREE/ConeGeometry. 0.68 0.75 14) 0xd2553f) [0 5.12 0] 0)]
   :glow [(placed (painted (THREE/CylinderGeometry. 0.46 0.46 0.62 14) 0xffe39a) [0 4.46 0] 0)]})

(def lamp-height 4.46)

(defn bridge-curve
  "A bridge's line, sagging a little in the middle."
  [{:keys [from-pos to-pos]}]
  (let [[ax ay az] from-pos [bx by bz] to-pos]
    (THREE/QuadraticBezierCurve3. (THREE/Vector3. ax (- ay 0.1) az)
                                  (THREE/Vector3. (/ (+ ax bx) 2) (- (/ (+ ay by) 2) 1.0) (/ (+ az bz) 2))
                                  (THREE/Vector3. bx (- by 0.1) bz))))

(defn- bridge-parts [b]
  [(painted (THREE/TubeGeometry. (bridge-curve b) 32 0.11 6 false) bridge-glow)])

(defn- merged [geoms]
  (let [gs (map (fn [^js g] (if (.-index g) (.toNonIndexed g) g)) geoms)]
    (bgu/mergeGeometries (into-array gs) false)))

(defn still-world
  "The still world of a plan as {:lit geometry :glow geometry}."
  [{:keys [islands buildings bridges]}]
  (let [lh (lighthouse-parts)]
    {:lit (merged (concat (mapcat island-parts islands) (mapcat island-trees islands)
                          (mapcat building-parts buildings) (:lit lh)))
     :glow (merged (concat (mapcat bridge-parts bridges) (:glow lh)))}))
