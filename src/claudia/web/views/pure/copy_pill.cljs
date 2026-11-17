(ns claudia.web.views.pure.copy-pill
  "Where the Copy pill goes, as data.")

(def gap "Room between the pill and the selected text (clear of the upper handle's knob), px." 30)
(def half-width "Half the pill's width, px." 34)
(def height "The pill's height, px." 40)
(def margin "Least room between the pill and the window's edges, px." 8)

(defn position
  "The pill's top-left {:x :y} for a selection's rect {:left :top :right :bottom} in a window
  width px wide: centred over the selection, above it by gap; below it by gap when there is no
  room above; kept inside the window's sides."
  [{:keys [left top right bottom]} width]
  (let [mid (/ (+ left right) 2)
        above (- top gap height)]
    {:x (-> (- mid half-width) (max margin) (min (- width margin (* 2 half-width))))
     :y (if (>= above margin) above (+ bottom gap))}))
