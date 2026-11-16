(ns claudia.web.views.mic
  "The composer's mic: a 56 px round button at the composer's left end, under the left thumb of a
  tablet held in landscape. Hold it and talk: a flower opens round it (views/mic_flower.cljs), big
  enough to show past the finger: amber while the microphone wakes, red once it records, with the
  time spoken. Let go in place (or dragged left or down) and the clip is heard and sent; dragged
  right, the words only join the draft; dragged up, the clip is thrown away. Each clip let go waits
  in a list over the composer with its own hearing clock, and the words go out in the order they
  were spoken. The button can be held again meanwhile. A tap only says to hold it. Shown only
  where the browser can record and the hub has a listen service."
  (:require [claudia.web.mic :as mic]
            [claudia.web.speech :as speech]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.mic-flower :as flower]
            [claudia.web.views.pure.mic :as pure]
            [claudia.web.views.pure.update-pill :as clock]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]))

(defn- flower-state
  "What the flower shows now: the phase, how loud (eased), seconds spoken, the way letting go goes."
  [phase ^js live hold eased]
  (let [{:keys [dir rec-at]} (.-current hold)]
    {:phase phase :level eased :way (or dir :send)
     :secs (if rec-at (/ (- (js/performance.now) rec-at) 1000) 0)
     :live live}))

(defn- use-flower!
  "Runs the flower while the mic is held (waking or recording); clears it after."
  [canvas live hold phase]
  (h/use-effect
   (fn []
     (let [raf (atom nil) eased (atom 0)
           step (fn step []
                  (when-let [c (.-current canvas)]
                    (when-let [s (.-current live)] (swap! eased #(+ (* 0.7 %) (* 0.3 (flower/level (mic/spectrum s))))))
                    (flower/draw! c (flower-state phase (.-current live) hold @eased)))
                  (reset! raf (js/requestAnimationFrame step)))]
       (when (#{:starting :recording} phase) (step))
       (fn []
         (some-> @raf js/cancelAnimationFrame)
         (some-> (.-current canvas) flower/wipe!))))
   [phase]))

;; The clips let go, in the order they were spoken: each gets its place (:issued) when it is let go,
;; and its words go out only once every earlier clip's have (a long clip is heard more slowly than a
;; short one after it). {:issued n :next n :held {n [result]}}
(defonce ^:private line (atom {:issued 0 :next 0 :held {}}))

;; The clips being heard, for the list over the composer: n -> {:n :secs :dir :t0 :heard}.
(defonce ^:private clips (r/atom {}))

(defn- place!
  "Clip n's place in line, and its row in the list (secs: how long it was held)."
  [secs dir]
  (let [n (dec (:issued (swap! line update :issued inc)))]
    (swap! clips assoc n {:n n :secs secs :dir dir :t0 (js/Date.now) :heard false})
    n))

(defn- arrive!
  "Clip n's outcome ({:text :dir :on-heard}, nil when nothing was heard): what is now in order goes
  out, and its rows leave the list."
  [n result]
  (let [out (atom nil)]
    (swap! line (fn [l] (let [[l' ready] (pure/in-order l n result)] (reset! out ready) l')))
    (swap! clips (fn [cs] (into {} (remove (fn [[k]] (< k (:next @line)))) (assoc-in cs [n :heard] true))))
    (doseq [{:keys [text dir on-heard]} @out] (on-heard text dir))))

(defn- heard!
  "Clip n heard by the hub, its words given out in turn."
  [n on-heard dir clip]
  (-> (mic/hear clip)
      (.then (fn [text] (mic/report! {:event "heard" :bytes (.-size clip) :chars (count text)})
               (arrive! n {:text text :dir dir :on-heard on-heard})))
      (.catch (fn [e] (mic/report! {:event "hear-failed" :bytes (.-size clip) :error (.-message e)})
                (arrive! n nil)
                (rf/dispatch [:mic/failed (.-message e)])))))

(defn- watch-start!
  "Reports a press whose microphone is not recording 5 s later (the button still amber)."
  [hold p]
  (let [done (atom false)]
    (.finally p #(reset! done true))
    (js/setTimeout #(when (and (not @done) (.-current hold))
                      (mic/report! {:event "slow-start" :steps (mic/steps-now)}))
                   5000)))

(defn- started! [t0 warm s]
  (mic/report! {:event "started" :ms (js/Math.round (- (js/performance.now) t0)) :warm warm :steps (when-not warm (mic/steps-now))})
  s)

(defn- start-failed! [set-phase ^js err]
  (mic/report! {:event "start-failed" :error (.-message err) :steps (mic/steps-now)})
  (rf/dispatch [:mic/failed (.-message err)])
  (set-phase :idle))

(defn- press!
  "Pointer down: the clip starts (the microphone opens if it is cold; the first time the browser
  asks for it)."
  [^js e {:keys [hold live set-phase set-dir on-heard]}]
  (when (and (zero? (.-button e)) (nil? (.-current hold)))
    (.preventDefault e)
    (.setPointerCapture (.-currentTarget e) (.-pointerId e))
    (speech/stop!)   ; it never talks over you
    (let [t0 (js/performance.now) warm (mic/warm?)
          p (.then (mic/start!) #(started! t0 warm %))]
      ;; on-heard as it is at the press: the composer the person spoke into, even when another chat
      ;; is open by the time the words are heard.
      (set! (.-current hold) {:t0 (js/performance.now) :x0 (.-clientX e) :y0 (.-clientY e) :dir :send :p p :on-heard on-heard})
      (set-dir :send)
      (set-phase :starting)
      (watch-start! hold p)
      (-> p
          (.then (fn [s]
                   (set! (.-current live) s)
                   (when (.-current hold)
                     (set! (.-current hold) (assoc (.-current hold) :rec-at (js/performance.now)))
                     (set-phase :recording))))
          (.catch #(start-failed! set-phase %))))))

(defn- drag!
  "Pointer move while held: where the finger went chooses what letting go does (pure/gesture)."
  [^js e {:keys [hold set-dir]}]
  (when-let [{:keys [x0 y0]} (.-current hold)]
    (let [dir (pure/gesture (- (.-clientX e) x0) (- (.-clientY e) y0))]
      (set! (.-current hold) (assoc (.-current hold) :dir dir))
      (set-dir dir))))

(defn- release!
  "Pointer up: a hold long enough is heard and sent (dragged right: added to the message; dragged
  up: thrown away); a tap is dropped and says to hold."
  [{:keys [hold live set-phase]}]
  (when-let [{:keys [t0 p dir on-heard rec-at]} (.-current hold)]
    (set! (.-current hold) nil)
    (let [tap? (< (- (js/performance.now) t0) pure/min-hold-ms)
          secs (js/Math.round (/ (- (js/performance.now) (or rec-at t0)) 1000))]
      (.then p (fn [s]
                 (set! (.-current live) nil)
                 (cond
                   tap? (do (mic/discard! s) (set-phase :tap))
                   (= dir :cancel) (do (mic/discard! s) (set-phase :idle))
                   :else (let [n (place! secs dir)]
                           (set-phase :idle)
                           (.then (mic/stop! s) #(heard! n on-heard dir %)))))))))

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

(defn- clip-row [{:keys [n secs dir t0 heard]} now]
  [:li.mic-clip {:class (when heard "heard")}
   [:span (str (inc n) " \u00b7 " secs " s \u2192 " (if (= dir :add) "add" "send"))]
   [:b (if heard "heard, waits its turn" (str "hearing " (clock/clock-text (- now t0))))]])

(defn- clip-list-view []
  (let [now (hooks/use-now) cs (vals @clips)]
    (when (seq cs)
      [:ol.mic-clips {:aria-live "polite"}
       (for [c (sort-by :n cs)] ^{:key (:n c)} [clip-row c now])])))

(defn- clip-list
  "Over the composer: each clip being heard, in spoken order, with how long it has been loading."
  []
  [:f> clip-list-view])

(defn- core
  "The button with its bars, and the hint after a tap."
  [{:keys [phase canvas] :as parts}]
  [:div.mic-core
   [:canvas.mic-flower {:ref #(set! (.-current canvas) %) :aria-hidden true
                        :width (* flower/size (or js/devicePixelRatio 1)) :height (* flower/size (or js/devicePixelRatio 1))}]
   [:button.mic-button {:type "button" :aria-label "Hold to talk; let go to send, drag right to add to the message, drag up to cancel"
                        :data-tip "Hold to talk: let go sends, drag right adds to the message, drag up cancels"
                        :on-pointer-down #(press! % parts)
                        :on-pointer-move #(drag! % parts)
                        :on-pointer-up #(release! parts)
                        :on-pointer-cancel #(release! parts)
                        :on-context-menu #(.preventDefault %)}
    icon]
   (when (= phase :tap) [:span.mic-hint "Hold to talk"])])

(defn- view [{:keys [phase] :as parts}]
  [:div.mic {:class (name phase)}
   [clip-list]
   (core parts)])

(defn button
  "The mic, or nothing where the mic cannot work. on-heard gets the heard text and :send or :add."
  [on-heard]
  (let [[ok set-ok] (h/use-state false)
        [phase set-phase] (h/use-state :idle)
        [dir set-dir] (h/use-state :send)
        hold (h/use-ref nil)
        live (h/use-ref nil)
        canvas (h/use-ref nil)]
    (h/use-effect (fn [] (.then (mic/available?) set-ok) nil) [])
    (use-flower! canvas live hold phase)
    (use-tap-hint! phase set-phase)
    (when ok
      (view {:phase phase :dir dir :canvas canvas :hold hold :live live
             :set-phase set-phase :set-dir set-dir :on-heard on-heard}))))
