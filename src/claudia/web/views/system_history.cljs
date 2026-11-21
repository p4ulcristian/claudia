(ns claudia.web.views.system-history
  "System's History tab (README decision 96): the last 7 days, a sample a minute: busy cores and
  memory (waiting on a press of its key) as lines in percent over the days, and the crashes,
  out-of-memory kills and failed units as marks; a click on a mark shows what happened."
  (:require [reagent.hooks :as h]
            [claudia.web.format :as format]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.pure.system-chart :as pure]
            [claudia.web.views.system-bits :as bits]))

(def ^:private plot-h "The lines' height in px; the days' names go under it." 150)
(def ^:private foot-h 22)

(defn- grid
  "The 0, 50 and 100% lines (named at the left), a line at each midnight and each day's name
  under its middle (one too narrow to name left unnamed)."
  [spans from to w]
  [:g.sy-grid
   (for [p [0 50 100] :let [y (- plot-h (* plot-h (/ p 100)))]]
     ^{:key p} [:g [:line {:x1 0 :x2 w :y1 y :y2 y :class (when (= p 0) "base")}]
                (when (pos? p) [:text.pct {:x 3 :y (- y 4)} (str p "%")])])
   (for [{:keys [at end] day :name} spans
         :let [x (pure/x-of at from to w) x2 (pure/x-of end from to w)]]
     ^{:key at}
     [:g (when (> at from) [:line {:x1 x :x2 x :y1 0 :y2 plot-h}])
      (when (> (- x2 x) 34) [:text.day {:x (/ (+ x x2) 2) :y (+ plot-h 15)} day])])])

(defn- mark [m picked? pick!]
  [:g.sy-mark {:class [(:tone m) (when picked? "on")] :on-click #(pick! m)}
   [:line {:x1 (:x m) :x2 (:x m) :y1 8 :y2 plot-h}]
   [:circle {:cx (:x m) :cy 7 :r 4}]
   [:rect.hit {:x (- (:x m) 7) :y 0 :width 14 :height plot-h}]])

(defn- chart-svg [{:keys [rows events shown to w picked pick!]}]
  (let [from (- to pure/week-ms)]
    [:svg.sy-chart-svg {:width w :height (+ plot-h foot-h) :role "img" :aria-label "The last 7 days"}
     [grid (pure/day-spans from to (.getTimezoneOffset (js/Date.))) from to w]
     (for [{:keys [id tone d]} (pure/lines rows shown to w plot-h)] ^{:key id} [:path.sy-line {:class tone :d d}])
     (for [m (pure/marks events to w)]
       ^{:key (str (:at m) (:kind m) (:pid m))} [mark m (= (:at m) (:at picked)) pick!])]))

(defn- legend [shown toggle!]
  [:div.sy-legend
   (for [[id label _ tone] pure/series]
     ^{:key id}
     [:button.sy-key-btn {:type "button" :class [tone (when (shown id) "on")] :aria-pressed (boolean (shown id))
                          :on-click #(toggle! id)}
      [:i] label])
   [:span.sy-key-mark.red [:i] "crash, out of memory"]
   [:span.sy-key-mark.amber [:i] "failed unit, reboot needed"]])

(defn- picked-event [{:keys [tab now]} e]
  (let [[log toggle-log] (bits/use-unit-log tab (when (:unit e) {:unit (:unit e) :scope "system"}))]
    [:article.sy-card {:class (pure/event-tone (:kind e))}
     [:div.sy-card-row
      [:div.sy-card-main
       [:div.sy-card-line (when-let [who (or (:name e) (:unit e))] [:<> [:b who] " "]) (:text e)]
       [:div.sy-card-facts (format/clock-text (:at e) now)]]
      [:div.sy-card-acts (when toggle-log [bits/button {:on-click toggle-log} (if log "Hide log" "Log")])]]
     (when log [bits/log-lines log now])]))

(defn history
  "The week's chart from the History rows (week: {:rows :events}, nil while asked)."
  [{:keys [week now] :as sc}]
  (let [ref (h/use-ref nil)
        w (hooks/use-width ref [])
        [shown set-shown] (h/use-state #{"cores" "memory"})
        [picked set-picked] (h/use-state nil)]
    [:div.sy-history
     [:div.sy-chart
      [:div {:ref ref}
       (cond (nil? week) [:div.sy-empty "Reading the week…"]
             (pos? w) [chart-svg {:rows (:rows week) :events (:events week) :shown shown :to now :w w
                                  :picked picked :pick! #(set-picked (when (not= (:at %) (:at picked)) %))}])]]
     [legend shown #(set-shown (fn [s] (if (s %) (disj s %) (conj s %))))]
     (if picked
       ^{:key (:at picked)} [:f> picked-event sc picked]
       [:p.sy-hint "Click a mark to see what happened."])]))
