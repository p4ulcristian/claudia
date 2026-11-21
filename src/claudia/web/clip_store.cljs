(ns claudia.web.clip-store
  "The mic's clips kept on this device until their words went out (README decision 20): in the
  browser's IndexedDB (database claudia-mic, store clips, key id), so a clip the listen service
  failed on is never lost, not even across a reload. A clip: {id k dir secs at blob}, k the composer
  it was spoken into (as JSON) and dir :send or :add. Every call answers a promise and never
  throws: a device without IndexedDB keeps clips in memory only.")

(def ^:private db-name "claudia-mic")
(def ^:private store "clips")

(defonce ^:private opened (atom nil))

(defn- upgrade!
  "A database at version old brought to version 3: the store clips (version 1) made; the store
  sounds (version 2, heard clips kept to listen back to, README decision 97) deleted."
  [^js db old]
  (when (< old 1) (.createObjectStore db store #js {:keyPath "id"}))
  (when (= old 2) (.deleteObjectStore db "sounds")))

(defn- open []
  (or @opened
      (reset! opened
              (js/Promise.
               (fn [ok no]
                 (if-not (exists? js/indexedDB)
                   (no (js/Error. "no IndexedDB"))
                   (let [^js r (.open js/indexedDB db-name 3)]
                     (set! (.-onupgradeneeded r) (fn [^js e] (upgrade! (.-result r) (.-oldVersion e))))
                     (set! (.-onsuccess r) #(ok (.-result r)))
                     (set! (.-onerror r) #(no (.-error r))))))))))

(defn- run
  "Promise of the result of (f object-store) in a transaction of mode; nil on any failure."
  [mode f]
  (-> (open)
      (.then (fn [^js db]
               (js/Promise.
                (fn [ok _]
                  (let [^js r (f (.objectStore (.transaction db store mode) store))]
                    (set! (.-onsuccess r) #(ok (.-result r)))
                    (set! (.-onerror r) #(ok nil)))))))
      (.catch (fn [_] nil))))

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
