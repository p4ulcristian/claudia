(ns claudia.web.world.look
  "What each material of the Blender pieces is to the shader (a number per vertex, `kind`), and the
  chalk look's colours.")

(def kinds
  "kind numbers: plain (lit as it is), grass (coloured by the server's health), window (lit while a
  chat works in its building), glow (lamps, eyes, the robot's jet: shine at night too)."
  {:plain 0 :grass 1 :window 2 :glow 3})

(def ^:private by-material
  {"grass" :grass "window" :window "lamp" :glow "eyes" :glow "jet" :glow})

(defn kind
  "The kind number of a material by its name in the Blender file."
  [material-name]
  (kinds (get by-material material-name :plain)))

(def ink
  "The chalk outline's colour (Claudia's chalk, as in claudia.web.scene3d.ink)."
  0xf3efe6)

(def outline-px
  "How wide the chalk outline is on screen."
  1.6)
