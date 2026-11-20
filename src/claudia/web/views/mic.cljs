(ns claudia.web.views.mic
  "The composer's mic: a 56 px round button at the composer's left end, under the left thumb of a
  tablet held in landscape. Hold it and talk: a flower opens round it (views/mic_flower.cljs), big
  enough to show past the finger: amber while the microphone wakes, red once it records, with the
  time spoken and bars round it that follow the voice. Let go in place or dragged up and the clip is
  heard and sent; dragged down, it is sent now (the running turn stops first); dragged left, the
  words only join the draft; dragged right, the clip is thrown away. Each clip let go waits
  in a list over the composer with its own hearing clock, and the words go out in the order they
  were spoken. The button can be held again meanwhile. A tap only says to hold it. Shown only
  where the browser can record and the hub has a listen service.
  On the tablet's rail (README decision 73) the flower is half, to the left, and the mic moves: a
  ring round the petals says move, and the finger reaching it (pure/in-ring?) at any time while
  holding keeps the recording going while the mic follows the finger on a spring (motion's useSpring: it trails
  and overshoots a little), its button kept on the screen; let go, the clip is heard and sent as
  when let go in place, and the mic glides there and stays (its spot kept on this device), or,
  covering half its slot or more (pure/home?), it glides home. Out
  of the rail its flower is whole, four ways.
  While a chat's reply is read aloud (README decision 21) the mic is that chat: its colour, its
  name and voice, bars that move. A tap then stops that one reply (the next one comes); a hold
  stops it and records as always. Nothing is read while the mic is held."
  (:require ["motion/react" :refer [motion useMotionValue useSpring]]
            ["react-dom" :as react-dom]
            [claudia.shared.voices :as voices]
            [claudia.web.mic :as mic]
            [claudia.web.speech :as speech]
            [claudia.web.views.mic-clips :as clips]
            [claudia.web.views.mic-flower :as flower]
            [claudia.web.views.pure.mic :as pure]
            [re-frame.core :as rf]
            [claudia.web.views.pure.words :as words]
            [reagent.core :as r]
            [reagent.hooks :as h]))

(defn- flower-state
  "What the flower shows now: the phase, the bars' levels (eased), seconds spoken, the way letting
  go goes."
  [phase ^js live hold levels]
  (let [{:keys [dir rec-at side rail?]} (.-current hold)]
    {:phase phase :levels levels :way (or dir :send) :side side :movable rail?
     :secs (if rec-at (/ (- (js/performance.now) rec-at) 1000) 0)
     :live live}))

(defn- use-flower!
  "Runs the flower while the mic is held (waking or recording); clears it after."
  [canvas live hold phase]
  (h/use-effect
   (fn []
     (let [raf (atom nil) eased (atom [])
           step (fn step []
                  (when-let [c (.-current canvas)]
                    (when-let [s (.-current live)] (swap! eased pure/eased (pure/bar-levels (mic/spectrum s) (:rate s))))
                    (flower/draw! c (flower-state phase (.-current live) hold @eased)))
                  (reset! raf (js/requestAnimationFrame step)))]
       (when (#{:starting :recording :moving} phase) (step))
       (fn []
         (some-> @raf js/cancelAnimationFrame)
         (some-> (.-current canvas) flower/wipe!))))
   [phase]))

(defn- watch-start!
  "Reports a press whose microphone is not recording 5 s later (the button still amber)."
  [hold p]
  (let [done (atom false)]
    (.finally p #(reset! done true))
    (js/setTimeout #(when (and (not @done) (.-current hold))
                      (mic/report! {:event "slow-start" :steps (mic/steps-now)}))
                   5000)))

(defn- turns
  "Every word on the screen's turn: the app's (session s), window.orientation, screen.orientation."
  [s]
  {:native (:turn s) :window (.-orientation js/window) :screen (some-> js/screen .-orientation .-angle)})

(defn- screen-angle [s]
  (let [{:keys [native window screen]} (turns s)] (pure/turn-angle native window screen)))

(defn- started! [t0 warm s]
  (mic/report! {:event "started" :ms (js/Math.round (- (js/performance.now) t0)) :warm warm :steps (when-not warm (mic/steps-now))
                :mic (:mic s) :angle (screen-angle s) :turns (turns s)
                :edge (some-> (pure/speak-edge (:mic s) (screen-angle s)) name)})
  s)

(defn- start-failed! [set-phase ^js err]
  (mic/report! {:event "start-failed" :error (.-message err) :steps (mic/steps-now)})
  (rf/dispatch [:mic/failed (.-message err)])
  (set-phase :idle))

(def ^:private spot-key "Where a moved mic sits on this device (localStorage)." "claudia.mic-spot")

(defn- rail-width []
  (or (some-> (js/document.querySelector ".thumb-edge") .-offsetWidth) 88))

(defn- button-r
  "The rail mic's button's radius, px."
  []
  (/ (or (some-> (js/document.querySelector ".thumb-edge .mic-button") .-offsetWidth) 64) 2))

(defn- kept-spot
  "The mic's spot on this device, clamped so its button is on the screen now, or nil (in the rail)."
  []
  (let [kept (try (js->clj (js/JSON.parse (.getItem js/localStorage spot-key)) :keywordize-keys true)
                  (catch :default _ nil))]
    (when (map? kept)
      (pure/stored->spot kept js/innerWidth js/innerHeight (button-r)))))

(defn- keep-spot! [spot]
  (if spot
    (.setItem js/localStorage spot-key (js/JSON.stringify (clj->js (pure/spot->stored spot js/innerWidth js/innerHeight))))
    (.removeItem js/localStorage spot-key)))

(defn- circle
  "An element as a circle on the screen: its centre {:x :y} and radius :r (half its width)."
  [^js el]
  (when el
    (let [r (.getBoundingClientRect el)]
      {:x (+ (.-left r) (/ (.-width r) 2)) :y (+ (.-top r) (/ (.-height r) 2)) :r (/ (.-width r) 2)})))

(defn- slot-circle
  "Where the mic sits in the rail: its slot when it has moved out, else the button itself."
  [^js button spot]
  (if spot (circle (js/document.querySelector ".mic-slot")) (circle button)))

(defn- side-at
  "The flower's side for a rail mic whose button's centre is at {:x}: in the rail :left (half), out
  of it nil (whole; pure/flower-side)."
  [rail? {:keys [x]}]
  (when rail? (pure/flower-side x js/innerWidth (rail-width))))

(defn- press!
  "Pointer down: the clip starts (the microphone opens if it is cold; the first time the browser
  asks for it). On the rail the hold knows its slot and which flower opens."
  [^js e {:keys [hold live set-phase set-dir set-mic draft-key rail? spot]}]
  (when (and (zero? (.-button e)) (nil? (.-current hold)))
    (.preventDefault e)
    (.setPointerCapture (.-currentTarget e) (.-pointerId e))
    (let [reading (speech/reading?)
          t0 (js/performance.now) warm (mic/warm?)
          p (.then (mic/start!) #(started! t0 warm %))]
      (speech/hold!)   ; it never talks over you
      ;; The composer as it is at the press: the one the person spoke into, even when another chat
      ;; is open by the time the words are heard.
      (set! (.-current hold) {:t0 (js/performance.now) :x0 (.-clientX e) :y0 (.-clientY e) :dir :send :p p :k draft-key
                              :reading reading
                              :rail? rail? :side (side-at rail? (circle (.-currentTarget e)))
                              :slot (when rail? (slot-circle (.-currentTarget e) spot)) :from (circle (.-currentTarget e))})
      (set-dir :send)
      (set-phase :starting)
      (watch-start! hold p)
      (-> p
          (.then (fn [s]
                   (set! (.-current live) s)
                   (set-mic s)
                   (when (.-current hold)
                     (set! (.-current hold) (assoc (.-current hold) :rec-at (js/performance.now)))
                     (when-not (:moving (.-current hold)) (set-phase :recording)))))
          (.catch #(start-failed! set-phase %))))))

(defn- jump!
  "The mic's spring put at {:x :y} at once, at rest."
  [{:keys [^js mx ^js my ^js sx ^js sy]} {:keys [x y]}]
  (.jump mx x) (.jump my y) (.jump sx x) (.jump sy y))

(defn- start-move!
  "The finger reached the move ring: the recording goes on and the mic, out of its place, starts
  following the finger from where it sat."
  [{:keys [hold set-phase set-free] :as parts}]
  (let [{:keys [from]} (.-current hold)]
    (set! (.-current hold) (assoc (.-current hold) :moving true :dir :send))
    (jump! parts from)
    (set-free true)
    (set-phase :moving)))

(defn- on-screen
  "Where a moved mic's button goes for a finger at x y: there, kept wholly on the screen."
  [x y r]
  (pure/clamp-spot {:x x :y y} js/innerWidth js/innerHeight r))

(defn- follow!
  "The mic, moving, pulled towards the finger (its button kept on the screen); its flower whole out
  of the rail, half in it."
  [^js e {:keys [hold ^js mx ^js my]}]
  (let [{:keys [rail? from]} (.-current hold)
        {:keys [x y] :as at} (on-screen (.-clientX e) (.-clientY e) (:r from))]
    (set! (.-current hold) (assoc (.-current hold) :side (side-at rail? at)))
    (.set mx x) (.set my y)))

(declare park!)

(defn- settle!
  "The finger rested while moving: the mic is put down under it, still recording, and the hold
  chooses again from there: the petals round it, letting go in place sends, the ring moves on."
  [{:keys [hold live set-phase set-dir] :as parts}]
  (when-let [{:keys [rested rail?] :as held} (.-current hold)]
    (when (:moving held)
      (let [to (park! (:x rested) (:y rested) parts held)]
        (set! (.-current hold) (assoc held :moving false :timer nil :x0 (:x rested) :y0 (:y rested)
                                      :from to :dir :send :side (side-at rail? to)))
        (set-dir :send)
        (set-phase (if (.-current live) :recording :starting))))))

(defn- watch-rest!
  "While moving: each time the finger leaves where it rested, the rest clock starts again; when it
  runs out (pure/settle-ms), the mic settles."
  [^js e {:keys [hold] :as parts}]
  (let [x (.-clientX e) y (.-clientY e) {:keys [rested timer]} (.-current hold)]
    (when-not (and rested (pure/still? rested x y))
      (some-> timer js/clearTimeout)
      (set! (.-current hold) (assoc (.-current hold) :rested {:x x :y y}
                                    :timer (js/setTimeout #(settle! parts) pure/settle-ms))))))

(defn- drag!
  "Pointer move while held: where the finger went chooses what letting go does (pure/gesture, on
  the rail's half flower pure/half-gesture); on the rail, the finger in the move ring moves the mic."
  [^js e {:keys [hold set-dir] :as parts}]
  (when-let [{:keys [x0 y0 side rail? moving from]} (.-current hold)]
    (when (and rail? (not moving) (pure/in-ring? (- (.-clientX e) (:x from)) (- (.-clientY e) (:y from))))
      (start-move! parts))
    (if (:moving (.-current hold))
      (do (follow! e parts) (watch-rest! e parts))
      (let [dx (- (.-clientX e) x0) dy (- (.-clientY e) y0)
            dir (if side (pure/half-gesture dx dy) (pure/gesture dx dy))]
        (set! (.-current hold) (assoc (.-current hold) :dir dir))
        (set-dir dir)))))

(defn- when-still!
  "Calls (f) once the mic's spring has come to rest."
  [^js sx ^js sy f]
  (js/requestAnimationFrame
   #(if (or (.isAnimating sx) (.isAnimating sy)) (when-still! sx sy f) (f))))

(defn- park!
  "The mic put down for a finger at x y: it glides there (its button on the screen) and stays (kept
  on this device), or, covering half its slot or more, glides home into it. -> where it goes
  {:x :y :r}."
  [x y {:keys [set-spot set-free ^js mx ^js my ^js sx ^js sy]} {:keys [slot from]}]
  (let [at (on-screen x y (:r from))
        spot (when-not (pure/home? (assoc at :r (:r from)) slot) at)
        to (or spot slot)]
    (keep-spot! spot)
    (set-spot spot)
    (.set mx (:x to)) (.set my (:y to))
    (when-not spot (when-still! sx sy #(set-free false)))
    (assoc to :r (:r from))))

(defn- moved!
  "Let go while moving: the mic is put down where the finger is (park!)."
  [^js e parts held]
  (park! (.-clientX e) (.-clientY e) parts held))

(defn- heard!
  "A hold let go in place or on a petal: a tap says to hold (while a reply was read, it only stopped
  that reply), cancel drops the clip, else it is heard."
  [p {:keys [t0 dir k rec-at reading]} live set-phase]
  (let [tap? (< (- (js/performance.now) t0) pure/min-hold-ms)
        secs (js/Math.round (/ (- (js/performance.now) (or rec-at t0)) 1000))]
    (.then p (fn [s]
               (set! (.-current live) nil)
               (cond
                 tap? (do (mic/discard! s) (set-phase (if reading :idle :tap)))
                 (= dir :cancel) (do (mic/discard! s) (set-phase :idle))
                 :else (do (set-phase :idle)
                           (clips/spoken! k dir secs (mic/stop! s))))))))

(defn- release!
  "Pointer up: a hold long enough is heard and sent (dragged down: now; dragged left: added to the
  message; dragged right: thrown away); a tap is dropped and says to hold; a move ends where the
  finger is, and its clip is heard and sent as when let go in place."
  [^js e {:keys [hold live set-phase] :as parts}]
  (when-let [{:keys [t0 p dir k rec-at moving timer reading] :as held} (.-current hold)]
    (some-> timer js/clearTimeout)
    (set! (.-current hold) nil)
    (when moving (moved! e parts held))
    (heard! p {:t0 t0 :dir (if moving :send dir) :k k :rec-at rec-at :reading reading} live set-phase)
    (speech/let-go!)))

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

(def ^:private speaker-bars
  "The bars on a mic that reads a reply aloud (they move by CSS)."
  [:span.mic-speak-bars {:aria-hidden true} [:i] [:i] [:i] [:i] [:i]])

(defn- speaker-label
  "Under a mic that reads a reply aloud: whose reply, in which voice."
  [{:keys [title voice]}]
  [:span.mic-speaker [:b title] (when voice [:small voice])])

(defn- core
  "The button with its bars (a reply being read: its chat's bars and name), and the hint after a tap."
  [{:keys [phase canvas speaker] :as parts}]
  [:div.mic-core
   [:canvas.mic-flower {:ref #(set! (.-current canvas) %) :aria-hidden true
                        :style {:width flower/size :height flower/size}
                        :width (* flower/size (or js/devicePixelRatio 1)) :height (* flower/size (or js/devicePixelRatio 1))}]
   [:button.mic-button {:type "button" :aria-label (if speaker
                                                     (str (:title speaker) " is speaking: tap to stop this reply, hold to talk")
                                                     "Hold to talk; let go or drag up to send, drag left to add to the message, drag right to cancel, drag down to send now")
                        :data-tip "Hold to talk: let go or drag up sends, left adds to the message, right cancels, down sends now"
                        :on-pointer-down #(press! % parts)
                        :on-pointer-move #(drag! % parts)
                        :on-pointer-up #(release! % parts)
                        :on-pointer-cancel #(release! % parts)
                        :on-context-menu #(.preventDefault %)}
    (if speaker speaker-bars icon)]
   (when speaker [speaker-label speaker])
   (when (= phase :tap) [:span.mic-hint "Hold to talk"])])

(defn- row-of [[server chat]] (get-in @(rf/subscribe [:servers]) [server :rows chat]))

(defn- colour-of
  "The colour of the chat the mic speaks into (draft-key: [server chat] or :new), as a CSS var."
  [draft-key]
  (words/colour-var (if (vector? draft-key)
                      (:color (row-of draft-key))
                      (:color @(rf/subscribe [:new-settings])))))

(defn- speaker-of
  "The reply being read aloud (speaking: {:k :voice}, nil: none) as the mic shows it: its chat's
  title and colour, the voice's name; nil while the mic is held."
  [speaking phase]
  (when (and speaking (= :idle phase))
    (let [row (some-> (:k speaking) row-of)]
      {:title (or (not-empty (:title row)) "Claudia") :colour (some-> (:color row) words/colour-var)
       :voice (some-> (:voice speaking) voices/by-id :name)})))

(defn- speak-here
  "While recording in the app: a glow on the screen edge nearest the microphone iOS records from,
  so the device is held with that side free and towards the mouth. s: the recording session."
  [s]
  (when-let [edge (pure/speak-edge (:mic s) (screen-angle s))]
    ;; On the page's body: inside the mic its fixed position was the flower's, not the screen's.
    (react-dom/createPortal
     (r/as-element [:div.speak-here {:class (name edge) :aria-hidden true} [:span "speak here"]])
     js/document.body)))

(defn- view
  "The mic; free (out of its place in the rail) it is drawn at its spring's point, its slot kept as a
  dashed ring so the column stays as it is. A motion div always, and keyed, so the button stays the
  same element when it frees: it keeps the finger."
  [{:keys [phase draft-key mic free sx sy speaking] :as parts}]
  (let [speaker (speaker-of speaking phase)]
    [:<>
     (when free ^{:key "slot"} [:span.edge-gap.mic-slot {:aria-hidden true}])
     ^{:key "mic"}
     [:> (.-div motion) {:class (str "mic " (name phase) (when free " moved") (when speaker " speaking"))
                         :style (cond-> {"--chat" (or (:colour speaker) (colour-of draft-key))} free (assoc :left sx :top sy))}
      [clips/clip-list]
      (core (assoc parts :speaker speaker))
      (when (= phase :recording) [speak-here mic])]]))

(def ^:private spring
  "The mic's follow: a little slack and one soft overshoot, a follower with a will of its own."
  #js {:stiffness 170 :damping 17 :mass 1.1})

(defn- use-follow
  "The mic's spring: mx/my where it is pulled to, sx/sy where it is (drawn), put on spot whenever
  the parked spot changes while it is still."
  [spot]
  (let [mx (useMotionValue 0) my (useMotionValue 0)
        sx (useSpring mx spring) sy (useSpring my spring)
        parts {:mx mx :my my :sx sx :sy sy}]
    (h/use-effect (fn [] (when (and spot (not (.isAnimating sx))) (jump! parts spot)) nil) [(:x spot) (:y spot)])
    parts))

(defn- use-spot
  "The mic's spot on this device (rail? only; nil: in the rail), clamped again when the screen
  turns or resizes."
  [rail?]
  (let [[spot set-spot] (h/use-state #(when rail? (kept-spot)))]
    (h/use-effect
     (fn []
       (when rail?
         (let [f #(set-spot (kept-spot))]
           (.addEventListener js/window "resize" f)
           #(.removeEventListener js/window "resize" f))))
     [rail?])
    [spot set-spot]))

(defn button
  "The mic, or nothing where the mic cannot work. draft-key: the composer it speaks into ([server
  chat] or :new); the heard words go there (:mic/heard), sent or added as each press chose.
  opts {:rail? true}: on the tablet's rail (half flower, moves)."
  [draft-key & [{:keys [rail?]}]]
  (let [[ok set-ok] (h/use-state false)
        [spot set-spot] (use-spot rail?)
        [free set-free] (h/use-state (some? spot))
        follow (use-follow spot)
        [phase set-phase] (h/use-state :idle)
        [dir set-dir] (h/use-state :send)
        [mic set-mic] (h/use-state nil)
        hold (h/use-ref nil)
        live (h/use-ref nil)
        canvas (h/use-ref nil)]
    (h/use-effect (fn [] (.then (mic/available?) set-ok) nil) [])
    (use-flower! canvas live hold phase)
    (use-tap-hint! phase set-phase)
    (when ok
      (view (merge follow
                   {:phase phase :dir dir :canvas canvas :hold hold :live live :mic mic
                    :set-phase set-phase :set-dir set-dir :set-mic set-mic :draft-key draft-key
                    :rail? rail? :spot spot :set-spot set-spot :free free :set-free set-free
                    :speaking @(rf/subscribe [:speaking])})))))
