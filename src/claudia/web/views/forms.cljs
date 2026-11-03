(ns claudia.web.views.forms
  "The predefined forms, drawn in the board's chalk: pipeline, timeline, compare, chart, stats.
  Data shapes as the draw tool describes them (PROTOCOL.md)."
  (:require [clojure.string :as str]
            [reagent.hooks :as h]
            [claudia.shared.forms :as forms]
            [claudia.web.scene3d :as scene3d]))

(defn badge
  "The badge over a block: its name, then its kind (graph, a form's name, freeform or template) in
  the kind's chalk colour; a form's when-line as its tooltip."
  [block]
  (let [form? (= "form" (:kind block))
        html? (= "html" (:kind block))
        kind (cond (= "graph" (:kind block)) "graph"
                   form? (:form block)
                   (:template block) "template"
                   :else "freeform")
        label (if form? (str/lower-case (forms/label-of (:form block))) kind)
        nm (or (not-empty (:name block)) (when html? (:template block)))]
    [:span.block-badge {:class (str "b-" kind) :title (when form? (:when (forms/spec-of (:form block))))}
     [:b label]
     (when nm [:span.block-name nm])]))

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
        [:div.f-stage.chalk-box {:class (str "st-" st) :data-item (:id s)}
         [:span.f-stage-mark (status-mark st)]
         [:span.f-stage-label (or (:label s) (:id s))]
         (when (:note s) [:span.f-note (:note s)])]])]))

(defn- timeline [data]
  [:ol.f-timeline
   (for [[i e] (map-indexed vector (items (:events data)))]
     ^{:key (or (:id e) i)}
     [:li {:class (str "st-" (or (:status e) "none")) :data-item (:id e) :style {"--dot" (color-var (:color e))}}
      [:span.f-at (:at e)]
      [:span.f-dot {:aria-hidden true}]
      [:span.f-event
       [:b (or (:label e) (:id e))]
       (when (and (:status e) (not= "none" (:status e))) [:i.f-tag (:status e)])
       (when (:note e) [:span.f-note (:note e)])]])])

(defn- compare-form [data]
  [:div.f-compare
   (for [[i o] (map-indexed vector (items (:options data)))]
     ^{:key (or (:id o) i)}
     [:section.f-option.chalk-box {:class (when (:picked o) "picked") :data-item (:id o)}
      [:h4 (or (:label o) (:id o)) (when (:picked o) [:span.f-tag "picked"])]
      (when (:note o) [:p.f-note (:note o)])
      [:ul
       (for [[j p] (map-indexed vector (items (:pros o)))] ^{:key (str "p" j)} [:li.pro p])
       (for [[j c] (map-indexed vector (items (:cons o)))] ^{:key (str "c" j)} [:li.con c])]])])

(defn- stats [data]
  [:div.f-stats
   (for [[i t] (map-indexed vector (items (:tiles data)))]
     ^{:key (or (:id t) i)}
     [:div.f-tile.chalk-box {:data-item (:id t) :style {"--tile" (color-var (:color t))}}
      [:span.f-label (or (:label t) (:id t))]
      [:span.f-value (str (:value t))]
      (when (number? (:progress t))
        [:span.f-bar [:i {:style {:width (str (* 100 (max 0 (min 1 (:progress t)))) "%")}}]])
      (when (:note t) [:span.f-note (:note t)])])])

;; Charts are Vega-Lite, loaded from the CDN the first time one is drawn, themed to the board.
(defonce ^:private embed-lib (atom nil))
(defn- embed []
  (or @embed-lib
      (reset! embed-lib (-> ((js/Function. "u" "return import(u)") "https://cdn.jsdelivr.net/npm/vega-embed@6/+esm")
                            (.then #(.-default ^js %))))))

(defn- chart [data]
  (let [box (h/use-ref nil)
        k (js/JSON.stringify (clj->js data))]
    (h/use-effect
     (fn []
       (when-let [^js el (.-current box)]
         (let [gone (atom false)
               view (atom nil)
               cs (js/getComputedStyle el)
               v #(str/trim (.getPropertyValue cs %))
               fg (v "--fg") muted (v "--muted") line (v "--line")
               palette (mapv v ["--blue" "--amber" "--green" "--violet" "--red" "--grey"])
               x (or (:x data) "x") y (or (:y data) "y")
               values (mapv #(dissoc % :id) (items (:rows data)))
               numeric-x (and (seq values) (every? #(number? (get % (keyword x))) values))
               mark (or (:mark data) "bar")
               spec (merge
                     {"$schema" "https://vega.github.io/schema/vega-lite/v5.json"
                      :width "container" :height 220 :background "transparent"
                      :data {:values values}
                      :mark (merge {:type mark :tooltip true}
                                   (if (#{"line" "area"} (:mark data)) {:point true :strokeWidth 3} {:cornerRadiusEnd 3}))
                      :encoding (merge {:x {:field x :type (if numeric-x "quantitative" "ordinal") :sort nil :axis {:labelAngle 0}}
                                        :y {:field y :type "quantitative"}}
                                       (if (:color data) {:color {:field (:color data) :type "nominal"}} {:color {:value (first palette)}}))
                      :config {:font "Atkinson Hyperlegible" :view {:stroke nil} :range {:category palette}
                               :title {:font "Kalam" :fontSize 18 :fontWeight 700 :color fg :anchor "start"}
                               :axis {:labelColor muted :titleColor muted :domainColor line :tickColor line :gridColor line :gridOpacity 0.5
                                      :labelFontSize 12 :titleFont "Kalam" :titleFontSize 14 :titleFontWeight 700}
                               :legend {:labelColor muted :titleColor muted}}}
                     (when (:title data) {:title (:title data)}))]
           (-> (embed)
               (.then (fn [vega-embed] (when-not @gone (vega-embed el (clj->js spec) #js {:actions false :renderer "svg"}))))
               (.then (fn [^js r] (reset! view (some-> r .-view))))
               (.catch (fn [e] (when-not @gone (set! (.-textContent el) (str "Chart failed: " (or (.-message e) e)))))))
           #(do (reset! gone true) (some-> ^js @view .finalize)))))
     [k])
    [:div.f-chart {:ref #(set! (.-current box) %)}]))

(defn form-view [form data & [block-id]]
  [:div.form-view {:class (str "form-" form)}
   (case form
     "pipeline" [:f> pipeline data]
     "timeline" [timeline data]
     "compare" [compare-form data]
     "chart" [:f> chart data]
     "stats" [stats data]
     "scene3d" [scene3d/scene3d-form data block-id]
     [:p.f-note (str "Unknown form \"" form "\".")])])
