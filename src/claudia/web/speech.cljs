(ns claudia.web.speech
  "The chats' voices (README decisions 21 and 93): a tap on a chat's voice message plays that chat's
  waiting messages one after the other (claudia.web.views.pure.talk's line), each in its chat's voice
  by the hub's voice service; one heard to its end is told to its runner (:voice/heard). Holding the
  mic stops it and drops the rest of the line (the messages stay, unheard); the page going to the
  background drops it too, so nothing is said late. The reply being read is in app-db as :speaking
  (the mic shows it), with :loading until its first sound plays (the sound plays as it comes, a
  sentence at a time)."
  (:require [claudia.shared.voices :as voices]
            [claudia.web.sound :as sound]
            [claudia.web.views.pure.talk :as talk]
            [claudia.web.voice :as voice]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]))

(defonce ^:private line (atom talk/empty-line))
(def ^:private quiet
  "No reply sounding: no pieces scheduled (:sources), none to come (:stream, the fetch's
  AbortController), :until the end of the last piece on the context's clock, :fed once the sound
  has stopped coming, :whole when it came to its end (not broken off)."
  {:sources [] :until nil :stream nil :fed false :whole false})
(defonce ^:private audio (atom (assoc quiet :ctx nil :analyser nil)))

(defn- visible? [] (= "visible" (.-visibilityState js/document)))

(defn- context! []
  (or (:ctx @audio)
      (:ctx (swap! audio assoc :ctx (js/AudioContext.)))))

(defn unlock!
  "Readies the sound inside a tap (iOS plays only after one)."
  []
  (let [^js c (context!)]
    (when (= "suspended" (.-state c)) (.catch (.resume c) (fn [_] nil)))))

(defn- silence!
  "The reply sounding now stops: its fetch is cancelled and every piece scheduled is stopped."
  []
  (let [{:keys [sources ^js stream]} @audio]
    (some-> stream .abort)
    (doseq [^js src sources]
      (set! (.-onended src) nil)
      (try (.stop src) (catch :default _ nil))))
  (swap! audio merge quiet))

(defn- analyser!
  "The node every reply plays through on its way out, so its spectrum can be drawn (the rail's bars)."
  []
  (or (:analyser @audio)
      (let [^js c (context!) ^js a (.createAnalyser c)]
        (set! (.-fftSize a) 512)
        (.connect a (.-destination c))
        (:analyser (swap! audio assoc :analyser a)))))

(defn spectrum
  "The reply being read now as {:spectrum (256 bytes 0-255, low to high) :rate}, nil when none plays."
  []
  (let [{:keys [sources ^js analyser ^js ctx]} @audio]
    (when (and (seq sources) analyser)
      (let [a (js/Uint8Array. (.-frequencyBinCount analyser))]
        (.getByteFrequencyData analyser a)
        {:spectrum (vec (array-seq a)) :rate (.-sampleRate ctx)}))))

(defn- shown!
  "The mic shows item (nil: none); :loading while its sound is still being made."
  [item]
  (rf/dispatch [:speech/now (some-> item (select-keys [:id :k :voice :loading]))]))

(declare pump! stop!)

(defn- heard!
  "A voice message read to its end: its runner hears of it, so it goes on every device."
  [{:keys [k msg]}]
  (when msg (rf/dispatch [:voice/heard k [msg]])))

(defn- finished!
  "The reply with this id is over (read to its end or failed): the next one, if it still is this one."
  [id]
  (when (= id (:id (:current @line)))
    (sound/release! ::reply)
    (swap! line talk/without-current)
    (shown! nil)
    (pump!)))

(defn- streaming? "Whether ctrl is the fetch of the reply sounding now." [ctrl] (identical? ctrl (:stream @audio)))

(defn- over!
  "item's reply is over: heard when its whole sound came and played (a broken-off one is not)."
  [item whole]
  (when whole (heard! item))
  (finished! (:id item)))

(defn- piece-ended!
  "A piece of item's sound ended: the reply is over once it has all come and none is left."
  [item ^js src]
  (let [{:keys [sources fed whole]} (swap! audio update :sources (fn [ss] (filterv #(not (identical? % src)) ss)))]
    (when (and fed (empty? sources)) (over! item whole))))

(defn- scheduled!
  "buf plays right where the piece before it ends (at once when none is left), so the pieces join
  without a gap; the first one starts the reply."
  [item ^js buf]
  (let [^js c (context!)
        ^js src (.createBufferSource c)
        {:keys [until]} @audio
        at (max (.-currentTime c) (or until 0))]
    (set! (.-buffer src) buf)
    (.connect src (analyser!))
    (set! (.-onended src) #(piece-ended! item src))
    (when-not until (sound/claim! ::reply stop!) (shown! item))
    (.start src at)
    (swap! audio #(-> % (update :sources conj src) (assoc :until (+ at (.-duration buf)))))))

(defn- piece!
  "A piece of item's sound arrived (whole samples): it is scheduled while its fetch is still the one
  sounding and the page is in front."
  [item ctrl ^js pcm]
  (when (streaming? ctrl)
    (if (visible?) (scheduled! item (voice/audio-buffer (context!) pcm)) (stop!))))

(defn- streamed!
  "item's sound has stopped coming, whole or broken off (what came still plays): the reply is over
  once its pieces have played."
  [item ctrl whole]
  (when (streaming? ctrl)
    (let [{:keys [sources]} (swap! audio assoc :fed true :whole whole)]
      (when (empty? sources) (over! item whole)))))

(defn- read!
  "Reads item, playing its sound as it comes; its colour name is said only when its chat is not the
  one open now."
  [{:keys [voice text] :as item}]
  (let [ctrl (js/AbortController.)]
    (swap! audio merge quiet {:stream ctrl})
    (-> (voice/stream-pcm (talk/spoken item (:open @rdb/app-db)) (:description (voices/by-id voice))
                          (talk/language-of text) (.-signal ctrl) #(piece! item ctrl %))
        (.then (fn [_] true)
               (fn [e] (when (streaming? ctrl) (js/console.warn "voice:" (.-message e))) false))
        (.then #(streamed! item ctrl %)))))

(defn- pump!
  "Starts the next reply when none is being read and the mic is not held."
  []
  (let [[before after] (swap-vals! line talk/next-up)
        item (:current after)]
    (when (and item (not= item (:current before)))
      (shown! (assoc item :loading true))
      (read! item))))

(defn play-chat!
  "items (one chat's voice messages, oldest first, each {:id :k :voice :text :msg}) are read one after
  the other from now (a tap on its circle); whatever was playing stops, and what waited from other
  chats comes after them."
  [items]
  (when (seq items)
    (silence!)
    (swap! line talk/with-chat-first items)
    (pump!)))

(defn enqueue!
  "item (a voice message just come, README decision 94) is read as soon as nothing else is and the
  mic is not held: after the ones already waiting."
  [item]
  (when (visible?)
    (swap! line talk/with-item item)
    (pump!)))

(defn say-now!
  "item is read at once (a reply's play button): the one being read is cut off."
  [item]
  (when (seq (:text item))
    (silence!)
    (swap! line #(-> % talk/without-current (talk/with-item-first item)))
    (pump!)))

(defn hold!
  "The mic is held: the reply being read stops and the rest of its chat's line is dropped (the mic,
  once it is a hold, marks that chat's messages heard: README decision 105); other chats' wait until
  let-go!."
  []
  (let [k (:k (:current @line))]
    (sound/release! ::reply)
    (silence!)
    (swap! line #(cond-> (-> % talk/without-current (talk/held true)) k (talk/without-chat k)))
    (shown! nil)))

(defn let-go!
  "The mic is let go: what waits comes."
  []
  (swap! line talk/held false)
  (pump!))

(defn reading?
  "Whether a reply is being read now."
  []
  (some? (:current @line)))

(defn reading-chat
  "The chat whose reply is being read now ([server chat]), or nil."
  []
  (:k (:current @line)))

(defn reading-msg
  "The voice message being read now (its id), or nil (none, or a reply's play button)."
  []
  (:msg (:current @line)))

(defn drop-chat!
  "Chat k's voice is Off: its waiting replies go, and it stops if it is being read."
  [k]
  (when (= k (:k (:current @line))) (silence!) (shown! nil))
  (swap! line talk/without-chat k)
  (pump!))

(defn stop!
  "Silence now; whatever waits is dropped (another sound started, or the page went to the background)."
  []
  (sound/release! ::reply)
  (silence!)
  (swap! line #(assoc talk/empty-line :said (:said %) :held (:held %)))
  (shown! nil))

(defonce ^:private hidden-stops
  ;; A page going to the background goes quiet.
  (delay (.addEventListener js/document "visibilitychange" #(when-not (visible?) (stop!)))))

(defn start! "Installs the background stop once." [] @hidden-stops)
