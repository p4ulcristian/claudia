(ns claudia.web.scene3d.view
  "The scene3d form's view (its own module, loaded on first use, so pictures without a 3D scene
  cost nothing): the stage, the story bar (play, pause, steps, voice), the legend, subtitles, the
  part popup with Explain, the overlap check, the style and quality switches, reset view, full
  screen and the height grip."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.web.fullscreen :as fs]
            [claudia.web.scene3d.bar :as bar]
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
   :close "M5 5l10 10M15 5L5 15"
   :full "M3.5 8V3.5H8M12 3.5h4.5V8M16.5 12v4.5H12M8 16.5H3.5V12"
   :exit "M8 3.5V8H3.5M16.5 8H12V3.5M12 16.5V12h4.5M3.5 12H8v4.5"})

(defn- icon [k] [:svg.s3-ic {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d (icons k)}]])

(def ^:private height-key "claudia.scene3d.height")
(defn- clamp-height [h] (bar/clamp-height h js/innerHeight))

(defn- stored [k] (js/localStorage.getItem k))

(defn- story-api [api k] (-> @api :story k))

(defn- story-track [ui api secs]
  (let [{:keys [story idx prog] peeked :peek} @ui
        n (count story)]
    [:span.s3-track {:on-mouse-leave #(swap! ui assoc :peek -1)}
     (when (>= peeked 0)
       [:span.s3-tip {:style {:left (str (bar/tip-left secs peeked) "%")}}
        (str (inc peeked) ". " (:say (story peeked)))])
     (for [[i w] (map-indexed vector secs)]
       ^{:key i}
       [:button.s3-seg {:class (when (= i peeked) "peek") :style {:flex-grow w}
                        :aria-label (str "Step " (inc i) " of " n)
                        :on-mouse-enter #(swap! ui assoc :peek i)
                        :on-click #((story-api api :goto) i)}
        [:i {:style {:width (str (bar/seg-fill i idx prog) "%")}}]])]))

(defn- story-bar [ui api]
  (let [{:keys [story idx playing voice-state muted]} @ui
        n (count story)]
    [:span.s3-story
     [:button.f-tool {:disabled (< idx 1) :title "Previous step" :on-click #((story-api api :prev))} [icon :prev]]
     [:button.f-tool {:on-click #(if playing ((story-api api :pause)) ((story-api api :play)))}
      [icon (if playing :pause :play)]]
     [:button.f-tool {:disabled (>= idx (dec n)) :title "Next step" :on-click #((story-api api :next))} [icon :next]]
     [story-track ui api (mapv story/estimate-seconds story)]
     (when (str/starts-with? (or voice-state "") "voice")
       [:button.f-tool {:title (if muted "Voice off: click to hear it" "Mute the voice")
                        :on-click #((story-api api :mute) (not muted))}
        [icon (if muted :mute :volume)]])
     (when (seq voice-state) [:span.f-note voice-state])]))

(defn- part-popup [ui api {:keys [chat block live?]} host]
  (let [{:keys [picked height]} @ui
        w (or (some-> ^js @host .-clientWidth) 400)
        h (or (some-> ^js @host .-clientHeight) height)]
    [:div.s3-pop {:style (bar/popup-at picked w h)
                  :on-pointer-down #(.stopPropagation %)}
     [:div.s3-pop-head
      [:b (:title picked)]
      [:button.s3-x {:aria-label "Close" :on-click #((:select! @api) nil)} [icon :close]]]
     (when (:note picked) [:p.s3-pop-note (:note picked)])
     [:dl (for [[k v] (:rows picked)] ^{:key k} [:<> [:dt k] [:dd v]])]
     [:button.primary {:disabled (not (and live? chat))
                       :title (if (and live? chat) "Ask Claude to explain this part" "Open this chat to ask Claude")
                       :on-click (fn []
                                   (rf/dispatch [:scene3d/explain (bar/explain-text picked block)])
                                   ((:select! @api) nil))}
      "Explain"]]))

(defn- grip-drag!
  "Dragging the grip resizes the stage; the height is kept when the drag ends."
  [ui ^js e]
  (let [y0 (.-clientY e) h0 (:height @ui) g (.-currentTarget e)]
    (.setPointerCapture g (.-pointerId e))
    (letfn [(move [^js mv] (swap! ui assoc :height (clamp-height (+ h0 (- (.-clientY mv) y0)))))
            (up [^js u]
              (.removeEventListener g "pointermove" move)
              (.removeEventListener g "pointerup" up)
              (js/localStorage.setItem height-key (str (clamp-height (+ h0 (- (.-clientY u) y0))))))]
      (.addEventListener g "pointermove" move)
      (.addEventListener g "pointerup" up))))

(defn- grip [ui]
  [:div.s3-grip
   {:role "separator" :aria-orientation "horizontal" :title "Drag to resize the height"
    :on-pointer-down #(grip-drag! ui %)}])

(defn- legend-view [legend]
  [:div.s3-legend
   (for [{:keys [color label]} legend]
     ^{:key label}
     [:span [:i {:style {:background (bar/swatch color)}}] label])])

(defn- overlap-panel [{:keys [now seen animated]} api]
  [:div.s3-panel
   [:b "Overlapping now"]
   (if (seq now) (for [x now] ^{:key x} [:div.bad x]) [:div.f-note "none"])
   [:b "Seen while it ran"]
   (if (seq seen) (for [x seen] ^{:key x} [:div x]) [:div.f-note "none"])
   (when animated [:button.f-tool {:on-click #((:scan! @api))} "scan the whole run"])])

(defn- check-button [ui {:keys [now seen scanned]}]
  [:button.f-tool.s3-check {:class (bar/check-class now seen)
                            :title "Solid parts that pass through each other"
                            :on-click #(swap! ui update :panel not)}
   [:<> [icon (if (seq now) :warn :check)] (bar/check-text now seen scanned)]])

(defn- left-bar [ui api {:keys [story-len solids now seen hint] :as state}]
  [:span.s3-left
   (when (pos? story-len) [story-bar ui api])
   (when (and (> solids 1) (or (seq now) (seq seen))) [check-button ui state])
   (when (seq hint) [:span.f-note hint])])

(defn- motion-tools [ui api {:keys [running speed]}]
  [:<>
   [:button.f-tool {:on-click #(do (swap! ui update :running not) ((:play! @api) (not running)))}
    (if running [:<> [icon :pause] " pause"] [:<> [icon :play] " play"])]
   [:button.f-tool {:on-click #(let [n (mod (inc speed) (count engine/speeds))]
                                 (swap! ui assoc :speed n)
                                 ((:speed! @api) (engine/speeds n)))}
    (str (engine/speeds speed) "×")]])

(defn- style-button [ui api style]
  [:button.f-tool {:title (if (= style "diagram")
                            "Chalk diagram style. Click for the realistic look."
                            "Realistic look. Click for the chalk diagram style.")
                   :on-click #(let [n (if (= style "diagram") "real" "diagram")]
                                (js/localStorage.setItem "claudia.scene3d.style" n)
                                (swap! ui assoc :style n)
                                ((:style! @api) n))}
   (if (= style "diagram") "Diagram" "Realistic")])

(defn- quality-button [ui api quality]
  [:button.f-tool {:title (if (= quality "high")
                            "Shadows and sharp pixels on. Click for lighter drawing."
                            "Lighter drawing. Click for shadows and sharp pixels.")
                   :on-click #(let [q (if (= quality "high") "low" "high")]
                                (js/localStorage.setItem "claudia.scene3d.quality" q)
                                (swap! ui assoc :quality q)
                                ((:quality! @api) q))}
   (if (= quality "high") "High" "Low")])

(defn- full-button [host full]
  [:button.f-tool {:title (if full "Leave full screen (Esc)" "Full screen")
                   :on-click #(fs/toggle! @host)}
   (if full [:<> [icon :exit] " exit"] [:<> [icon :full] " full screen"])])

(defn- tools [ui api host {:keys [animated style quality full] :as state}]
  [:span.s3-tools
   (when animated [motion-tools ui api state])
   [style-button ui api style]
   (when (= style "real") [quality-button ui api quality])
   [:button.f-tool {:on-click #((:fit! @api))} [icon :reset] " reset view"]
   [full-button host full]])

(defn- scene-view [ui api host props]
  (let [{:keys [error solids panel picked legend height story-len idx playing sub autoplay full] :as state} @ui]
    [:div.f-scene3d {:ref #(reset! host %)}
     [:div.s3-stage {:style {:height (if full "100%" height)}}]
     (when-not full [grip ui])
     (when picked [part-popup ui api props host])
     (when (seq legend) [legend-view legend])
     (when (and (pos? story-len) (neg? idx) (not playing) (false? autoplay))
       [:button.primary.s3-bigplay {:on-click #((story-api api :play))} [icon :play] " Play the story"])
     (when (and (seq sub) (>= idx 0)) [:div.s3-sub sub])
     (when (seq error) [:p.s3-error (str "script: " error)])
     (when (and panel (> solids 1)) [overlap-panel state api])
     [:div.s3-bar
      [left-bar ui api state]
      [tools ui api host state]]]))

(defn- initial-ui []
  {:animated false :running true :speed 2 :error "" :solids 0 :now [] :seen []
   :panel false :scanned false :picked nil :legend [] :hint "" :full false
   :height (bar/stored-height (stored height-key) js/innerHeight)
   :quality (if (= "low" (stored "claudia.scene3d.quality")) "low" "high")
   :style (if (= "real" (stored "claudia.scene3d.style")) "real" "diagram")
   :story-len 0 :story [] :idx -1 :prog 0 :peek -1 :playing false :sub ""
   :voice-state "" :muted (not= "on" (stored engine/voice-key))})

(defn- watch-hint!
  "A hint shows for 1.5 s."
  [ui]
  (let [timer (atom nil)]
    (add-watch ui ::hint (fn [_ _ o n]
                           (when (and (seq (:hint n)) (not= (:hint o) (:hint n)))
                             (js/clearTimeout @timer)
                             (reset! timer (js/setTimeout #(swap! ui assoc :hint "") 1500)))))))

(defn scene3d
  "props: {:data (the form's data) :chat :block :live? (the open chat, so Explain can ask)}."
  [_props]
  (let [host (atom nil)
        api (atom nil)
        unlisten (atom nil)
        ui (r/atom (initial-ui))]
    (watch-hint! ui)
    (r/create-class
     {:display-name "scene3d"
      :component-did-mount (fn [this]
                             (reset! unlisten (fs/on-change! #(swap! ui assoc :full (fs/full? @host))))
                             (reset! api (engine/start! @host ui (:data (r/props this)))))
      :component-did-update (fn [this [_ old]]
                              (let [d (:data (r/props this))]
                                (when (not= d (:data old)) ((:set! @api) d))))
      :component-will-unmount (fn []
                                (when-let [stop @unlisten] (stop))
                                (when-let [a @api] ((:destroy! a)))
                                (reset! api nil))
      :reagent-render (fn [props] [scene-view ui api host props])})))
