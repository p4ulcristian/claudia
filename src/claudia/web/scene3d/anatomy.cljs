(ns claudia.web.scene3d.anatomy
  "Real human anatomy in a 3D scene: a part {shape: \"anatomy\", ...} becomes the structures it
  names (claudia.web.scene3d.structures), each its own part with the structure's mesh, loaded here:
  one GLB per structure (claudia.shared.anatomy), in body coordinates (metres, y up, facing +z,
  the body's right at -x)."
  (:require ["three" :as THREE]
            ["three/examples/jsm/loaders/GLTFLoader.js" :refer [GLTFLoader]]
            ["three/examples/jsm/libs/meshopt_decoder.module.js" :refer [MeshoptDecoder]]
            ["three/examples/jsm/utils/BufferGeometryUtils.js" :refer [mergeGeometries]]
            [claudia.shared.anatomy :as shared]
            [claudia.web.api :as api]
            [claudia.web.scene3d.structures :as structures]))

;; ---- loading (effects) ----

(defonce ^:private index-promise (atom nil))
(defonce ^:private geometries (atom {})) ; file -> BufferGeometry, once loaded
(defonce ^:private loading (atom {}))    ; file -> Promise

(defn- index []
  (or @index-promise
      (reset! index-promise
              (-> (api/fetch-json (shared/page-path "index.json"))
                  (.then #(:structures %))
                  (.catch (fn [e] (reset! index-promise nil) (throw e)))))))

(defn- plain-geometry
  "The loaded model's mesh as plain float positions in body coordinates (its quantised positions
  undone, the node's transform applied), with smooth normals."
  [^js gltf]
  (let [^js scene (.-scene gltf)
        _ (.updateMatrixWorld scene true)
        ^js mesh (first (filter #(.-isMesh ^js %) (.-children scene)))
        ^js g (.-geometry mesh)
        ^js pos (.getAttribute g "position")
        n (.-count pos)
        arr (js/Float32Array. (* 3 n))
        out (THREE/BufferGeometry.)]
    (dotimes [i n]
      (aset arr (* 3 i) (.getX pos i)) (aset arr (+ 1 (* 3 i)) (.getY pos i)) (aset arr (+ 2 (* 3 i)) (.getZ pos i)))
    (.setAttribute out "position" (THREE/BufferAttribute. arr 3))
    (.setIndex out (.getIndex g))
    (.applyMatrix4 out (.-matrixWorld mesh))
    (.computeVertexNormals out)
    (.computeBoundingBox out)
    out))

(defn- tube-geometry
  "A nerve's or vessel's centre lines (flat [x y z …] arrays, body coordinates) as one tube
  geometry radius thick."
  [lines radius]
  (let [parts (for [^js l lines :let [n (quot (.-length l) 3)] :when (> n 1)]
                (let [pts (into-array (for [i (range n)] (THREE/Vector3. (aget l (* 3 i)) (aget l (+ 1 (* 3 i))) (aget l (+ 2 (* 3 i))))))]
                  (THREE/TubeGeometry. (THREE/CatmullRomCurve3. pts) (max 4 (* 2 n)) radius 6 false)))
        ^js g (mergeGeometries (into-array parts) false)]
    (.computeBoundingBox g)
    g))

(defn- key-of [file radius] (if radius (str file "@" radius) file))

(defn- load-lines! [file radius]
  (-> (api/fetch-json (shared/page-path file))
      (.then (fn [{:keys [lines]}] (tube-geometry (map clj->js lines) radius)))))

(defn- load-file!
  "Promise of a structure's geometry loaded and kept (a mesh, or a nerve's or vessel's tube radius
  thick)."
  [file radius]
  (let [k (key-of file radius)]
    (or (get @loading k)
        (let [p (-> (if radius
                      (load-lines! file radius)
                      (.then (.loadAsync (doto (GLTFLoader.) (.setMeshoptDecoder MeshoptDecoder)) (shared/page-path file))
                             plain-geometry))
                    (.then (fn [g] (swap! geometries assoc k g) k)))]
          (swap! loading assoc k p)
          (.catch p (fn [_] (swap! loading dissoc k)))
          p))))

(defn geometry
  "A loaded structure's geometry (shared by every part showing it), or nil; radius for a nerve or
  vessel."
  ([file] (geometry file nil))
  ([file radius] (get @geometries (key-of file radius))))

(defn prepare
  "Promise of {:parts :errors}: every anatomy part replaced by its structures, their models loaded;
  the other parts as they are."
  [parts]
  (if-not (some #(= "anatomy" (:shape %)) parts)
    (js/Promise.resolve {:parts parts :errors []})
    (-> (index)
        (.then (fn [idx]
                 (let [expanded (mapv (fn [p] (if (= "anatomy" (:shape p)) (structures/expand p (structures/structures-of idx p)) [p])) parts)
                       missing (keep (fn [[p ps]] (when (and (= "anatomy" (:shape p)) (empty? ps)) (str "no structure called " (pr-str (:ref p)))))
                                     (map vector parts expanded))
                       out (vec (apply concat expanded))]
                   (-> (js/Promise.allSettled (into-array (map (fn [[f r]] (load-file! f r)) (distinct (keep #(when (:file %) [(:file %) (when (:lines %) (:radius %))]) out)))))
                       (.then (fn [_] {:parts (filterv #(or (not= "anatomy" (:shape %)) (geometry (:file %) (when (:lines %) (:radius %)))) out)
                                       :errors (vec missing)}))))))
        (.catch (fn [e] {:parts (filterv #(not= "anatomy" (:shape %)) parts) :errors [(.-message e)]})))))
