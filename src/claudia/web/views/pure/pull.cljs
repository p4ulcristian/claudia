(ns claudia.web.views.pure.pull
  "Pull to reload on the rail (a touch screen), as numbers: how far the circles follow the finger
  and when letting go reloads.")

(def ready-px
  "Pulled this far (after the give), letting go reloads."
  64)

(def max-px
  "The circles never follow further than this."
  96)

(defn distance
  "How far the circles move for a finger dragged dy px down from the top: half of it (a give that
  feels like pulling), never below 0 or past max-px."
  [dy]
  (-> (* 0.5 (max 0 dy)) (min max-px)))

(defn ready?
  "Whether letting go at distance d reloads."
  [d]
  (>= d ready-px))
