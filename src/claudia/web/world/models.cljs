(ns claudia.web.world.models
  "The world's pieces made in Blender (tools/world/make-models.py, resources/web/world/models/*.glb),
  loaded once and turned into plain geometry the world merges and instances: per vertex its
  material's colour (`color`), the ambient occlusion baked in Blender (`ao`, from COLOR_0) and what
  the material is (`kind`: claudia.web.world.look/kinds), so the shader can recolour grass by a
  server's health, light windows and make lamps glow without separate materials."
  (:require ["three/webgpu" :as THREE]
            ["three/examples/jsm/loaders/GLTFLoader.js" :refer [GLTFLoader]]
            ["three/examples/jsm/utils/BufferGeometryUtils.js" :as bgu]
            [claudia.web.world.look :as look]))

(def ^:private base "/world/models/")

(def files
  "Every piece by kind; variants are picked by a stable hash."
  {:island ["island-1" "island-2" "island-3"]
   :building ["building-1" "building-2" "building-3" "building-4"]
   :tree ["tree-1" "tree-2" "tree-3"]
   :rock ["rock-1" "rock-2"]
   :lighthouse ["lighthouse"]
   :robot ["robot"]})

(defn- filled [n value]
  (let [a (js/Float32Array. n)] (.fill a value) a))

(defn- ao-of
  "The baked occlusion (COLOR_0's red, 1 where there is none) per vertex."
  [^js g n]
  (if-let [^js c (.getAttribute g "color")]
    (let [a (js/Float32Array. n)] (dotimes [i n] (aset a i (.getX c i))) a)
    (filled n 1)))

(defn- colour-of [^js material n]
  (let [^js c (or (.-color material) (THREE/Color. 1 1 1))
        a (js/Float32Array. (* 3 n))]
    (dotimes [i n] (aset a (* 3 i) (.-r c)) (aset a (+ 1 (* 3 i)) (.-g c)) (aset a (+ 2 (* 3 i)) (.-b c)))
    a))

(defn- plain
  "A mesh as non-indexed geometry with only position, normal, color, ao and kind."
  [^js mesh]
  (let [^js g (.toNonIndexed (.-geometry mesh))
        n (.. g -attributes -position -count)
        ^js m (.-material mesh)
        ao (ao-of g n)]
    (doseq [a ["uv" "uv1" "tangent" "color"]] (.deleteAttribute g a))
    (.setAttribute g "color" (THREE/BufferAttribute. (colour-of m n) 3))
    (.setAttribute g "ao" (THREE/BufferAttribute. ao 1))
    (.setAttribute g "kind" (THREE/BufferAttribute. (filled n (look/kind (.-name m))) 1))
    ;; Blender's grass is a thin skin on the soil: lifted a little, or at the world's scale the two
    ;; fight over the same depth and the grass speckles.
    (when (= (look/kind (.-name m)) (:grass look/kinds)) (.translate g 0 0.03 0))
    g))

(defn- meshes [^js scene]
  (let [out #js []]
    (.traverse scene (fn [^js o] (when (.-isMesh o) (.push out o))))
    (vec out)))

(defn- piece
  "A loaded file as one geometry, or (the robot) its meshes' geometries by name."
  [k ^js gltf]
  (let [ms (meshes (.-scene gltf))]
    (if (= k :robot)
      (into {} (map (fn [^js m] [(.-name m) (plain m)]) ms))
      (bgu/mergeGeometries (into-array (map plain ms)) false))))

(defn load!
  "Promise of every piece: {:island [geometry …] :building […] … :robot {\"body\" geometry …}}."
  []
  (let [loader (GLTFLoader.)
        jobs (for [[k file-names] files file file-names]
               (.then (.loadAsync loader (str base file ".glb")) (fn [g] [k (piece k g)])))]
    (.then (js/Promise.all (into-array jobs))
           (fn [pairs]
             (reduce (fn [m [k p]] (update m k (fnil conj []) p)) {} pairs)))))
