(ns claudia.web.face.art
  "Claudia's face as drawing data (2026-11-18, the noir look the user picked): an orange-bob girl in
  oval sunglasses and a black leather jacket, painted once (FLUX.2) in two pictures: glasses on
  (/face/noir-up.webp) and glasses pulled down to the tip of her nose (/face/noir-down.webp). Over
  them, all in one 512-unit square: her eyes as vectors (so they can follow, blink and wink), the
  lenses as a screen (code rain, symbols, a ticker of words) and the Claude spark as her hair clip.")

(def view-box "0 0 512 512")
(def ink "#120d0a")
(def skin "#f6e7cf")
(def sclera "#eee9dc")
(def green "#3cff6a")
(def red "#ff4a4a")
(def amber "#ffc04a")

(def pictures {:up "/face/noir-up.webp" :down "/face/noir-down.webp"})

(def lenses
  "The two lenses [cx cy rx ry] with the glasses on (:up) and pulled down (:down)."
  {:up [[178 298 50 29] [329 298 52 29]]
   :down [[187 334 50 23] [325 334 52 23]]})

(def eyes
  "Each eye (seen with the glasses down): where its iris rests, the almond it shows through, the
  upper lid open and shut, the lower lid and the flick at the outer corner."
  {:far {:iris [191 280] :almond "M159 279Q186 260 224 279L221 282Q194 296 163 285Z"
         :lid "M157 280Q186 258 225 279" :shut "M157 282Q190 292 225 281" :happy "M159 284Q190 266 223 283"
         :low "M163 285Q194 296 221 282" :flick "M159 277L148 265" :box [150 255 80 45]}
   :near {:iris [316 280] :almond "M283 279Q316 258 348 272L345 276Q316 296 288 283Z"
          :lid "M282 280Q316 256 349 272" :shut "M282 282Q316 292 349 274" :happy "M284 283Q316 264 347 276"
          :low "M288 283Q316 296 345 276" :flick "M347 272L357 261" :box [278 252 80 48]}})

(def eye-cover
  "Skin over the painted eyes of the glasses-down picture, under the drawn ones."
  ["M146 268Q186 250 230 270L230 292Q190 304 150 290Z" "M278 270Q318 248 358 262L352 290Q318 304 282 288Z"])

(def spark-at "Where the spark clip sits: centre x y, its resting turn, its scale." [400 159 -12 0.52])
(def feet "The point she squashes and turns round." [256 512])
