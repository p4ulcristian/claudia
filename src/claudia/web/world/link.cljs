(ns claudia.web.world.link
  "What the page (claudia.web.world, the main module) may ask the running world (the lazy :world
  module) for: the world's view registers it while it runs.")

(defonce api
  ;; While a world runs: {:screen-rect (fn [robot-id] {:left :top :width :height} or nil),
  ;; :board-rect (fn [island-id] …), :screen-image / :board-image (fn [id] canvas or nil)}; else nil.
  (atom nil))
