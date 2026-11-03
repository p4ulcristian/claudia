(ns claudia.web.views.clojure
  "The clojure form: a project's namespaces as chalk bubbles in layers (entry points first, leaves
  last), arrows for :require (one going back closes a circle: red). A prefix with namespaces under
  it is one bubble that opens on click. A namespace lists its vars below the map, a var opens its
  source, an arrow lists the functions that cross it. Read live through the runner (clj-kondo)."
  (:require [clojure.string :as str]
            [reagent.hooks :as h]
            [claudia.web.api :as api]
            [claudia.web.shiki :as shiki]
            [claudia.web.views.code :as code]))

(def ^:private poll-ms 4000)
(def ^:private col-w 180)
(def ^:private row-h 84)
(def ^:private layer-h 120)
(def ^:private node-w 150)
(def ^:private pad 40)
(def ^:private colours ["var(--c-teal)" "var(--c-amber)" "var(--c-sky)" "var(--c-pink)" "var(--c-lime)" "var(--c-violet)"
                        "var(--c-orange)" "var(--c-cyan)" "var(--c-red)" "var(--c-indigo)" "var(--c-yellow)" "var(--c-green)"])
(def ^:private mark-colour {"red" "var(--red)" "green" "var(--green)" "amber" "var(--amber)" "blue" "var(--blue)" "violet" "var(--violet)" "grey" "var(--muted)"})
(def ^:private items code/items)

(defn kget
  "A map value by a string key, whether the map came from JSON (keyword keys) or from EDN."
  [m k]
  (when (map? m) (or (get m k) (get m (keyword k)))))

(defn- under? [n p] (or (not (seq p)) (= n p) (str/starts-with? n (str p "."))))
(defn- parent [n] (if (str/includes? n ".") (subs n 0 (str/last-index-of n ".")) ""))
(defn- in-node? [n node] (if (:exact node) (= n (:key node)) (under? n (:key node))))
(defn- id-of [n] (if (:exact n) (str "=" (:key n)) (:key n)))
(defn- strip [n p] (if (and (seq p) (str/starts-with? n (str p "."))) (subs n (inc (count p))) n))

(defn layout
  "The bubbles and arrows of the map at prefix at, laid out in layers."
  [all at]
  (let [members (filter #(under? (:name %) at) all)
        node-of (fn [nm] (if (= nm at) (str "=" at)
                             (let [seg (first (str/split (subs nm (if (seq at) (inc (count at)) 0)) #"\."))]
                               (if (seq at) (str at "." seg) seg))))
        by-key (reduce (fn [m n]
                         (let [id (node-of (:name n))
                               exact (str/starts-with? id "=")
                               k (if exact at id)
                               node (or (get m id) {:key k :label (last (str/split (subs k (if (or exact (not (seq at))) 0 (inc (count at)))) #"\."))
                                                    :exact exact :group false :count 0 :vars 0})]
                           (assoc m id (cond-> (-> node (update :count inc) (update :vars + (or (:vars n) 0)))
                                         (and (not exact) (not= (:name n) k)) (assoc :group true)))))
                       {} members)
        ids (vec (sort (keys by-key)))
        by-key (reduce (fn [m [i id]] (assoc-in m [id :color] (nth colours (mod i (count colours))))) by-key (map-indexed vector ids))
        pairs (reduce (fn [m n]
                        (reduce (fn [m rq]
                                  (let [a (node-of (:name n)) b (node-of rq)]
                                    (if (and (under? rq at) (not= a b) (contains? by-key b)) (update m [a b] (fnil inc 0)) m)))
                                m (items (:requires n))))
                      {} members)
        out (reduce (fn [m [a b]] (update m a conj b)) (zipmap ids (repeat [])) (keys pairs))
        has-in (set (map second (keys pairs)))
        back (let [state (atom {}) back (atom #{})]
               (letfn [(visit [v]
                         (swap! state assoc v 1)
                         (doseq [w (get out v)]
                           (case (get @state w)
                             1 (swap! back conj [v w])
                             nil (visit w)
                             nil))
                         (swap! state assoc v 2))]
                 (doseq [id (concat (remove has-in ids) ids) :when (not (contains? @state id))] (visit id)))
               @back)
        layer (loop [layer (zipmap ids (repeat 0)) pass 0]
                (let [next (reduce (fn [l [a b]] (if (or (back [a b]) (>= (l b) (inc (l a)))) l (assoc l b (inc (l a))))) layer (keys pairs))]
                  (if (or (= next layer) (> pass (count ids))) next (recur next (inc pass)))))
        n-layers (if (seq ids) (inc (reduce max 0 (vals layer))) 0)
        cols (vec (for [li (range n-layers)] (vec (filter #(= li (layer %)) ids))))
        place (fn [cols] (into {} (for [c cols [i id] (map-indexed vector c)] [id (- i (/ (dec (count c)) 2))])))
        near (fn [pos id dir] (for [[a b] (keys pairs) :when (if (= dir 1) (= b id) (= a id))] (pos (if (= dir 1) a b))))
        cols (loop [s 0 cols cols]
               (if (>= s 4)
                 cols
                 (let [dir (if (even? s) 1 -1)
                       order (if (= dir 1) (range n-layers) (range (dec n-layers) -1 -1))
                       cols (reduce (fn [cols li]
                                      (let [pos (place cols)
                                            c (nth cols li)
                                            at-pos (into {} (for [id c :let [ns (near pos id dir)]] [id (if (seq ns) (/ (reduce + ns) (count ns)) (pos id))]))]
                                        (assoc cols li (vec (sort-by at-pos c)))))
                                    cols order)]
                   (recur (inc s) cols))))
        rows (max 1 (reduce max 0 (map count cols)))
        nodes (vec (for [[li c] (map-indexed vector cols) [i id] (map-indexed vector c)
                         :let [n (get by-key id)]]
                     (assoc n :layer li :slot (+ (/ (- rows (count c)) 2) i)
                            :r (+ 11 (min 20 (* 1.7 (js/Math.sqrt (if (:group n) (* 12 (:count n)) (:vars n)))))))))]
    {:nodes nodes
     :edges (vec (for [[[a b] c] pairs] {:from a :to b :pairs c :back (contains? back [a b])}))
     :layers n-layers :rows rows}))

(defn- place-nodes [nodes layers rows dir]
  [(mapv (fn [n] (if (= dir "across")
                   (assoc n :x (+ pad 50 (* (:layer n) col-w)) :y (+ pad (* (:slot n) row-h) (/ row-h 2)))
                   (assoc n :x (+ pad 60 (* (:slot n) node-w)) :y (+ pad 40 (* (:layer n) layer-h)))))
         nodes)
   (if (= dir "across")
     {:w (+ (* pad 2) 100 (* (dec layers) col-w)) :h (+ (* pad 2) (* rows row-h) 10)}
     {:w (+ (* pad 2) 120 (* (dec rows) node-w)) :h (+ (* pad 2) 100 (* (dec layers) layer-h))})])

(defn- best-dir
  "The direction whose map is closest in shape to its space (the half screen is narrow, about 0.8)."
  [layers rows aim]
  (let [ratio (fn [d] (let [{:keys [w h]} (second (place-nodes [] layers rows d))] (js/Math.abs (js/Math.log (/ (/ w h) aim)))))]
    (if (< (ratio "down") (ratio "across")) "down" "across")))

(defn- edge-path [a b back dir]
  (if (= dir "down")
    (if (and (not back) (> (:layer b) (:layer a)))
      (let [y1 (+ (:y a) (:r a)) y2 (- (:y b) (:r b) 6) my (/ (+ y1 y2) 2)]
        (str "M" (:x a) " " y1 "C" (:x a) " " my " " (:x b) " " my " " (:x b) " " y2))
      (let [lift (+ 40 (* 0.15 (js/Math.abs (- (:y a) (:y b)))) (* 0.1 (js/Math.abs (- (:x a) (:x b)))))
            x1 (- (:x a) (:r a)) x2 (- (:x b) (:r b) 6) y2 (+ (:y b) (if (= (:y a) (:y b)) 4 0)) mx (- (min x1 x2) lift)]
        (str "M" x1 " " (:y a) "C" mx " " (:y a) " " mx " " y2 " " x2 " " y2)))
    (if (and (not back) (> (:layer b) (:layer a)))
      (let [x1 (+ (:x a) (:r a)) x2 (- (:x b) (:r b) 6) mx (/ (+ x1 x2) 2)]
        (str "M" x1 " " (:y a) "C" mx " " (:y a) " " mx " " (:y b) " " x2 " " (:y b)))
      (let [lift (+ 40 (* 0.15 (js/Math.abs (- (:x a) (:x b)))) (* 0.1 (js/Math.abs (- (:y a) (:y b)))))
            x1 (:x a) y1 (- (:y a) (:r a)) x2 (+ (:x b) (if (= (:x a) (:x b)) 4 0)) y2 (- (:y b) (:r b) 6) my (- (min y1 y2) lift)]
        (str "M" x1 " " y1 "C" x1 " " my " " x2 " " my " " x2 " " y2)))))

(defn source-view [{:keys [code row lang]}]
  (let [tokens (shiki/use-tokens code (or lang "clj") false)]
    (into [:pre.cl-src]
          (map-indexed (fn [i l]
                         ^{:key i}
                         (into [:div [:span.cl-no (+ (or row 1) i)]]
                               (if-let [toks (shiki/line tokens i)]
                                 (for [[j ^js t] (map-indexed vector (array-seq toks))]
                                   ^{:key j} [:span {:style {:color (.-color t)}} (.-content t)])
                                 [l])))
                       (str/split (str code) #"\n" -1)))))

(defn- edge-panel [edge used go]
  (let [by-ns (group-by :ns used)
        froms (count (distinct (mapcat :from used)))
        {:keys [from to]} edge]
    [:<>
     [:h4.cl-title (:key from) " " [:span.cl-arrow "→"] " " (:key to)]
     (cond
       (nil? used) [:p.f-note "Reading…"]
       (empty? used) [:p.f-note "No function calls cross it (the requires are for macros, specs or loading only)."]
       :else
       [:<>
        [:p.f-note (str (count used) " function" (when (> (count used) 1) "s") " used, from " froms " namespace" (when (> froms 1) "s"))]
        (for [[ns us] by-ns]
          ^{:key ns}
          [:div.cl-group
           [:div.cl-ns ns]
           (for [u us]
             ^{:key (:name u)}
             [:div.cl-use
              [:button.cl-fn {:type "button" :on-click #(go (:ns u) (:name u))} (:name u)]
              [:span.cl-times (str "×" (:count u))]
              [:span.cl-from (str "← " (str/join ", " (map #(let [s (strip % (if (:exact from) (parent (:key from)) (:key from)))] (if (seq s) s %)) (:from u))))]])])])]))

(defn- ns-panel [ns detail all var-pick set-var-pick src marks go]
  (let [me (some #(when (= (:name %) ns) %) all)
        by (map :name (filter #(some #{ns} (items (:requires %))) all))]
    [:<>
     [:h4.cl-title ns]
     (when (:doc detail) [:p.cl-doc (:doc detail)])
     [:div.cl-reqs
      [:span.k "requires"]
      (for [rq (items (:requires me))] ^{:key rq} [:button.cl-chip {:type "button" :on-click #(go rq)} rq])
      (for [rq (items (:external detail))] ^{:key rq} [:span.cl-chip.ext rq])
      (when (and (empty? (items (:requires me))) (empty? (items (:external detail)))) [:span.f-note "nothing"])]
     [:div.cl-reqs
      [:span.k "required by"]
      (for [rq by] ^{:key rq} [:button.cl-chip {:type "button" :on-click #(go rq)} rq])
      (when (empty? by) [:span.f-note "nobody"])]
     (if-not detail
       [:p.f-note "Reading…"]
       (for [v (items (:vars detail))
             :let [m (some #(when (= (:var %) (:name v)) %) marks)
                   open (= var-pick (:name v))]]
         ^{:key (:name v)}
         [:div.cl-var {:class [(when (:private v) "priv") (when open "open")]}
          [:button.cl-var-head {:type "button" :on-click #(set-var-pick (when-not open (:name v)))}
           [:span.cl-fn (:name v)]
           (when (not= "fn" (:kind v)) [:span.cl-kind (:kind v)])
           (when (:private v) [:span.cl-kind "private"])
           (for [[i a] (map-indexed vector (items (:args v)))] ^{:key i} [:span.cl-args a])]
          (when (:doc v) [:div.cl-vdoc (if open (:doc v) (first (str/split-lines (:doc v))))])
          (when m [:div.cl-mark {:style {:color (get mark-colour (:color m) "var(--amber)")}} (str "● " (:note m))])
          (when open (if src [:f> source-view src] [:p.f-note "Reading…"]))]))
     (for [m marks :when (not (:var m))]
       ^{:key (:id m)} [:div.cl-mark {:style {:color (get mark-colour (:color m) "var(--amber)")}} (str "● " (:note m))])]))

(defn- view-of
  "The window on the map for a zoom: k times closer around (cx, cy), kept inside the map."
  [frame z]
  (let [k (or (:k z) 1) w (/ (:w frame) k) hh (/ (:h frame) k)
        clamp (fn [c lo size win] (min (- (+ lo size) (/ win 2)) (max (+ lo (/ win 2)) c)))
        cx (clamp (or (:cx z) (+ (:x frame) (/ (:w frame) 2))) (:x frame) (:w frame) w)
        cy (clamp (or (:cy z) (+ (:y frame) (/ (:h frame) 2))) (:y frame) (:h frame) hh)]
    {:x (- cx (/ w 2)) :y (- cy (/ hh 2)) :w w :h hh}))

(defn clojure-form [data {:keys [server chat]}]
  (let [demo (when (map? (:demo data)) (:demo data))
        root (if (string? (:root data)) (:root data) "")
        live (and (not demo) (seq root) chat)
        url-of (fn [q] (api/url server (str "chats/" (js/encodeURIComponent chat) "/clojure") (merge {:root root} q)))
        [all set-all] (h/use-state nil)
        [error set-error] (h/use-state nil)
        [at set-at] (h/use-state (if (string? (:at data)) (:at data) ""))
        [pick set-pick] (h/use-state nil)
        [var-pick set-var-pick] (h/use-state nil)
        [edge set-edge] (h/use-state nil)
        [detail set-detail] (h/use-state nil)
        [src set-src] (h/use-state nil)
        [used set-used] (h/use-state nil)
        [hover set-hover] (h/use-state nil)
        [full set-full] (h/use-state false)
        [chosen-dir set-chosen-dir] (h/use-state (when (#{"down" "across"} (:direction data)) (:direction data)))
        ;; The zoom lives in a ref and moves the viewBox straight on the element (no re-render per
        ;; wheel step, chalk masks off while moving); zoom-k follows once it rests, for the buttons.
        zoom (h/use-ref nil) ; nil: the whole map; else {:k :cx :cy}
        [zoom-k set-zoom-k] (h/use-state 1)
        latest (h/use-ref nil) ; {:frame :vb}
        frame-req (h/use-ref 0)
        rest-timer (h/use-ref nil)
        dragged (h/use-ref false)
        [box-w set-box-w] (h/use-state 0)
        [bounds set-bounds] (h/use-state nil)
        box (h/use-ref nil)
        mapbox (h/use-ref nil)
        svg-ref (h/use-ref nil)
        drawn (h/use-ref nil)
        pan (h/use-ref nil)
        apply! (fn []
                 (js/cancelAnimationFrame (.-current frame-req))
                 (set! (.-current frame-req)
                       (js/requestAnimationFrame
                        (fn []
                          (let [el (.-current svg-ref) l (.-current latest)]
                            (when (and el l)
                              (let [vb (view-of (:frame l) (.-current zoom))]
                                (set! (.-current latest) (assoc l :vb vb))
                                (.setAttribute el "viewBox" (str (:x vb) " " (:y vb) " " (:w vb) " " (:h vb)))
                                (.add (.-classList el) "moving")
                                (js/clearTimeout (.-current rest-timer))
                                (set! (.-current rest-timer)
                                      (js/setTimeout (fn [] (.remove (.-classList el) "moving") (set-zoom-k (or (:k (.-current zoom)) 1))) 160)))))))))
        ;; Zoom by f at a point of the screen (the map's middle when none), keeping that point still.
        zoom-at (fn [f cx cy]
                  (let [el (.-current svg-ref) l (.-current latest) ctm (some-> el .getScreenCTM)]
                    (when (and el l ctm)
                      (let [r (.getBoundingClientRect el)
                            pt (.matrixTransform (js/DOMPoint. (or cx (+ (.-left r) (/ (.-width r) 2))) (or cy (+ (.-top r) (/ (.-height r) 2)))) (.inverse ctm))
                            vb (:vb l) fr (:frame l)
                            fx (/ (- (.-x pt) (:x vb)) (:w vb)) fy (/ (- (.-y pt) (:y vb)) (:h vb))
                            k (min 16 (max 1 (* (or (:k (.-current zoom)) 1) f)))
                            w (/ (:w fr) k) hh (/ (:h fr) k)]
                        (set! (.-current zoom) (when-not (= k 1) {:k k :cx (+ (- (.-x pt) (* fx w)) (/ w 2)) :cy (+ (- (.-y pt) (* fy hh)) (/ hh 2))}))
                        (apply!)))))]
    (h/use-effect (fn [] (when (#{"down" "across"} (:direction data)) (set-chosen-dir (:direction data))) nil) [(:direction data)])
    (h/use-effect (fn [] (when (string? (:at data)) (set-at (:at data))) nil) [(:at data)])
    (h/use-effect
     (fn []
       (let [on #(set-full (and (.-current box) (identical? js/document.fullscreenElement (.-current box))))]
         (js/document.addEventListener "fullscreenchange" on)
         #(js/document.removeEventListener "fullscreenchange" on)))
     [])
    (h/use-effect
     (fn []
       (when-let [el (.-current mapbox)]
         (let [ro (js/ResizeObserver. #(set-box-w (.-clientWidth el)))]
           (.observe ro el)
           #(.disconnect ro))))
     [(some? all)])
    ;; The namespaces, asked again every poll-ms while the page is seen; {same} until a file changes.
    (h/use-effect
     (fn []
       (cond
         demo (do (set-all (vec (items (:namespaces demo)))) nil)
         live (let [gone (atom false) timer (atom nil) fp (atom nil)
                    ask (fn ask []
                          (-> (if js/document.hidden (js/Promise.resolve nil)
                                  (-> (api/fetch-json (url-of {:fp @fp}))
                                      (.then (fn [j] (when-not @gone (set-error nil) (when-not (:same j) (reset! fp (:fp j)) (set-all (:namespaces j))))))
                                      (.catch (fn [e] (when-not (or @gone (instance? js/TypeError e)) (set-error (.-message e)))))))
                              (.then (fn [_] (when-not @gone (reset! timer (js/setTimeout ask poll-ms)))))))]
                (ask)
                #(do (reset! gone true) (js/clearTimeout @timer)))))
     [live root server chat (some? demo)])
    (let [view (h/use-memo
                (fn []
                  (when all
                    ;; A prefix with a single bubble opens straight into it.
                    (loop [p at]
                      (let [v (layout all p)]
                        (if (and (= 1 (count (:nodes v))) (:group (first (:nodes v))))
                          (recur (:key (first (:nodes v))))
                          (assoc v :at p))))))
                [all at])
          open-ns (fn [ns v]
                    (set-edge nil) (set-pick ns) (set-var-pick v)
                    (when (and view (not (some #(and (in-node? ns %) (not (:group %))) (:nodes view)))) (set-at (parent ns))))]
      ;; What Claude opens: a namespace, ns/var, or "a -> b".
      (h/use-effect
       (fn []
         (let [o (str/trim (str (:open data)))]
           (when (and (seq o) view)
             (let [arrow (str/split o #"\s*->\s*")]
               (if (= 2 (count arrow))
                 (let [find (fn [k] (or (some #(when (= (:key %) k) %) (:nodes view)) (some #(when (under? k (:key %)) %) (:nodes view))))
                       a (find (first arrow)) b (find (second arrow))]
                   (when (and a b) (set-pick nil) (set-edge {:from a :to b})))
                 (let [slash (str/index-of o "/")]
                   (open-ns (if (and slash (pos? slash)) (subs o 0 slash) o) (when (and slash (pos? slash)) (subs o (inc slash))))))))
           nil))
       [(:open data) (some? view)])
      (h/use-effect
       (fn []
         (set-detail nil)
         (when pick
           (if demo
             (let [d (kget (:vars demo) pick)] (set-detail {:doc (:doc d) :vars (items (:vars d)) :external (items (:external d))}) nil)
             (let [gone (atom false)]
               (-> (api/fetch-json (url-of {:ns pick}))
                   (.then #(when-not @gone (set-detail %)))
                   (.catch #(when-not @gone (set-detail {:vars [] :external [] :doc (.-message %)}))))
               #(reset! gone true)))))
       [pick root])
      (h/use-effect
       (fn []
         (set-src nil)
         (when (and pick var-pick)
           (if demo
             (let [v (some #(when (= (:name %) var-pick) %) (items (:vars (kget (:vars demo) pick))))]
               (when (:code v) (set-src {:code (:code v) :row (or (:row v) 1) :lang "clj"})) nil)
             (let [gone (atom false)]
               (-> (api/fetch-json (url-of {:ns pick :var var-pick}))
                   (.then #(when-not @gone (set-src %)))
                   (.catch (fn [_])))
               #(reset! gone true)))))
       [pick var-pick root])
      (h/use-effect
       (fn []
         (set-used nil)
         (when edge
           (let [{:keys [from to]} edge]
             (if demo
               (do (set-used (vals (reduce (fn [m [f by]]
                                             (let [f (if (keyword? f) (subs (str f) 1) f)]
                                               (if-not (in-node? f from) m
                                                       (reduce (fn [m [t names]]
                                                                 (let [t (if (keyword? t) (subs (str t) 1) t)]
                                                                   (if-not (in-node? t to) m
                                                                           (reduce (fn [m nm] (-> m (update-in [(str t "/" nm) :count] (fnil inc 0))
                                                                                                  (assoc-in [(str t "/" nm) :ns] t) (assoc-in [(str t "/" nm) :name] nm)
                                                                                                  (update-in [(str t "/" nm) :from] (fnil conj []) f)))
                                                                                   m (items names)))))
                                                               m by))))
                                           {} (:uses demo))))
                   nil)
               (let [gone (atom false)]
                 (-> (api/fetch-json (url-of {:from (:key from) :fx (if (:exact from) 1 0) :to (:key to) :tx (if (:exact to) 1 0)}))
                     (.then #(when-not @gone (set-used (items (:used %)))))
                     (.catch (fn [_])))
                 #(reset! gone true))))))
       [(some-> edge :from :key) (some-> edge :to :key) root])
      (let [dir (or chosen-dir (when view (best-dir (:layers view) (:rows view)
                                                    (if full (max 1 (/ (* js/innerWidth 0.68) (max 1 (- js/innerHeight 60)))) 0.8))))
            [nodes {:keys [w h]}] (if view (place-nodes (:nodes view) (:layers view) (:rows view) dir) [[] {:w 0 :h 0}])]
        (h/use-layout-effect
         (fn []
           (let [b (some-> (.-current drawn) .getBBox)]
             (set-bounds (when (and b (pos? (.-width b))) {:x (- (.-x b) 14) :y (- (.-y b) 14) :w (+ (.-width b) 28) :h (+ (.-height b) 28)}))
             (set! (.-current zoom) nil)
             (set-zoom-k 1))
           nil)
         [view dir full])
        (h/use-effect
         (fn []
           (when-let [el (.-current svg-ref)]
             (let [wheel (fn [e]
                           (when (or (.-ctrlKey e) (.-metaKey e) js/document.fullscreenElement)
                             (.preventDefault e)
                             (let [f (js/Math.exp (* (- (.-deltaY e)) (if (.-ctrlKey e) 0.01 0.002)))]
                               (zoom-at f (.-clientX e) (.-clientY e)))))]
               (.addEventListener el "wheel" wheel #js {:passive false})
               #(.removeEventListener el "wheel" wheel))))
         [(some? view)])
        (cond
          error [:p.f-note.cl-err error]
          (not view) [:p.f-note (if (or live demo) "Reading the project with clj-kondo…" "Open this chat to see its namespaces.")]
          :else
          (let [by-id (into {} (map (juxt id-of identity)) nodes)
                marks (items (:marks data))
                mark-on (fn [n] (some #(when (and (string? (:ns %)) (in-node? (:ns %) n)) %) marks))
                pick-node (when pick (some #(when (in-node? pick %) %) nodes))
                focus (if hover (by-id hover) pick-node)
                on-edge? (fn [e] (and edge (= (:from e) (id-of (:from edge))) (= (:to e) (id-of (:to edge)))))
                lit? (fn [n] (cond edge (or (= (id-of n) (id-of (:from edge))) (= (id-of n) (id-of (:to edge))))
                                   (not focus) true
                                   :else (let [f (id-of focus)]
                                           (or (= (id-of n) f) (some #(or (and (= (:from %) f) (= (:to %) (id-of n))) (and (= (:to %) f) (= (:from %) (id-of n)))) (:edges view))))))
                frame (or bounds {:x 0 :y 0 :w w :h h})
                vb (view-of frame (.-current zoom))
                _ (set! (.-current latest) {:frame frame :vb vb})
                k zoom-k
                crumbs (if (seq (:at view)) (str/split (:at view) #"\.") [])
                project (or (last (filter seq (str/split root #"/"))) "project")
                reset-to (fn [p] (set-at p) (set-pick nil) (set-edge nil))]
            [:div.cl-form {:class (when full "full") :ref #(set! (.-current box) %)}
             [:div.cl-head
              [:button.cl-crumb {:type "button" :on-click #(reset-to "")} (if demo "demo" project)]
              (for [[i c] (map-indexed vector crumbs)]
                ^{:key i} [:<> [:span.cl-dot "."] [:button.cl-crumb {:type "button" :on-click #(reset-to (str/join "." (take (inc i) crumbs)))} c]])
              [:span.cl-count (str (count nodes) " bubbles · " (count (filter #(under? (:name %) (:at view)) all)) " namespaces")]
              [:button.cl-full {:type "button" :on-click #(set-chosen-dir (if (= dir "down") "across" "down"))
                                :title (str (if chosen-dir "Set by hand" "Picked by the map's shape") "; click to lay it out " (if (= dir "down") "left to right" "top to bottom"))}
               (if (= dir "down") "↓ down" "→ across")]
              (when chosen-dir [:button.cl-full {:type "button" :on-click #(set-chosen-dir nil) :title "Pick the direction by the map's shape again"} "auto"])
              [:span.cl-zoom
               [:button.cl-full {:type "button" :on-click #(zoom-at (/ 1 1.4) nil nil) :disabled (= k 1) :title "Zoom out"} "−"]
               [:button.cl-full {:type "button" :on-click #(zoom-at 1.4 nil nil) :title "Zoom in (or pinch, Ctrl + scroll)"} "+"]
               (when (> k 1) [:button.cl-full {:type "button" :on-click #(do (set! (.-current zoom) nil) (apply!)) :title "The whole map"} "fit"])]
              [:button.cl-full {:type "button" :title (if full "Leave full screen (Esc)" "Full screen")
                                :on-click #(-> (if full (js/document.exitFullscreen) (.requestFullscreen (.-current box))) (.catch (fn [_])))}
               (if full "✕ exit" "⤢ full screen")]]
             [:div.cl-mapbox {:ref #(set! (.-current mapbox) %)}
              [:svg.cl-map {:class (when (> k 1) "zoomed") :ref #(set! (.-current svg-ref) %)
                            :viewBox (str (:x vb) " " (:y vb) " " (:w vb) " " (:h vb))
                            :style (when (and (not full) (pos? box-w)) {:height (* (:h frame) (min 1.1 (/ box-w (:w frame))))})
                            :on-pointer-down (fn [e]
                                               (let [ctm (some-> (.-current svg-ref) .getScreenCTM)
                                                     vb (:vb (.-current latest))]
                                                 (when (and (.-current zoom) ctm)
                                                   (set! (.-current pan) {:x (.-clientX e) :y (.-clientY e) :cx (+ (:x vb) (/ (:w vb) 2)) :cy (+ (:y vb) (/ (:h vb) 2)) :s (/ 1 (.-a ctm))})
                                                   (set! (.-current dragged) false))))
                            :on-pointer-move (fn [e]
                                               (when-let [p (.-current pan)]
                                                 (let [dx (- (.-clientX e) (:x p)) dy (- (.-clientY e) (:y p))]
                                                   (when (or (.-current dragged) (>= (js/Math.hypot dx dy) 4))
                                                     (when-not (.-current dragged)
                                                       (set! (.-current dragged) true)
                                                       (some-> (.-current svg-ref) (.setPointerCapture (.-pointerId e))))
                                                     (when (.-current zoom)
                                                       (set! (.-current zoom) (assoc (.-current zoom) :cx (- (:cx p) (* dx (:s p))) :cy (- (:cy p) (* dy (:s p)))))
                                                       (apply!))))))
                            :on-pointer-up #(set! (.-current pan) nil)
                            :on-click-capture #(when (.-current dragged) (.stopPropagation %) (set! (.-current dragged) false))}
               [:defs
                (for [c ["muted" "blue" "green" "violet" "red"]]
                  ^{:key c} [:marker {:id (str "cl-a-" c) :viewBox "0 0 10 10" :refX 8 :refY 5 :markerUnits "userSpaceOnUse" :markerWidth 11 :markerHeight 11 :orient "auto"}
                             [:path {:d "M0 0L10 5L0 10z" :fill (str "var(--" c ")")}]])]
               [:g {:ref #(set! (.-current drawn) %)}
                [:g.cl-edges
                 (for [e (:edges view)
                       :let [a (by-id (:from e)) b (by-id (:to e))]
                       :when (and a b)
                       :let [f (when (and focus (not edge)) (id-of focus))
                             tone (cond (on-edge? e) "violet" (and f (= (:from e) f)) "blue" (and f (= (:to e) f)) "green" (:back e) "red" :else "muted")
                             dim (or (and edge (not (on-edge? e))) (and f (not= (:from e) f) (not= (:to e) f)))
                             d (edge-path a b (:back e) dir)]]
                   ^{:key (str (:from e) ">" (:to e))}
                   [:g.cl-edge {:class (when dim "dim") :on-click #(do (set-pick nil) (set-var-pick nil) (set-edge (when-not (on-edge? e) {:from a :to b})))}
                    [:path.hit {:d d}]
                    [:path.line {:d d :class tone :stroke-width (+ 1.4 (* 0.9 (js/Math.log2 (inc (:pairs e)))))
                                 :stroke-dasharray (when (or (:back e) (= tone "green")) "6 5") :marker-end (str "url(#cl-a-" tone ")")}]
                    [:title (str (let [s (strip (:key a) (:at view))] (if (seq s) s (:label a))) " → " (let [s (strip (:key b) (:at view))] (if (seq s) s (:label b)))
                                 ": " (:pairs e) " require" (when (> (:pairs e) 1) "s") (when (:back e) " (closes a circle)") ". Click for the functions it uses.")]])]
                (for [n nodes
                      :let [m (mark-on n) on (and pick-node (= (id-of pick-node) (id-of n)))]]
                  ^{:key (id-of n)}
                  [:g.cl-node {:class [(when-not (lit? n) "dim") (when on "on")]
                               :on-mouse-enter #(set-hover (id-of n)) :on-mouse-leave #(set-hover nil)
                               :on-click #(do (set-edge nil)
                                              (cond (:group n) (do (set-at (:key n)) (set-pick nil) (set-var-pick nil))
                                                    (= pick (:key n)) (do (set-pick nil) (set-var-pick nil))
                                                    :else (do (set-pick (:key n)) (set-var-pick nil))))}
                   [:circle.body {:cx (:x n) :cy (:y n) :r (:r n) :style {:stroke (:color n) :fill (str "color-mix(in srgb, " (:color n) " 18%, transparent)")}}]
                   (when (:group n) [:circle.ring {:cx (:x n) :cy (:y n) :r (+ (:r n) 4) :style {:stroke (:color n)}}])
                   [:text.num {:x (:x n) :y (+ (:y n) 4)} (if (:group n) (:count n) (:vars n))]
                   [:text.lbl {:x (:x n) :y (+ (:y n) (:r n) (if (:group n) 19 15))} (str (if (:exact n) (str (:label n) " (itself)") (:label n)) (when (:group n) " ›"))]
                   (when m [:circle {:cx (+ (:x n) (* 0.72 (:r n))) :cy (- (:y n) (* 0.72 (:r n))) :r 5 :style {:fill (get mark-colour (:color m) "var(--amber)")}}
                            [:title (or (:note m) (:ns m))]])
                   [:title (str (:key n) (if (:group n) (str ": " (:count n) " namespaces, click to open") (str ": " (:vars n) " vars")))]])]]
              [:div.cl-legend
               [:span [:b.blue "──"] " requires"] [:span [:b.green "- -"] " required by"] [:span [:b.red "- -"] " closes a circle"]
               [:span "◎ opens · number = namespaces / vars"]]]
             [:div.cl-panel
              (cond
                edge [edge-panel edge used open-ns]
                pick [ns-panel pick detail all var-pick set-var-pick src (filterv #(= (:ns %) pick) marks) #(open-ns % nil)]
                :else [:p.f-note "Click a bubble to open it or see its vars; click an arrow for the functions it uses."])]]))))))
