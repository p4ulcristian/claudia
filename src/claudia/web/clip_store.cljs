(ns claudia.web.clip-store
  "The mic's clips kept on this device until their words went out (README decision 20): in the
  browser's IndexedDB (database claudia-mic, store clips, key id), so a clip the listen service
  failed on is never lost, not even across a reload. A clip: {id k dir secs at blob}, k the composer
  it was spoken into (as JSON) and dir :send or :add. Every call answers a promise and never
  throws: a device without IndexedDB keeps clips in memory only.

  Heard clips move to the store sounds (README decision 20, Listen back): {id at secs text raw blob
  msg}, msg the id of the message they went out in (nil until sent), kept for listening back on
  this device only and deleted 30 minutes after they were spoken (claudia.web.voice-back).")

(def ^:private db-name "claudia-mic")
(def ^:private store "clips")
(def ^:private sounds "sounds")

(defonce ^:private opened (atom nil))

(defn- upgrade!
  "The stores a database at version old lacks: clips (version 1), sounds (version 2)."
  [^js db old]
  (when (< old 1) (.createObjectStore db store #js {:keyPath "id"}))
  (when (< old 2) (.createObjectStore db sounds #js {:keyPath "id"})))

(defn- open []
  (or @opened
      (reset! opened
              (js/Promise.
               (fn [ok no]
                 (if-not (exists? js/indexedDB)
                   (no (js/Error. "no IndexedDB"))
                   (let [^js r (.open js/indexedDB db-name 2)]
                     (set! (.-onupgradeneeded r) (fn [^js e] (upgrade! (.-result r) (.-oldVersion e))))
                     (set! (.-onsuccess r) #(ok (.-result r)))
                     (set! (.-onerror r) #(no (.-error r))))))))))

(defn- run-in
  "Promise of the result of (f object-store) in a transaction of mode on store named st; nil on any
  failure."
  [st mode f]
  (-> (open)
      (.then (fn [^js db]
               (js/Promise.
                (fn [ok _]
                  (let [^js r (f (.objectStore (.transaction db st mode) st))]
                    (set! (.-onsuccess r) #(ok (.-result r)))
                    (set! (.-onerror r) #(ok nil)))))))
      (.catch (fn [_] nil))))

(defn- run [mode f] (run-in store mode f))

(defn put!
  "Keeps a clip ({:id :k :dir :secs :at :blob})."
  [{:keys [id k dir secs at blob]}]
  (run "readwrite" #(.put ^js % #js {:id id :k (js/JSON.stringify (clj->js k)) :dir (name dir)
                                     :secs secs :at at :blob blob})))

(defn drop!
  "Forgets a clip (its words went out, or it was thrown away)."
  [id]
  (run "readwrite" #(.delete ^js % id)))

(defn kept
  "Promise of every clip still kept, oldest first: [{:id :k :dir :secs :at :blob}]."
  []
  (.then (run "readonly" #(.getAll ^js %))
         (fn [rows]
           (->> (array-seq (or rows #js []))
                (map (fn [^js r] {:id (.-id r) :k (let [k (js->clj (js/JSON.parse (.-k r)))] (if (= k "new") :new k))
                                  :dir (keyword (.-dir r)) :secs (.-secs r) :at (.-at r) :blob (.-blob r)}))
                (sort-by :at)
                vec))))

;; ---- sounds: heard clips kept to listen back to ----

(defn heard!
  "Clip c ({:id :at :secs :blob}) was heard as text (clean) and raw (as heard): it leaves the
  clips and is kept among the sounds, not yet tied to a message."
  [{:keys [id at secs blob]} text raw]
  (-> (run-in sounds "readwrite" #(.put ^js % #js {:id id :at at :secs secs :text (str text) :raw (str raw)
                                                  :blob blob :msg nil}))
      (.then #(run "readwrite" (fn [^js o] (.delete o id))))))

(defn- tag-one! [id msg]
  (.then (run-in sounds "readonly" #(.get ^js % id))
         (fn [^js r] (when r (set! (.-msg r) msg) (run-in sounds "readwrite" #(.put ^js % r))))))

(defn tag!
  "The sounds ids went out in the message msg (its id)."
  [ids msg]
  (js/Promise.all (into-array (map #(tag-one! % msg) ids))))

(defn kept-sounds
  "Promise of every sound kept: [{:id :at :secs :text :raw :blob :msg}]."
  []
  (.then (run-in sounds "readonly" #(.getAll ^js %))
         (fn [rows]
           (vec (for [^js r (array-seq (or rows #js []))]
                  {:id (.-id r) :at (.-at r) :secs (.-secs r) :text (.-text r) :raw (.-raw r)
                   :blob (.-blob r) :msg (.-msg r)})))))

(defn drop-sound!
  "Deletes a kept sound (its time is up)."
  [id]
  (run-in sounds "readwrite" #(.delete ^js % id)))
