(ns claudia.web.views.flow
  "The flow form: something travelling through a system, one frame at a time. Stations side by side
  (a station may have steps inside); each lane carries one card that slides to the station or step
  it is at; a caption says what happens. Back / next (or left/right while it has focus), and several
  ways through (scenarios) to switch between."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.hl :as hl]
            [claudia.web.views.code :as code]))

(def ^:private card-w 260)
(def ^:private items code/items)

(defn- card-body
  "A card's text, coloured when it names a language; fresh (a piece of it) is marked as new."
  [text lang fresh]
  (let [html (h/use-memo #(hl/lines text lang) [text lang])
        from (if (seq fresh) (str/index-of text fresh) nil)]
    (cond
      ;; Coloured, the new part is marked by line (the lines it covers).
      html (let [nl-before (when from (count (re-seq #"\n" (subs text 0 from))))
                 nl-in (when from (count (re-seq #"\n" fresh)))]
             [:pre (map-indexed (fn [i l]
                                  ^{:key i}
                                  [:span {:class (when (and from (<= nl-before i (+ nl-before nl-in))) "new")}
                                   [:span {:dangerouslySetInnerHTML (r/unsafe-html l)}]
                                   (when (< i (dec (count html))) "\n")])
                                html)])
      from [:pre (subs text 0 from) [:span.new fresh] (subs text (+ from (count fresh)))]
      :else [:pre text])))

(defn flow-form [data]
  (let [stations (items (:stations data))
        lanes (if (seq (items (:lanes data))) (items (:lanes data)) [{:id ""}])
        ways (if (seq (items (:scenarios data)))
               (mapv #(update % :frames items) (items (:scenarios data)))
               [{:id "" :frames (items (:frames data))}])
        [way set-way] (h/use-state (:id (first ways)))
        [i set-i] (h/use-state 0)
        cur (or (some #(when (= (:id %) way) %) ways) (first ways))
        frames (:frames cur)
        n (min i (max 0 (dec (count frames))))
        f (or (get frames n) {})
        go (fn [d] (set-i (max 0 (min (dec (count frames)) (+ n d)))))
        stage (h/use-ref nil)
        [x set-x] (h/use-state {:w 0 :at {}})
        stations-key (pr-str stations)]
    (h/use-layout-effect
     (fn []
       (when-let [el (.-current stage)]
         (let [measure (fn []
                         (let [box (.getBoundingClientRect el)]
                           (set-x {:w (.-width box)
                                   :at (into {} (for [s (array-seq (.querySelectorAll el "[data-at]"))
                                                      :let [r (.getBoundingClientRect s)]]
                                                  [(.. s -dataset -at) (+ (- (.-left r) (.-left box)) (/ (.-width r) 2))]))})))
               ro (js/ResizeObserver. measure)]
           (measure)
           (.observe ro el)
           #(.disconnect ro))))
     [stations-key])
    (let [card-of (fn [lane fr] (some #(when (= (or (:lane %) (:id (first lanes))) lane) %) (items (:cards fr))))
          station-of (fn [id] (some #(when (or (= (:id %) id) (some (fn [t] (= (:id t) id)) (items (:steps %)))) (:id %)) stations))
          lit (set (keep station-of (concat (map :at (items (:cards f))) (items (:on f)))))
          has? (fn [k id] (some #{id} (items (get f k))))]
      [:div.f-flow {:tab-index 0
                    :on-key-down (fn [e]
                                   (when (#{"ArrowLeft" "ArrowRight"} (.-key e))
                                     (.preventDefault e)
                                     (.stopPropagation e)
                                     (go (if (= "ArrowLeft" (.-key e)) -1 1))))}
       [:div.f-flow-bar
        [:button.f-flow-btn {:type "button" :on-click #(go -1) :disabled (zero? n)} "◀ back"]
        [:button.f-flow-btn {:type "button" :on-click #(go 1) :disabled (>= n (dec (count frames)))} "next ▶"]
        (when (> (count ways) 1)
          [:span.f-flow-ways {:role "radiogroup" :aria-label "Scenario"}
           (for [w ways]
             ^{:key (:id w)}
             [:button.f-flow-btn {:type "button" :role "radio" :aria-checked (= (:id w) (:id cur))
                                  :class (when (= (:id w) (:id cur)) "on")
                                  :on-click #(do (set-way (:id w)) (set-i 0))}
              (or (:label w) (:id w))])])
        [:span.f-flow-n (if (seq frames) (str (inc n) " / " (count frames)) "no frames yet")]]
       [:div.f-flow-stage {:ref #(set! (.-current stage) %)}
        [:div.f-flow-stations {:style {:grid-template-columns (str/join " " (for [s stations] (str (max 1 (* 0.55 (count (items (:steps s))))) "fr")))}}
         (for [s stations]
           ^{:key (:id s)}
           [:div.f-flow-st.chalk-box {:class (when (lit (:id s)) "on") :data-at (:id s) :data-item (:id s)}
            [:b (or (:label s) (:id s))]
            (when (:note s) [:small (:note s)])
            (when (seq (items (:steps s)))
              [:div.f-flow-steps
               (for [t (items (:steps s))]
                 ^{:key (:id t)}
                 [:span.f-flow-step {:data-at (:id t)
                                     :class [(when (has? :on (:id t)) "on") (when (has? :leave (:id t)) "leave") (when (has? :skip (:id t)) "skip")]}
                  (or (:label t) (:id t))])])])]
        (for [l lanes]
          (let [now (card-of (:id l) f)
                card (or now (some #(card-of (:id l) (nth frames %)) (range (dec n) -1 -1)))
                lines (apply max 1 (for [fr frames :let [c (card-of (:id l) fr)] :when c] (count (str/split (str (:body c)) #"\n"))))
                w (min card-w (* 0.48 (:w x)))
                at (if card (get-in x [:at (:at card)] 0) 0)
                left (max 0 (min (- at (/ w 2)) (- (:w x) w)))]
            ^{:key (:id l)}
            [:div.f-flow-lane {:style {:min-height (+ (* lines 18) 52)}}
             (when (:label l) [:span.f-flow-lane-label (:label l)])
             (when (and card (pos? (:w x)))
               [:div.f-flow-card {:class [(when-not now "gone") (when (:dim card) "dim")]
                                  :style {:left left :width w "--c" (str "var(--" (or (:color card) "grey") ")")}}
                (when (:title card) [:div.hd (:title card)])
                (when (some? (:body card)) [:f> card-body (str (:body card)) (:lang card) (:new card)])])]))]
       (when (:caption f) [:p.f-flow-cap (:caption f)])])))
