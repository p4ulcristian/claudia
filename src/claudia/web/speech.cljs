(ns claudia.web.speech
  "The chats' voices (README decisions 21 and 93): a tap on a chat's voice message plays that chat's
  waiting messages one after the other (claudia.web.views.pure.talk's line), each in its chat's voice
  by the hub's voice service; one heard to its end is told to its runner (:voice/heard). Holding the
  mic stops it and drops the rest of the line (the messages stay, unheard); the page going to the
  background drops it too, so nothing is said late. The reply being read is in app-db as :speaking
  (the mic shows it), with :loading until its sound is ready and plays."
  (:require [claudia.shared.voices :as voices]
            [claudia.web.sound :as sound]
            [claudia.web.views.pure.talk :as talk]
            [claudia.web.voice :as voice]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]))

(defonce ^:private line (atom talk/empty-line))
(defonce ^:private audio (atom {:ctx nil :source nil :analyser nil}))

(defn- visible? [] (= "visible" (.-visibilityState js/document)))

(defn- context! []
  (or (:ctx @audio)
      (:ctx (swap! audio assoc :ctx (js/AudioContext.)))))

(defn unlock!
  "Readies the sound inside a tap (iOS plays only after one)."
  []
  (let [^js c (context!)]
    (when (= "suspended" (.-state c)) (.catch (.resume c) (fn [_] nil)))))

(defn- silence! []
  (when-let [^js src (:source @audio)]
    (set! (.-onended src) nil)
    (try (.stop src) (catch :default _ nil)))
  (swap! audio assoc :source nil))

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
  (let [{:keys [^js source ^js analyser ^js ctx]} @audio]
    (when (and source analyser)
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

(defn- play! [item ^js buf]
  (let [^js c (context!)
        ^js src (.createBufferSource c)]
    (set! (.-buffer src) (voice/audio-buffer c buf))
    (.connect src (analyser!))
    (set! (.-onended src) #(do (heard! item) (finished! (:id item))))
    (sound/claim! ::reply stop!)
    (.start src)
    (swap! audio assoc :source src)
    (shown! item)))

(defn- read!
  "Reads item; its colour name is said only when its chat is not the one open now."
  [{:keys [id voice text] :as item}]
  (-> (voice/fetch-pcm (talk/spoken item (:open @rdb/app-db))
                       (:description (voices/by-id voice)) (talk/language-of text))
      (.then #(if (and (= id (:id (:current @line))) (visible?)) (play! item %) (finished! id)))
      (.catch (fn [e] (js/console.warn "voice:" (.-message e)) (finished! id)))))

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
  the other from now; whatever was playing stops."
  [items]
  (when (seq items)
    (silence!)
    (reset! line (reduce talk/with-item talk/empty-line items))
    (pump!)))

(defn say-now!
  "item is read at once (a reply's play button): the one being read is cut off."
  [item]
  (when (seq (:text item))
    (silence!)
    (swap! line #(-> % talk/without-current (talk/with-item-first item)))
    (pump!)))

(defn hold!
  "The mic is held: the reply being read stops and the rest of its chat's line is dropped (a hold
  then replies to that chat; its messages not heard stay)."
  []
  (stop!)
  (swap! line talk/held true))

(defn let-go!
  "The mic is let go."
  []
  (swap! line talk/held false))

(defn reading?
  "Whether a reply is being read now."
  []
  (some? (:current @line)))

(defn reading-chat
  "The chat whose reply is being read now ([server chat]), or nil."
  []
  (:k (:current @line)))

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
