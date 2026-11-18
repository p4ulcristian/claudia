(ns claudia.web.face.art
  "Claudia's face as drawing data (2026-11-18, the noir look the user picked): an orange-bob girl in
  oval sunglasses and a black leather jacket, painted once (FLUX.2) with her glasses on
  (/face/noir-up.webp). Over it, in one 512-unit square: the lenses as a screen (code rain, a few
  symbols, a ticker of words) and the Claude spark as her hair clip.")

(def view-box "0 0 512 512")
(def ink "#120d0a")
(def green "#3cff6a")
(def red "#ff4a4a")

(def picture "/face/noir-up.webp")

(def lenses
  "The two lenses [cx cy rx ry]."
  [[178 298 50 29] [329 298 52 29]])

(def spark-transform
  "Where the spark clip sits, still: on her hair, above her right eye."
  "translate(400 159) rotate(-12) scale(0.52) translate(-150 -150)")
