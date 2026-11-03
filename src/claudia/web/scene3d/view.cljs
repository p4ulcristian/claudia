(ns claudia.web.scene3d.view
  "The scene3d form's view (its own module, loaded on first use, so pictures without a 3D scene
  cost nothing): the stage, the story bar (play, pause, steps, voice), the legend, subtitles, the
  part popup with Explain, the overlap check, the style and quality switches, reset view and the
  height grip."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.web.scene3d.engine :as engine]
            [claudia.web.scene3d.story :as story]))

(def ^:private icons
  {:play "M6.5 4.5v11l9-5.5z"
   :pause "M7 4.5v11M13 4.5v11"
   :prev "M5 4.5v11M15 4.5l-8 5.5 8 5.5z"
   :next "M15 4.5v11M5 4.5l8 5.5-8 5.5z"
   :volume "M3 8v4h3l4 3.5v-11L6 8zM13 7.5a3.5 3.5 0 0 1 0 5M15.2 5.3a6.5 6.5 0 0 1 0 9.4"
   :mute "M3 8v4h3l4 3.5v-11L6 8zM13.5 8l4 4M17.5 8l-4 4"
   :reset "M4.5 10a5.5 5.5 0 1 0 1.8-4.1M4.5 3.5v3.7h3.7"
   :warn "M10 3.5l7 12.2H3zM10 8.3v3.2M10 13.6v.4"
   :check "M4 10.5l4 4 8-9"
   :close "M5 5l10 10M15 5L5 15"})

(defn- icon [k] [:svg.s3-ic {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d (icons k)}]])

(def ^:private height-key "claudia.scene3d.height")
(def ^:private height-min 220)
(defn- clamp-height [h]
  (js/Math.round (min (max h height-min) (max (* 1.5 js/innerHeight) 600))))

(defn- stored [k] (js/localStorage.getItem k))

(defn- explain-text [{:keys [id title color shape parent note]} block]
  (str "Explain the \"" title (when (not= title id) (str " (id " id ")")) "\" in the 3D view"
       (when block (str " (block " block ")")) ": a " color " " shape
       (when parent (str ", part of " parent))
       (when note (str ", noted as \"" note "\""))
       ". What it is, what it does, how it works, and how it connects to the parts around it."))

(defn- story-bar [ui api]
  (let [{:keys [story idx prog peek playing voice-state muted]} @ui
        secs (mapv story/estimate-seconds story)
        total (max 1 (reduce + secs))
        n (count story)]
    [:span.s3-story
     [:button.f-tool {:disabled (< idx 1) :title "Previous step" :on-click #((-> @api :story :prev))} [icon :prev]]
     [:button.f-tool {:on-click #(if playing ((-> @api :story :pause)) ((-> @api :story :play)))}
      [icon (if playing :pause :play)]]
     [:button.f-tool {:disabled (>= idx (dec n)) :title "Next step" :on-click #((-> @api :story :next))} [icon :next]]
     [:span.s3-track {:on-mouse-leave #(swap! ui assoc :peek -1)}
      (when (>= peek 0)
        [:span.s3-tip {:style {:left (str (* 100 (/ (+ (reduce + (take peek secs)) (/ (secs peek) 2)) total)) "%")}}
         (str (inc peek) ". " (:say (story peek)))])
      (for [[i w] (map-indexed vector secs)]
        ^{:key i}
        [:button.s3-seg {:class (when (= i peek) "peek") :style {:flex-grow w}
                         :aria-label (str "Step " (inc i) " of " n)
                         :on-mouse-enter #(swap! ui assoc :peek i)
                         :on-click #((-> @api :story :goto) i)}
         [:i {:style {:width (str (cond (< i idx) 100 (= i idx) (* 100 prog) :else 0) "%")}}]])]
     (when (str/starts-with? (or voice-state "") "voice")
       [:button.f-tool {:title (if muted "Voice off: click to hear it" "Mute the voice")
                        :on-click #((-> @api :story :mute) (not muted))}
        [icon (if muted :mute :volume)]])
     (when (seq voice-state) [:span.f-note voice-state])]))

(defn- part-popup [ui api {:keys [chat block live?]} host]
  (let [{:keys [picked height]} @ui
        w (or (some-> ^js @host .-clientWidth) 400)]
    [:div.s3-pop {:style {:left (min (+ (:x picked) 12) (- w 250))
                          :top (max 4 (min (- (:y picked) 10) (- height 250)))}
                  :on-pointer-down #(.stopPropagation %)}
     [:div.s3-pop-head
      [:b (:title picked)]
      [:button.s3-x {:aria-label "Close" :on-click #((:select! @api) nil)} [icon :close]]]
     (when (:note picked) [:p.s3-pop-note (:note picked)])
     [:dl (for [[k v] (:rows picked)] ^{:key k} [:<> [:dt k] [:dd v]])]
     [:button.primary {:disabled (not (and live? chat))
                       :title (if (and live? chat) "Ask Claude to explain this part" "Open this chat to ask Claude")
                       :on-click (fn []
                                   (rf/dispatch [:scene3d/explain (explain-text picked block)])
                                   ((:select! @api) nil))}
      "Explain"]]))

(defn- grip [ui]
  [:div.s3-grip
   {:role "separator" :aria-orientation "horizontal" :title "Drag to resize the height"
    :on-pointer-down
    (fn [^js e]
      (let [y0 (.-clientY e) h0 (:height @ui) g (.-currentTarget e)]
        (.setPointerCapture g (.-pointerId e))
        (letfn [(move [^js m] (swap! ui assoc :height (clamp-height (+ h0 (- (.-clientY m) y0)))))
                (up [^js u]
                  (.removeEventListener g "pointermove" move)
                  (.removeEventListener g "pointerup" up)
                  (js/localStorage.setItem height-key (str (clamp-height (+ h0 (- (.-clientY u) y0))))))]
          (.addEventListener g "pointermove" move)
          (.addEventListener g "pointerup" up))))}])

(defn scene3d
  "props: {:data (the form's data) :chat :block :live? (the open chat, so Explain can ask)}."
  [_props]
  (let [host (atom nil)
        api (atom nil)
        ui (r/atom {:animated false :running true :speed 2 :error "" :solids 0 :now [] :seen []
                    :panel false :scanned false :picked nil :legend [] :hint ""
                    :height (clamp-height (let [n (js/Number (stored height-key))] (if (pos? n) n 440)))
                    :quality (if (= "low" (stored "claudia.scene3d.quality")) "low" "high")
                    :style (if (= "real" (stored "claudia.scene3d.style")) "real" "diagram")
                    :story-len 0 :story [] :idx -1 :prog 0 :peek -1 :playing false :sub ""
                    :voice-state "" :muted (not= "on" (stored engine/voice-key))})
        hint-timer (atom nil)]
    (add-watch ui ::hint (fn [_ _ o n]
                           (when (and (seq (:hint n)) (not= (:hint o) (:hint n)))
                             (js/clearTimeout @hint-timer)
                             (reset! hint-timer (js/setTimeout #(swap! ui assoc :hint "") 1500)))))
    (r/create-class
     {:display-name "scene3d"
      :component-did-mount (fn [this]
                             (reset! api (engine/start! @host ui (:data (r/props this)))))
      :component-did-update (fn [this [_ old]]
                              (let [d (:data (r/props this))]
                                (when (not= d (:data old)) ((:set! @api) d))))
      :component-will-unmount (fn [] (when-let [a @api] ((:destroy! a))) (reset! api nil))
      :reagent-render
      (fn [props]
        (let [{:keys [animated running speed error solids now seen panel scanned picked legend hint height
                      quality style story-len idx playing sub autoplay]} @ui]
          [:div.f-scene3d {:ref #(reset! host %)}
           [:div.s3-stage {:style {:height height}}]
           [grip ui]
           (when picked [part-popup ui api props host])
           (when (seq legend)
             [:div.s3-legend
              (for [{:keys [color label]} legend]
                ^{:key label}
                [:span [:i {:style {:background (if (re-matches #"[a-z]+" (str color)) (str "var(--" color ")") color)}}] label])])
           (when (and (pos? story-len) (neg? idx) (not playing) (false? autoplay))
             [:button.primary.s3-bigplay {:on-click #((-> @api :story :play))} [icon :play] " Play the story"])
           (when (and (seq sub) (>= idx 0)) [:div.s3-sub sub])
           (when (seq error) [:p.s3-error (str "script: " error)])
           (when (and panel (> solids 1))
             [:div.s3-panel
              [:b "Overlapping now"]
              (if (seq now) (for [x now] ^{:key x} [:div.bad x]) [:div.f-note "none"])
              [:b "Seen while it ran"]
              (if (seq seen) (for [x seen] ^{:key x} [:div x]) [:div.f-note "none"])
              (when animated [:button.f-tool {:on-click #((:scan! @api))} "scan the whole run"])])
           [:div.s3-bar
            [:span.s3-left
             (when (pos? story-len) [story-bar ui api])
             (when (and (> solids 1) (or (seq now) (seq seen)))
               [:button.f-tool.s3-check {:class (cond (seq now) "bad" (seq seen) "warn" :else "ok")
                                         :title "Solid parts that pass through each other"
                                         :on-click #(swap! ui update :panel not)}
                (if (seq now)
                  [:<> [icon :warn] (str " " (count now) " overlap" (when (> (count now) 1) "s"))]
                  [:<> [icon :check] (str " " (cond (seq seen) (str "clear now · " (count seen) " seen")
                                                    scanned "no overlaps in the run"
                                                    :else "no overlaps"))])])
             (when (seq hint) [:span.f-note hint])]
            [:span.s3-tools
             (when animated
               [:<>
                [:button.f-tool {:on-click #(do (swap! ui update :running not) ((:play! @api) (not running)))}
                 (if running [:<> [icon :pause] " pause"] [:<> [icon :play] " play"])]
                [:button.f-tool {:on-click #(let [n (mod (inc speed) (count engine/speeds))]
                                              (swap! ui assoc :speed n)
                                              ((:speed! @api) (engine/speeds n)))}
                 (str (engine/speeds speed) "×")]])
             [:button.f-tool {:title (if (= style "diagram")
                                       "Chalk diagram style. Click for the realistic look."
                                       "Realistic look. Click for the chalk diagram style.")
                              :on-click #(let [n (if (= style "diagram") "real" "diagram")]
                                           (js/localStorage.setItem "claudia.scene3d.style" n)
                                           (swap! ui assoc :style n)
                                           ((:style! @api) n))}
              (if (= style "diagram") "Diagram" "Realistic")]
             (when (= style "real")
               [:button.f-tool {:title (if (= quality "high")
                                         "Shadows and sharp pixels on. Click for lighter drawing."
                                         "Lighter drawing. Click for shadows and sharp pixels.")
                                :on-click #(let [q (if (= quality "high") "low" "high")]
                                             (js/localStorage.setItem "claudia.scene3d.quality" q)
                                             (swap! ui assoc :quality q)
                                             ((:quality! @api) q))}
                (if (= quality "high") "High" "Low")])
             [:button.f-tool {:on-click #((:fit! @api))} [icon :reset] " reset view"]]]]))})))
