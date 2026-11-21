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
  Wider than a phone (README decisions 94, 109) the mic floats over the page, out of the rail: bottom
  right beside the rail until dragged, its spot kept on this device; a ring round the petals says
  move, and the finger reaching it (pure/in-ring?) at any time while holding keeps the recording
  going while the mic follows the finger on a spring (motion's useSpring: it trails and overshoots
  a little), its button kept on the screen; let go, the clip is heard and sent as when let go in
  place, and the mic stays there. Its flower is always whole, four ways.
  The mic is always the open chat's (README decision 107): its colour, its words. While the open
  chat's own reply is read aloud (README decision 21) its bars move on the mic and a tap stops it.
  The chats' voice messages wait as circles round it (README decisions 93 and 94,
  views/voice_circles.cljs), each a little mic: a tap plays that chat's messages (on the one playing:
  the next), a hold opens this same flower round the circle, in its chat's colour, and the words go
  to that chat; its left petal there skips all of that chat's waiting messages. Another chat read
  aloud shows on its own circle (the bars) and by its name beside the mic. Nothing is read while the
  mic is held: a hold stops the reading at once, and once it is a hold (not a tap) that chat's
  waiting messages are all marked heard (stop and drop); a tap on the mic while another chat reads
  starts that chat's message again."
  (:require ["motion/react" :refer [motion useMotionValue useSpring]]
            ["react-dom" :as react-dom]
            [claudia.shared.voices :as voices]
            [claudia.web.mic :as mic]
            [claudia.web.speech :as speech]
            [claudia.web.views.mic-clips :as clips]
            [claudia.web.views.mic-flower :as flower]
            [claudia.web.views.voice-circles :as voice-circles]
            [claudia.web.views.pure.mic :as pure]
            [re-frame.core :as rf]
            [claudia.web.views.pure.words :as words]
            [reagent.core :as r]
            [reagent.hooks :as h]))

(defn- flower-state
  "What the flower shows now: the phase, the bars' levels (eased), seconds spoken, the way letting
  go goes."
  [phase ^js live hold levels]
  (let [{:keys [dir rec-at movable circle]} (.-current hold)]
    {:phase phase :levels levels :way (or dir :send) :movable movable :circle (some? circle)
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

(defn- edge-width
  "The edge column's width, px; 0 on a phone, which has none."
  []
  (or (some-> (js/document.querySelector ".thumb-edge") .-offsetWidth) 0))

(defn- button-r
  "The floating mic's button's radius, px."
  []
  (/ (or (some-> (js/document.querySelector ".float-mic .mic-button") .-offsetWidth) 64) 2))

(defn- kept-spot
  "The mic's spot on this device, clamped so its whole flower is on the screen now, or nil (never
  moved)."
  []
  (let [kept (try (js->clj (js/JSON.parse (.getItem js/localStorage spot-key)) :keywordize-keys true)
                  (catch :default _ nil))]
    (when (map? kept)
      (pure/stored->spot kept js/innerWidth js/innerHeight))))

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

(defn- default-spot
  "Where a floating mic sits until dragged (pure/default-spot): beside the edge column, or on a
  phone just over the composer."
  []
  (pure/default-spot {:w js/innerWidth :h js/innerHeight :edge-w (edge-width) :r (button-r)
                      :composer-top (some-> (js/document.querySelector ".composer") .getBoundingClientRect .-top)}))

(defn- recording!
  "The microphone records session s: the hold, if still held, records from now."
  [s {:keys [hold live set-phase set-mic]}]
  (set! (.-current live) s)
  (set-mic s)
  (when (.-current hold)
    (set! (.-current hold) (assoc (.-current hold) :rec-at (js/performance.now)))
    (when-not (:moving (.-current hold)) (set-phase :recording))))

(defn- press!
  "Pointer down on the mic, or on a voice message's circle (target {:k its chat :at {:x :y} its
  offset from the mic}): the clip starts (the microphone opens if it is cold; the first time the
  browser asks for it), its flower round what was pressed. The words go to the circle's chat, else
  to draft-key's (README decision 107)."
  [^js e {:keys [hold set-phase set-dir set-reply set-aim draft-key floating?] :as parts} & [target]]
  (when (and (zero? (.-button e)) (nil? (.-current hold)))
    (.preventDefault e)
    (some-> (js/getSelection) .removeAllRanges)   ; nothing stays selected under the flower
    (.setPointerCapture (.-currentTarget e) (.-pointerId e))
    (let [reading (speech/reading-chat)
          reply (:k target)
          t0 (js/performance.now) warm (mic/warm?)
          p (.then (mic/start!) #(started! t0 warm %))]
      (speech/hold!)   ; it never talks over you
      (set-reply reply)
      (set-aim (:at target))
      ;; The composer as it is at the press: the one the person spoke into, even when another chat
      ;; is open by the time the words are heard.
      (set! (.-current hold) {:t0 (js/performance.now) :x0 (.-clientX e) :y0 (.-clientY e) :dir :send :p p
                              :k (or reply draft-key)
                              :reading reading :reading-msg (speech/reading-msg) :circle (:k target)
                              :movable (and floating? (not target)) :from (circle (.-currentTarget e))})
      (set-dir :send)
      (set-phase :starting)
      (watch-start! hold p)
      (-> p
          (.then #(recording! % parts))
          (.catch #(start-failed! set-phase %))))))

(defn- jump!
  "The mic's spring put at {:x :y} at once, at rest."
  [{:keys [^js mx ^js my ^js sx ^js sy]} {:keys [x y]}]
  (.jump mx x) (.jump my y) (.jump sx x) (.jump sy y))

(defn- start-move!
  "The finger reached the move ring: the recording goes on and the mic, out of its place, starts
  following the finger from where it sat."
  [{:keys [hold set-phase] :as parts}]
  (let [{:keys [from]} (.-current hold)]
    (set! (.-current hold) (assoc (.-current hold) :moving true :dir :send))
    (jump! parts from)
    (set-phase :moving)))

(defn- on-screen
  "Where a moved mic goes for a finger at x y: there, its whole flower kept on the screen."
  [x y]
  (pure/clamp-spot {:x x :y y} js/innerWidth js/innerHeight))

(defn- follow!
  "The mic, moving, pulled towards the finger (its whole flower kept on the screen)."
  [^js e {:keys [^js mx ^js my]}]
  (let [{:keys [x y]} (on-screen (.-clientX e) (.-clientY e))]
    (.set mx x) (.set my y)))

(declare park!)

(defn- settle!
  "The finger rested while moving: the mic is put down under it, still recording, and the hold
  chooses again from there: the petals round it, letting go in place sends, the ring moves on."
  [{:keys [hold live set-phase set-dir] :as parts}]
  (when-let [{:keys [rested] :as held} (.-current hold)]
    (when (:moving held)
      (let [to (park! (:x rested) (:y rested) parts held)]
        (set! (.-current hold) (assoc held :moving false :timer nil :x0 (:x rested) :y0 (:y rested)
                                      :from to :dir :send))
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
  "Pointer move while held: where the finger went chooses what letting go does (pure/gesture); a
  floating mic's finger in the move ring moves the mic."
  [^js e {:keys [hold set-dir] :as parts}]
  (when-let [{:keys [x0 y0 movable moving from]} (.-current hold)]
    (when (and movable (not moving) (pure/in-ring? (- (.-clientX e) (:x from)) (- (.-clientY e) (:y from))))
      (start-move! parts))
    (if (:moving (.-current hold))
      (do (follow! e parts) (watch-rest! e parts))
      (let [dx (- (.-clientX e) x0) dy (- (.-clientY e) y0)
            dir (cond-> (pure/gesture dx dy) (:circle (.-current hold)) pure/circle-way)]
        (set! (.-current hold) (assoc (.-current hold) :dir dir))
        (set-dir dir)))))

(defn- park!
  "The mic put down for a finger at x y: it glides there (its whole flower on the screen) and stays
  (kept on this device). -> where it goes {:x :y :r}."
  [x y {:keys [set-spot ^js mx ^js my]} {:keys [from]}]
  (let [at (on-screen x y)]
    (keep-spot! at)
    (set-spot at)
    (.set mx (:x at)) (.set my (:y at))
    (assoc at :r (:r from))))

(defn- moved!
  "Let go while moving: the mic is put down where the finger is (park!)."
  [^js e parts held]
  (park! (.-clientX e) (.-clientY e) parts held))

(defn- tapped!
  "A tap: on a circle it plays that chat's messages, or, on the chat being read, the next one (the
  one cut off counts as heard); on the mic, while its own chat's reply was read it only stopped it,
  else it says to hold, and another chat's message cut off by the press starts again."
  [{circle-k :circle :keys [reading reading-msg k]} set-phase]
  (set-phase (if (or circle-k (= reading k)) :idle :tap))
  (cond
    circle-k (do (speech/unlock!)
                 (when (and (= circle-k reading) reading-msg) (rf/dispatch [:voice/heard circle-k [reading-msg]]))
                 (rf/dispatch [:voice/play circle-k]))
    (and reading reading-msg (not= reading k)) (rf/dispatch [:voice/play reading])))

(defn- dropped!
  "After a hold (not a tap): the chat whose reading it stopped is done with (stop and drop: its
  waiting messages marked heard), and so is the circle's chat when its skip petal was chosen."
  [{:keys [reading dir] circle-k :circle}]
  (when reading (rf/dispatch [:voice/done reading]))
  (when (and circle-k (= dir :skip)) (rf/dispatch [:voice/done circle-k])))

(defn- heard!
  "A hold let go in place or on a petal: a tap plays, stops or says to hold (tapped!), cancel and
  skip drop the clip, else it is heard; a hold stops and drops what it cut off (dropped!)."
  [p {:keys [t0 dir k rec-at] :as held} live set-phase]
  (let [tap? (< (- (js/performance.now) t0) pure/min-hold-ms)
        secs (js/Math.round (/ (- (js/performance.now) (or rec-at t0)) 1000))]
    (.then p (fn [s]
               (set! (.-current live) nil)
               (cond
                 tap? (do (mic/discard! s) (tapped! held set-phase))
                 (#{:cancel :skip} dir) (do (mic/discard! s) (set-phase :idle) (dropped! held))
                 :else (do (set-phase :idle)
                           (dropped! held)
                           (clips/spoken! k dir secs (mic/stop! s))))))))

(defn- release!
  "Pointer up: a hold long enough is heard and sent (dragged down: now; dragged left: added to the
  message; dragged right: thrown away); a tap is dropped and says to hold; a move ends where the
  finger is, and its clip is heard and sent as when let go in place."
  [^js e {:keys [hold live set-phase set-reply set-aim] :as parts}]
  (when-let [{:keys [p dir moving timer] :as held} (.-current hold)]
    (some-> timer js/clearTimeout)
    (set! (.-current hold) nil)
    (set-reply nil)
    (set-aim nil)
    (when moving (moved! e parts held))
    (heard! p (assoc (select-keys held [:t0 :k :rec-at :reading :reading-msg :circle]) :dir (if moving :send dir)) live set-phase)
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

(defn- use-no-select!
  "While the mic or a circle is held, nothing on the page starts a selection (iOS selected the text
  behind the flower)."
  [phase]
  (h/use-effect
   (fn []
     (when-not (#{:idle :tap} phase)
       (let [stop #(.preventDefault %)]
         (.addEventListener js/document "selectstart" stop)
         #(.removeEventListener js/document "selectstart" stop))))
   [phase]))

(defn- speaker-label
  "Beside the mic while a reply is read aloud: whose reply (in its chat's colour), in which voice
  (getting ready while loading)."
  [{:keys [title voice loading colour]}]
  [:span.mic-speaker {:style {"--chat" colour}} [:b title]
   (when (or voice loading) [:small (str voice (when (and voice loading) " \u00b7 ") (when loading "getting ready"))])])

(defn- flower-canvas
  "The flower's canvas, centred on the mic, or on the circle held (aim: its offset {:x :y})."
  [canvas aim]
  [:canvas.mic-flower {:ref #(set! (.-current canvas) %) :aria-hidden true
                       :style (cond-> {:width flower/size :height flower/size}
                                aim (assoc :transform (str "translate(calc(-50% + " (:x aim) "px), calc(-50% + " (:y aim) "px))")))
                       :width (* flower/size (or js/devicePixelRatio 1)) :height (* flower/size (or js/devicePixelRatio 1))}])

(defn- circle-hands
  "What a finger on a voice message's circle does: the mic's own press, drag and let go."
  [parts]
  {:down #(press! %1 parts %2) :move #(drag! % parts) :up #(release! % parts)})

(defn- core
  "The button (the open chat's reply being read: its bars), the name of the chat being read, the
  hint after a tap, and the voice messages' circles round it."
  [{:keys [phase canvas speaker own? floating? aim reply] :as parts}]
  [:div.mic-core
   [flower-canvas canvas aim]
   [:button.mic-button {:type "button" :aria-label (if own?
                                                     (str (:title speaker) " is speaking: tap to stop this reply, hold to talk")
                                                     "Hold to talk; let go or drag up to send, drag left to add to the message, drag right to cancel, drag down to send now")
                        :data-tip "Hold to talk: let go or drag up sends, left adds to the message, right cancels, down sends now"
                        :on-pointer-down #(press! % parts)
                        :on-pointer-move #(drag! % parts)
                        :on-pointer-up #(release! % parts)
                        :on-pointer-cancel #(release! % parts)
                        :on-context-menu #(.preventDefault %)}
    (cond (and own? (:loading speaker)) voice-circles/speaker-loading own? voice-circles/speaker-bars :else icon)]
   (when speaker [speaker-label speaker])
   (when (= phase :tap) [:span.mic-hint "Hold to talk"])
   [:f> voice-circles/circles {:side (if floating? :ring :up) :hands (circle-hands parts)
                               :held (when aim reply) :away (not (#{:idle :tap} phase))}]])

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
      {:k (:k speaking) :title (or (not-empty (:title row)) "Claudia") :colour (some-> (:color row) words/colour-var)
       :voice (some-> (:voice speaking) voices/by-id :name) :loading (:loading speaking)})))

(defn- reply-label
  "Over a mic held while a reply was read: the chat the words go to."
  [{:keys [title]}]
  [:span.mic-speaker [:b (str "\u2192 " (or (not-empty title) "that chat"))] [:small "your reply"]])

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
  "The mic; floating (a touch tablet) it is drawn at its spring's point. A motion div always."
  [{:keys [phase draft-key mic floating? sx sy speaking reply] :as parts}]
  (let [speaker (speaker-of speaking phase)
        own? (and speaker (= (:k speaker) draft-key))
        to (some-> reply row-of)]
    [:<>
     [:> (.-div motion) {:class (str "mic " (name phase) (when floating? " moved") (when own? " speaking") (when reply " replying"))
                         :style (cond-> {"--chat" (colour-of draft-key)
                                         :left (if floating? sx "auto") :top (if floating? sy "auto")}
                                  reply (assoc "--rec" (words/colour-var (:color to))))}
      [clips/clip-list]
      (core (assoc parts :speaker speaker :own? own?))
      (when (and reply (not= :idle phase)) [reply-label to])
      (when (= phase :recording) [speak-here mic])]]))

(def ^:private spring
  "The mic's follow: a little slack and one soft overshoot, a follower with a will of its own; it
  comes to rest in about 0.3 s, not 0.5 s (2026-11-21, the user: back to still a bit faster)."
  #js {:stiffness 300 :damping 26 :mass 1})

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
  "A floating mic's spot on this device (else nil): where it was put, else bottom right; clamped
  again when the screen turns or resizes."
  [floating?]
  (let [spot-now #(or (kept-spot) (default-spot))
        [spot set-spot] (h/use-state #(when floating? (spot-now)))]
    (h/use-effect
     (fn []
       (when floating?
         (let [f #(set-spot (spot-now))]
           (.addEventListener js/window "resize" f)
           #(.removeEventListener js/window "resize" f))))
     [floating?])
    [spot set-spot]))

(defn button
  "The mic, or nothing where the mic cannot work. draft-key: the composer it speaks into ([server
  chat] or :new); the heard words go there (:mic/heard), sent or added as each press chose.
  opts {:floating? true}: the mic floating over the page (moves; README decisions 94, 110)."
  [draft-key & [{:keys [floating?]}]]
  (let [[ok set-ok] (h/use-state false)
        [spot set-spot] (use-spot floating?)
        follow (use-follow spot)
        [phase set-phase] (h/use-state :idle)
        [dir set-dir] (h/use-state :send)
        [mic set-mic] (h/use-state nil)
        [reply set-reply] (h/use-state nil)
        [aim set-aim] (h/use-state nil)
        hold (h/use-ref nil)
        live (h/use-ref nil)
        canvas (h/use-ref nil)]
    (h/use-effect (fn [] (.then (mic/available?) set-ok) nil) [])
    (use-flower! canvas live hold phase)
    (use-tap-hint! phase set-phase)
    (use-no-select! phase)
    (when ok
      (view (merge follow
                   {:phase phase :dir dir :canvas canvas :hold hold :live live :mic mic
                    :set-phase set-phase :set-dir set-dir :set-mic set-mic :draft-key draft-key
                    :reply reply :set-reply set-reply :aim aim :set-aim set-aim
                    :floating? floating? :spot spot :set-spot set-spot
                    :speaking @(rf/subscribe [:speaking])})))))

(defn- composer-shown?
  "The composer shows (claudia.web.views.chat/pane-foot): not read-only, and a server to chat on."
  []
  (let [me @(rf/subscribe [:me])]
    (and (not @(rf/subscribe [:read-only?])) (seq (:servers me)))))

(defn floating
  "The mic floating over the page (README decisions 94, 110), speaking into the open chat oc: beside
  the edge column wider than a phone, over the composer on a phone's chat screen."
  [oc]
  [:div.float-mic
   (when (composer-shown?) [:f> button (if oc (:ck oc) :new) {:floating? true}])])
