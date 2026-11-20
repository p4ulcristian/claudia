(ns claudia.web.back
  "Layers the browser's back closes (the media viewer, README decision 84): opening one adds a
  history entry at the same address, so back (the button, iOS's edge swipe, Android's back) closes
  the layer instead of leaving the chat or the app. Closing it from the page (×, Esc, a tap) takes
  its entry away again, and the popstate that brings is swallowed. Every popstate listener of the
  page asks took-pop? first and does nothing when a layer took the event.")

(defonce ^:private state (atom {:layers [] :swallow 0}))
(defonce ^:private decided (js/WeakMap.))

(defn- here [] (str js/location.pathname js/location.search))
(defn- entry-id [] (some-> js/history.state .-claudiaLayer))

(defn pop-outcome
  "What a popstate means. st: {:layers [{:id :path :close}] :swallow n}; entry: the layer id of the
  history entry now on top (nil: none); path: the address now. -> {:st (the next st) :took? (the page's
  own listeners leave the event alone) :close (a layer's close to call, or nil)}. A back from the top
  layer to the same address is the layer's; a pop elsewhere (a link, a notification) closes it too
  but is the page's."
  [{:keys [layers swallow] :as st} entry path]
  (let [top (peek layers)]
    (cond (pos? swallow) {:st (update st :swallow dec) :took? true}
          (and top (not= entry (:id top))) {:st (update st :layers pop) :took? (= path (:path top))
                                            :close (:close top)}
          :else {:st st :took? false})))

(defn- decide! []
  (let [{:keys [st took? close]} (pop-outcome @state (entry-id) (here))]
    (reset! state st)
    (when close (close))
    took?))

(defn took-pop?
  "Whether the popstate event e was a layer's (asked by each listener: decided once per event)."
  [e]
  (when-not (.has decided e) (.set decided e (decide!)))
  (.get decided e))

(defn open!
  "A new layer that back closes by calling close; returns its id."
  [close]
  (let [id (str (random-uuid))]
    (js/history.pushState #js {:claudiaLayer id} "")
    (swap! state update :layers conj {:id id :path (here) :close close})
    id))

(defn- without [st id] (update st :layers (fn [ls] (filterv #(not= id (:id %)) ls))))

(defn drop!
  "Layer id is gone from the page: its history entry goes too when it is still on top. Returns the
  layer, or nil when it was gone already."
  [id]
  (when-let [layer (some #(when (= id (:id %)) %) (:layers @state))]
    (let [on-top? (= id (entry-id))]
      (reset! state (cond-> (without @state id) on-top? (update :swallow inc)))
      (when on-top? (js/history.back))
      layer)))

(defn close!
  "Closes layer id from the page (×, Esc, a tap): drop! and its close."
  [id]
  (when-let [{:keys [close]} (drop! id)] (close)))
