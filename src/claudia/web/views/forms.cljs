(ns claudia.web.views.forms
  "The predefined forms, drawn in the board's chalk: pipeline, timeline, compare, chart, stats.
  Data shapes as the draw tool describes them (PROTOCOL.md)."
  (:require [reagent.hooks :as h]
            [claudia.web.scene3d :as scene3d]
            [claudia.web.views.frame :as frame]))

(def ^:private colors #{"green" "red" "amber" "blue" "grey" "violet"})
(defn- color-var [c] (when (colors c) (str "var(--" c ")")))
(defn- items [v] (if (sequential? v) v []))
(def ^:private status-mark {"done" "✓" "running" "…" "failed" "✗" "skipped" "–" "todo" ""})

;; Stages side by side while each gets at least this many px; narrower, they stack.
(def ^:private stage-min 120)

(defn- pipeline [data]
  (let [stages (items (:stages data))
        box (h/use-ref nil)
        [down? set-down] (h/use-state false)]
    (h/use-effect
     (fn []
       (let [el (.-current box)
             fit #(set-down (< (.-clientWidth el) (* (count stages) stage-min)))
             ro (js/ResizeObserver. fit)]
         (fit)
         (.observe ro el)
         #(.disconnect ro)))
     [(count stages)])
    [:div.f-pipeline {:ref #(set! (.-current box) %) :class (when down? "down")}
     (for [[i s] (map-indexed vector stages)
           :let [st (or (:status s) "todo")]]
       ^{:key (or (:id s) i)}
       [:<>
        (when (pos? i)
          [:span.f-arrow {:class (when (= "done" (:status (nth stages (dec i)))) "lit") :aria-hidden true}])
        [:div.f-stage.chalk-box {:class (str "st-" st)}
         [:span.f-stage-mark (status-mark st)]
         [:span.f-stage-label (or (:label s) (:id s))]
         (when (:note s) [:span.f-note (:note s)])]])]))

(defn- timeline [data]
  [:ol.f-timeline
   (for [[i e] (map-indexed vector (items (:events data)))]
     ^{:key (or (:id e) i)}
     [:li {:class (str "st-" (or (:status e) "none")) :style {"--dot" (color-var (:color e))}}
      [:span.f-at (:at e)]
      [:span.f-dot {:aria-hidden true}]
      [:span.f-event
       [:b (or (:label e) (:id e))
        (when (and (:status e) (not= "none" (:status e))) [:i.f-tag (:status e)])]
       (when (:note e) [:span.f-note (:note e)])]])])

(defn- compare-form [data]
  [:div.f-compare
   (for [[i o] (map-indexed vector (items (:options data)))]
     ^{:key (or (:id o) i)}
     [:section.f-option.chalk-box {:class (when (:picked o) "picked")}
      [:h4 (or (:label o) (:id o)) (when (:picked o) [:span.f-tag "picked"])]
      (when (:note o) [:p.f-note (:note o)])
      [:ul
       (for [[j p] (map-indexed vector (items (:pros o)))] ^{:key (str "p" j)} [:li.pro p])
       (for [[j c] (map-indexed vector (items (:cons o)))] ^{:key (str "c" j)} [:li.con c])]])])

(defn- stats [data]
  [:div.f-stats
   (for [[i t] (map-indexed vector (items (:tiles data)))]
     ^{:key (or (:id t) i)}
     [:div.f-tile.chalk-box {:style {"--tile" (color-var (:color t))}}
      [:span.f-label (or (:label t) (:id t))]
      [:span.f-value (str (:value t))]
      (when (number? (:progress t))
        [:span.f-bar [:i {:style {:width (str (* 100 (max 0 (min 1 (:progress t)))) "%")}}]])
      (when (:note t) [:span.f-note (:note t)])])])

(defn form-view [form data & [block-id]]
  [:div.form-view {:class (str "form-" form)}
   (case form
     "pipeline" [:f> pipeline data]
     "timeline" [timeline data]
     "compare" [compare-form data]
     "chart" [:div.f-chart [:f> frame/chart-frame data]]
     "stats" [stats data]
     "scene3d" [scene3d/scene3d-form data block-id]
     [:p.f-note (str "Unknown form \"" form "\".")])])
