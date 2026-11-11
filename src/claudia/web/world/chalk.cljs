(ns claudia.web.world.chalk
  "The world's chalk look in TSL (node materials): cel shading in three bands over the Blender
  pieces' own colours and baked occlusion, a faint chalk grain fixed to the world (it never swims
  while the view slides), and a chalk outline drawn as each shape's back faces pushed out by a few
  screen pixels. The still world's material also reads, per vertex, its island and building: grass
  takes the island's health colour, a dead island goes grey, a building's windows light up while
  one of its chats works, lamps glow."
  (:require ["three/webgpu" :as THREE]
            ["three/tsl" :as tsl]
            [claudia.web.world.look :as look]))

(def ^:private bands
  (let [^js t (THREE/DataTexture. (js/Uint8Array. #js [110 110 110 255 190 190 190 255 255 255 255 255]) 3 1)]
    (set! (.-minFilter t) THREE/NearestFilter)
    (set! (.-magFilter t) THREE/NearestFilter)
    (set! (.-needsUpdate t) true)
    t))

(def max-islands 16)
(def max-buildings 96)

(defn make-uniforms
  "The per-island and per-building values the still world's material reads (claudia.web.world.health
  sets them): grass colour, greyness, lit windows."
  []
  #js {:grass (tsl/uniformArray (clj->js (repeatedly max-islands #(THREE/Color. 0x5cbf48))) "color")
       :dead (tsl/uniformArray (clj->js (repeat max-islands 0)) "float")
       :lit (tsl/uniformArray (clj->js (repeat max-buildings 0)) "float")
       :width (tsl/uniform 0.05)})

(defn- attr [n t] (tsl/attribute n t))

(defn- whole
  "A per-vertex number that is the same on all three corners, rounded: carried to each pixel it can
  arrive as 0.9999, and grass would speckle with the wrong colour."
  [n]
  (tsl/round (attr n "float")))

(defn- grain
  "A faint chalk grain, fixed to the world."
  []
  (.add (.mul (tsl/mx_noise_float (.mul tsl/positionWorld 1.3)) 0.045) 1))

(defn- kind? [k] (.equal (whole "kind") k))

(defn- surface
  "The still world's colour before shading: its material's, grass by health, windows lit."
  [^js u]
  (let [isl (.toInt (whole "isl"))
        bld (.toInt (.max (whole "bld") 0))
        base (attr "color" "vec3")
        grassy (tsl/select (kind? (:grass look/kinds)) (.element (.-grass u) isl) base)]
    (tsl/select (kind? (:window look/kinds)) (tsl/mix base (tsl/color 0xffcf6e) (.element (.-lit u) bld)) grassy)))

(defn- greyed [^js u c]
  (let [isl (.toInt (whole "isl"))]
    (tsl/mix c (.mul (tsl/vec3 (tsl/luminance c)) 0.7) (.element (.-dead u) isl))))

(defn- glow [^js u]
  (let [bld (.toInt (.max (whole "bld") 0))]
    (tsl/select (kind? (:glow look/kinds)) (attr "color" "vec3")
                (tsl/select (kind? (:window look/kinds)) (.mul (tsl/color 0xffb84d) (.mul (.element (.-lit u) bld) 0.8))
                            (tsl/vec3 0)))))

(defn world-material
  "The still world's material (u: make-uniforms)."
  [^js u]
  (doto (THREE/MeshToonNodeMaterial. #js {:gradientMap bands})
    (set! -colorNode (greyed u (.mul (.mul (surface u) (attr "ao" "float")) (grain))))
    (set! -emissiveNode (glow u))))

(defn piece-material
  "A Blender piece shown instanced (the robots' parts): its colours, occlusion and grain, cel
  shaded; three.js multiplies in each instance's colour (the chat's) where the mesh has them."
  []
  (doto (THREE/MeshToonNodeMaterial. #js {:gradientMap bands})
    (set! -colorNode (.mul (.mul (attr "color" "vec3") (attr "ao" "float")) (grain)))))

(defn glow-material
  "A shining piece (the robots' eyes and jet): its own colour, unlit, times the instance's."
  [opts]
  (doto (THREE/MeshBasicNodeMaterial. (clj->js opts))
    (set! -colorNode (attr "color" "vec3"))))

(defn outline-material
  "The chalk outline: back faces pushed out along their normals by width (a uniform node, world
  units, kept at outline-px screen pixels by the engine)."
  [^js width]
  (doto (THREE/MeshBasicNodeMaterial. #js {:side THREE/BackSide})
    (set! -colorNode (tsl/color look/ink))
    (set! -positionNode (.add tsl/positionLocal (.mul tsl/normalLocal width)))))

(defn outline-width
  "The outline's width in world units for a view dist away, a vertical fov (degrees) and a canvas h
  pixels high."
  [dist fov h]
  (* look/outline-px dist (/ (* 2 (js/Math.tan (/ (* fov js/Math.PI) 360))) (max 1 h))))
