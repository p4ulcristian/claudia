(ns claudia.web.views.mic-clips
  "The clips the mic let go (README decision 20), over the composer, in the order they were spoken:
  each is kept on this device (claudia.web.clip-store) until its words went out, heard by the hub
  (it tries once more by itself after a failure), and its words go out in turn: a clip waits for
  every earlier one. A clip that still fails stays with Try again and Throw away, and holds the
  ones after it; after a reload the kept clips come back that way. Words go to the composer the
  clip was spoken into (:mic/heard with its key), sent or added as the press chose."
  (:require [claudia.web.clip-store :as store]
            [claudia.web.hooks :as hooks]
            [claudia.web.mic :as mic]
            [claudia.web.views.pure.mic :as pure]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]
            [reagent.core :as r]))

(def ^:private retry-ms "How long after a failure the clip is heard once more by itself." 2500)

;; {:issued n :next n :held {n [result]}}: the line (pure/in-order).
(defonce ^:private line (atom {:issued 0 :next 0 :held {}}))
;; n -> {:n :id :k :dir :secs :t0 :state (:hearing :heard :failed) :tries}
(defonce ^:private rows (r/atom {}))
;; id -> the clip's sound (a Blob)
(defonce ^:private blobs (atom {}))

(defn- place! [] (dec (:issued (swap! line update :issued inc))))

(defn- arrive!
  "Clip n's outcome ({:text :raw :k :dir}, nil: nothing to give): what is now in order goes out."
  [n result]
  (let [out (atom nil)]
    (swap! line (fn [l] (let [[l' ready] (pure/in-order l n result)] (reset! out ready) l')))
    (swap! rows (fn [rs] (into {} (remove (fn [[i]] (< i (:next @line)))) (cond-> rs (get rs n) (assoc-in [n :state] :heard)))))
    (doseq [{:keys [text raw k dir clip]} @out] (rf/dispatch [:mic/heard k {:text text :raw raw :clip clip} dir]))))

(declare hear!)

(defn- failed! [n ^js e]
  (let [{:keys [tries id]} (get @rows n)]
    (mic/report! {:event "hear-failed" :bytes (some-> (get @blobs id) .-size) :error (.-message e) :tries tries})
    (if (< tries 2)
      (js/setTimeout #(hear! n) retry-ms)
      (swap! rows assoc-in [n :state] :failed))))

(defn- hear!
  "Clip n heard by the hub; on success its words take their turn and the kept copy moves to the
  sounds kept to listen back to (clip-store/heard!)."
  [n]
  (when-let [{:keys [id k dir secs t0]} (get @rows n)]
    (swap! rows #(-> % (assoc-in [n :state] :hearing) (update-in [n :tries] inc)))
    (-> (mic/hear (get @blobs id) (pure/listen-context (get-in @rdb/app-db [:chats k :messages])))
        (.then (fn [{:keys [text raw]}]
                 (mic/report! {:event "heard" :bytes (.-size (get @blobs id)) :chars (count text)})
                 (store/heard! {:id id :at t0 :secs secs :blob (get @blobs id)} text raw)
                 (swap! blobs dissoc id)
                 (arrive! n {:text text :raw raw :k k :dir dir :clip id})))
        (.catch #(failed! n %)))))

(defn- add-row! [n {:keys [id k dir secs at blob]} state]
  (swap! blobs assoc id blob)
  (swap! rows assoc n {:n n :id id :k k :dir dir :secs secs :t0 at :state state :tries 0}))

(defn- soundless!
  "Clip n came without sound (the mic's error e): said, and out of the line so the next ones go on."
  [n ^js e]
  (rf/dispatch [:mic/failed (.-message e)])
  (swap! rows dissoc n)
  (arrive! n nil))

(defn spoken!
  "A clip let go in composer k (dir :send or :add, secs long): its place in line now; heard, kept
  and given out once its sound (blob-promise) is here; a clip without sound only says so."
  [k dir secs blob-promise]
  (let [n (place!) id (str (random-uuid)) at (js/Date.now)]
    (swap! rows assoc n {:n n :id id :k k :dir dir :secs secs :t0 at :state :hearing :tries 0})
    (.then blob-promise
           (fn [blob]
             (let [clip {:id id :k k :dir dir :secs secs :at at :blob blob}]
               (add-row! n clip :hearing)
               (store/put! clip)
               (hear! n)))
           #(soundless! n %))))

(defn throw-away!
  "Clip n is given up on: forgotten here and on the device; the clips after it go on."
  [n]
  (when-let [{:keys [id]} (get @rows n)]
    (store/drop! id)
    (swap! blobs dissoc id)
    (arrive! n nil)))

(defn try-again! [n] (swap! rows assoc-in [n :tries] 0) (hear! n))

(defonce ^:private restored
  ;; The clips kept from before a reload come back, failed, in their order.
  (.then (store/kept) (fn [clips] (doseq [c clips] (add-row! (place!) c :failed)))))

;; ---- the list: one small pill per clip ----

(defn- clip-acts [n]
  [:<>
   [:button.mic-clip-act {:type "button" :aria-label "Try again" :data-tip "Try again" :on-click #(try-again! n)} "↻"]
   [:button.mic-clip-act {:type "button" :aria-label "Throw away" :data-tip "Throw away" :on-click #(throw-away! n)} "✕"]])

(defn- row [{:keys [n secs dir state] :as c} now]
  (let [label (pure/clip-label c now)]
    [:li.mic-clip {:class (name state) :data-tip label :aria-label label}
     (if (= state :failed) [:i.mic-clip-fail {:aria-hidden true} "!"] [:i.mic-clip-ring {:aria-hidden true}])
     [:span.mic-clip-len (str secs "s")]
     [:span.mic-clip-way {:aria-hidden true} (pure/way-mark dir)]
     (when (= state :failed) [clip-acts n])]))

(defn- list-view []
  (let [now (hooks/use-now) rs (vals @rows)]
    (when (seq rs)
      [:ol.mic-clips {:aria-live "polite"}
       (for [c (sort-by :n rs)] ^{:key (:n c)} [row c now])])))

(defn clip-list
  "Over the composer: each clip let go and not out yet, in spoken order."
  []
  [:f> list-view])
