(ns claudia.web.scene3d.ink
  "How a scene3d's parts look. The diagram style (the default): flat colour in three bands (cel
  shading) and a thin light outline round every part, like chalk on the board; the outline is the
  part's own shape pushed out along its normals and drawn from behind, a fixed number of screen
  pixels wide at any distance. The realistic style: physically based materials (`material: steel`),
  the part's colour tinting the preset."
  (:require ["three" :as THREE]))

(def ink (THREE/Color. "#f3efe6"))
(def focus-ink (THREE/Color. "#ffc247"))
(def outline-px 1.7)
(def focus-px 3.4)

;; The viewport size in CSS pixels, shared by every outline (set when the stage resizes).
(def ^js viewport #js {:value (THREE/Vector2. 800 500)})

(def ^:private gradient
  (let [bands (js/Uint8Array. #js [120 120 120 255 200 200 200 255 255 255 255 255])
        ^js t (THREE/DataTexture. bands 3 1 THREE/RGBAFormat)]
    (set! (.-minFilter t) THREE/NearestFilter)
    (set! (.-magFilter t) THREE/NearestFilter)
    (set! (.-needsUpdate t) true)
    t))

(defn toon-material [color op wire?]
  (let [^js m (THREE/MeshToonMaterial. #js {:color color :gradientMap gradient :side THREE/DoubleSide
                                            :transparent (< op 1) :opacity op :wireframe (boolean wire?)})]
    (set! (.. m -userData -op) op)
    m))

(def ^:private vertex "
  #include <common>
  #include <skinning_pars_vertex>
  uniform float thickness;
  uniform vec2 viewport;
  void main() {
    #include <beginnormal_vertex>
    #include <skinbase_vertex>
    #include <skinnormal_vertex>
    #include <begin_vertex>
    #include <skinning_vertex>
    vec4 clip = projectionMatrix * modelViewMatrix * vec4(transformed, 1.0);
    vec3 n = normalize(normalMatrix * objectNormal);
    vec2 dir = (projectionMatrix * vec4(n, 0.0)).xy;
    float len = length(dir);
    dir = len > 1e-5 ? dir / len : vec2(0.0);
    clip.xy += dir * (thickness * 2.0 / viewport) * clip.w;
    gl_Position = clip;
  }")

(def ^:private fragment "
  uniform vec3 color;
  uniform float opacity;
  void main() { gl_FragColor = vec4(color, opacity); }")

(defn outline-material
  "The outline's material; works on a SkinnedMesh too (a person), bending with the bones."
  []
  (THREE/ShaderMaterial. #js {:uniforms #js {:color #js {:value (.clone ink)}
                                             :opacity #js {:value 1}
                                             :thickness #js {:value outline-px}
                                             :viewport viewport}
                              :vertexShader vertex
                              :fragmentShader fragment
                              :side THREE/BackSide
                              :transparent true
                              :depthWrite false
                              :toneMapped false}))

(defn set-outline!
  "An outline's look: how far it has appeared (k), faded back (dim), lit (thick amber), opacity."
  [^js outline k dim lit? op]
  (let [^js u (.. outline -material -uniforms)]
    (set! (.. u -opacity -value) (* k (cond (< dim 1) 0.28 (< op 1) 0.6 :else 1)))
    (set! (.. u -thickness -value) (if lit? focus-px outline-px))
    (.copy (.. u -color -value) (if lit? focus-ink ink))))

;; ---- materials (the realistic style) ----

(def presets
  {"steel" {:color "#c8ccd0" :metalness 1 :roughness 0.32}
   "brushed-steel" {:color "#b9bdc1" :metalness 1 :roughness 0.42 :anisotropy 0.8}
   "aluminium" {:color "#d9dde0" :metalness 1 :roughness 0.38}
   "chrome" {:color "#ffffff" :metalness 1 :roughness 0.04}
   "copper" {:color "#c87533" :metalness 1 :roughness 0.3}
   "brass" {:color "#c9a24a" :metalness 1 :roughness 0.28}
   "iron" {:color "#585b60" :metalness 0.9 :roughness 0.62}
   "plastic" {:roughness 0.42 :metalness 0 :clearcoat 0.35 :clearcoatRoughness 0.3}
   "matte-plastic" {:roughness 0.8 :metalness 0}
   "rubber" {:color "#222426" :roughness 0.96 :metalness 0}
   "enamel" {:roughness 0.28 :metalness 0.1 :clearcoat 1 :clearcoatRoughness 0.08}
   "ceramic" {:color "#f1f1ee" :roughness 0.14 :metalness 0 :clearcoat 1 :clearcoatRoughness 0.05}
   "wood" {:color "#8a5a2b" :roughness 0.68 :metalness 0}
   "glass" {:color "#cfe6f2" :roughness 0.03 :metalness 0 :clearcoat 0.5 :clearcoatRoughness 0.02
            :opacity 0.14 :ior 1.5 :envMapIntensity 0.6}})

(def ^:private plain {:roughness 0.55 :metalness 0.05})

(defn material-for
  "A part's physical material: its preset (if any), tinted by its colour."
  [tint part base-op]
  (let [preset (get presets (:material part))
        {:keys [color opacity] :as all} (merge plain preset)
        params (dissoc all :color :opacity)
        op (if (number? (:opacity part)) (:opacity part) (or opacity base-op))
        ^js m (THREE/MeshPhysicalMaterial.
               (clj->js (merge params {:color (or tint (THREE/Color. (or color "#8fc4ea")))
                                       :side THREE/DoubleSide
                                       :transparent (< op 1)
                                       :opacity op
                                       :wireframe (boolean (:wire part))})))]
    (set! (.. m -userData -op) op)
    m))
