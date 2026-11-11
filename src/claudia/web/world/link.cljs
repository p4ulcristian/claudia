(ns claudia.web.world.link
  "What the page (claudia.web.world, the main module) may ask the running world (the lazy :world
  module) for: the world's view registers it while it runs.")

(defonce api
  ;; {:screen-rect (fn [robot-id] {:left :top :width :height} or nil)} while a world runs, else nil.
  (atom nil))
