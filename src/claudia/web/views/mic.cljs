(ns claudia.web.views.mic
  "The composer's mic: a 56 px round button at the composer's left end, under the left thumb of a
  tablet held in landscape. Hold it and talk: bars round it follow the voice; let go and the hub
  hears the clip, and the text joins the draft (nothing is sent: Send stays yours). A tap only says
  to hold it. Shown only where the browser can record and the hub has a listen service."
  (:require [claudia.web.mic :as mic]
            [claudia.web.views.pure.mic :as pure]
            [re-frame.core :as rf]
            [reagent.hooks :as h]))

(def ^:private size "The canvas round the button, CSS px." 160)
(def ^:private inner "Where the bars start: just outside the 56 px button." 32)

(defn- color [^js el v] (.trim (.getPropertyValue (js/getComputedStyle el) v)))

(defn- draw-bars! [^js g levels]
  (doseq [[i level] (map-indexed vector levels)
          :when (> level 0.02)]
    (.save g)
    (.rotate g (* 2 js/Math.PI (/ i pure/bar-count)))
    (.fillRect g inner -1.5 (+ 3 (* level 34)) 3)
    (.restore g)))

(defn- draw-wait! [^js g t]
  (.beginPath g)
  (.arc g 0 0 (+ inner 4) (* t 5) (+ (* t 5) 1.6))
  (set! (.-lineWidth g) 3)
  (.stroke g))

(defn- wipe! [^js canvas]
  (let [g (.getContext canvas "2d")]
    (.setTransform g 1 0 0 1 0 0)
    (.clearRect g 0 0 (.-width canvas) (.-height canvas))))

(defn- frame!
  "One frame on the canvas: the voice's bars while recording, a turning arc while being heard."
  [^js canvas phase levels]
  (let [g (.getContext canvas "2d") dpr (or js/devicePixelRatio 1) ink (color canvas "--red")]
    (wipe! canvas)
    (.setTransform g dpr 0 0 dpr 0 0)
    (.translate g (/ size 2) (/ size 2))
    (set! (.-fillStyle g) ink)
    (set! (.-strokeStyle g) (color canvas "--accent"))
    (if (= phase :hearing)
      (draw-wait! g (/ (js/performance.now) 1000))
      (draw-bars! g levels))))

(defn- use-bars!
  "Runs the canvas while recording or being heard; clears it after."
  [canvas live phase]
  (h/use-effect
   (fn []
     (let [raf (atom nil) levels (atom [])
           step (fn step []
                  (when-let [c (.-current canvas)]
                    (when-let [s (.-current live)] (swap! levels pure/eased (pure/bar-levels (mic/spectrum s))))
                    (frame! c phase @levels))
                  (reset! raf (js/requestAnimationFrame step)))]
       (when (#{:recording :hearing} phase) (step))
       (fn []
         (some-> @raf js/cancelAnimationFrame)
         (some-> (.-current canvas) wipe!))))
   [phase]))

(defn- heard! [draft-key set-phase clip]
  (set-phase :hearing)
  (-> (mic/hear clip)
      (.then #(rf/dispatch [:draft/heard draft-key %]))
      (.catch #(rf/dispatch [:mic/failed (.-message %)]))
      (.finally #(set-phase :idle))))

(defn- press!
  "Pointer down: the microphone goes on (the first time the browser asks for it)."
  [^js e {:keys [hold live set-phase]}]
  (when (and (zero? (.-button e)) (nil? (.-current hold)))
    (.preventDefault e)
    (.setPointerCapture (.-currentTarget e) (.-pointerId e))
    (let [p (mic/start!)]
      (set! (.-current hold) {:t0 (js/performance.now) :p p})
      (set-phase :recording)
      (-> p
          (.then #(set! (.-current live) %))
          (.catch (fn [err] (rf/dispatch [:mic/failed (.-message err)]) (set-phase :idle)))))))

(defn- release!
  "Pointer up: a hold long enough is heard; a tap is dropped and says to hold."
  [{:keys [hold live set-phase draft-key]}]
  (when-let [{:keys [t0 p]} (.-current hold)]
    (set! (.-current hold) nil)
    (let [tap? (< (- (js/performance.now) t0) pure/min-hold-ms)]
      (.then p (fn [s]
                 (set! (.-current live) nil)
                 (if tap?
                   (do (mic/discard! s) (set-phase :tap))
                   (.then (mic/stop! s) #(heard! draft-key set-phase %))))))))

(def ^:private icon
  [:svg {:viewBox "0 0 24 24" :aria-hidden true :fill "none" :stroke "currentColor" :stroke-width 2 :stroke-linecap "round"}
   [:rect {:x 9 :y 3 :width 6 :height 11 :rx 3}]
   [:path {:d "M5 11a7 7 0 0 0 14 0M12 18v3"}]])

(defn- use-tap-hint! [phase set-phase]
  (h/use-effect
   (fn []
     (when (= phase :tap)
       (let [t (js/setTimeout #(set-phase :idle) 1600)] #(js/clearTimeout t))))
   [phase]))

(defn- view [{:keys [phase canvas] :as parts}]
  [:div.mic {:class (name phase)}
   [:canvas.mic-bars {:ref #(set! (.-current canvas) %) :aria-hidden true
                      :width (* size (or js/devicePixelRatio 1)) :height (* size (or js/devicePixelRatio 1))}]
   [:button.mic-button {:type "button" :aria-label "Hold to talk" :data-tip "Hold to talk"
                        :disabled (= phase :hearing)
                        :on-pointer-down #(press! % parts)
                        :on-pointer-up #(release! parts)
                        :on-pointer-cancel #(release! parts)
                        :on-context-menu #(.preventDefault %)}
    icon]
   (when (= phase :tap) [:span.mic-hint "Hold to talk"])])

(defn button
  "The mic for the draft draft-key, or nothing where it cannot work."
  [draft-key]
  (let [[ok set-ok] (h/use-state false)
        [phase set-phase] (h/use-state :idle)
        hold (h/use-ref nil)
        live (h/use-ref nil)
        canvas (h/use-ref nil)]
    (h/use-effect (fn [] (.then (mic/available?) set-ok) nil) [])
    (use-bars! canvas live phase)
    (use-tap-hint! phase set-phase)
    (when ok
      (view {:phase phase :canvas canvas :hold hold :live live :set-phase set-phase :draft-key draft-key}))))
