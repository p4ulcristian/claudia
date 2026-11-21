(ns claudia.web.speech
  "The chats' voices (README decision 21): replies of chats with a voice, read aloud one at a time
  in arrival order (claudia.web.views.pure.talk's line), each in its chat's voice by the hub's
  voice service. Holding the mic drops the reply being read and holds the rest until it is let go;
  a chat turned Off leaves the line; the page going to the background drops it all, so nothing is
  said late. The reply being read is in app-db as :speaking (the mic shows it), with :loading until
  its first sound plays (the sound plays as it comes, a sentence at a time)."
  (:require [claudia.shared.voices :as voices]
            [claudia.web.sound :as sound]
            [claudia.web.views.pure.talk :as talk]
            [claudia.web.voice :as voice]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]))

(defonce ^:private line (atom talk/empty-line))
(def ^:private quiet
  "No reply sounding: no pieces scheduled (:sources), none to come (:stream, the fetch's
  AbortController), :until the end of the last piece on the context's clock, :fed once the whole
  sound has come."
  {:sources [] :until nil :stream nil :fed false})
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

(defn- finished!
  "The reply with this id is over (read to its end or failed): the next one, if it still is this one."
  [id]
  (when (= id (:id (:current @line)))
    (sound/release! ::reply)
    (swap! line talk/without-current)
    (shown! nil)
    (pump!)))

(defn- streaming? "Whether ctrl is the fetch of the reply sounding now." [ctrl] (identical? ctrl (:stream @audio)))

(defn- piece-ended!
  "A piece of item's sound ended: the reply is over once it has all come and none is left."
  [item ^js src]
  (let [{:keys [sources fed]} (swap! audio update :sources (fn [ss] (filterv #(not (identical? % src)) ss)))]
    (when (and fed (empty? sources)) (finished! (:id item)))))

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
  "item's sound has all come (or broke off: what came still plays): the reply is over once its
  pieces have played."
  [item ctrl]
  (when (streaming? ctrl)
    (when (empty? (:sources (swap! audio assoc :fed true))) (finished! (:id item)))))

(defn- read!
  "Reads item, playing its sound as it comes; its colour name is said only when its chat is not the
  one open now."
  [{:keys [voice text] :as item}]
  (let [ctrl (js/AbortController.)]
    (swap! audio merge quiet {:stream ctrl})
    (-> (voice/stream-pcm (talk/spoken item (:open @rdb/app-db)) (:description (voices/by-id voice))
                          (talk/language-of text) (.-signal ctrl) #(piece! item ctrl %))
        (.catch (fn [e] (when (streaming? ctrl) (js/console.warn "voice:" (.-message e)))))
        (.then #(streamed! item ctrl)))))

(defn- pump!
  "Starts the next reply when none is being read and the mic is not held."
  []
  (let [[before after] (swap-vals! line talk/next-up)
        item (:current after)]
    (when (and item (not= item (:current before)))
      (shown! (assoc item :loading true))
      (read! item))))

(defn say!
  "item {:id :k :voice :text} joins the line (a reply already waiting, read or being read does not)."
  [item]
  (when (and (seq (:text item)) (visible?))
    (swap! line talk/with-item item)
    (pump!)))

(defn say-now!
  "item is read at once (a reply's play button): the one being read is cut off."
  [item]
  (when (seq (:text item))
    (silence!)
    (swap! line #(-> % talk/without-current (talk/with-item-first item)))
    (pump!)))

(defn skip!
  "The reply being read stops; the next one comes."
  []
  (silence!)
  (swap! line talk/without-current)
  (shown! nil)
  (pump!))

(defn hold!
  "The mic is held: the reply being read stops and nothing is read until let-go!."
  []
  (silence!)
  (swap! line talk/held true)
  (shown! nil))

(defn let-go!
  "The mic is let go: the waiting replies come again."
  []
  (swap! line talk/held false)
  (pump!))

(defn reading?
  "Whether a reply is being read now."
  []
  (some? (:current @line)))

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
  (shown! nil)
  (swap! line #(assoc talk/empty-line :said (:said %) :held (:held %)))
  (shown! nil))

(defonce ^:private hidden-stops
  ;; A page going to the background goes quiet.
  (delay (.addEventListener js/document "visibilitychange" #(when-not (visible?) (stop!)))))

(defn start! "Installs the background stop once." [] @hidden-stops)
