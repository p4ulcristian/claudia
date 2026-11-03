(ns claudia.web.views.clojure
  "The clojure form: a project's namespaces as chalk bubbles in layers (entry points first, leaves
  last), arrows for :require (one going back closes a circle: red). A prefix with namespaces under
  it is one bubble that opens on click. A namespace lists its vars below the map, a var opens its
  source, an arrow lists the functions that cross it. Read live through the runner (clj-kondo)."
  (:require [clojure.string :as str]
            [claudia.web.fullscreen :as fs]
            [claudia.web.hooks :as hooks]
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
(def ^:private items code/items)
(def ^:private mark-colour code/mark-colour)

(defn kget
  "A map value by a string key, whether the map came from JSON (keyword keys) or from EDN."
  [m k]
  (when (map? m) (or (get m k) (get m (keyword k)))))

(defn under? [n p] (or (not (seq p)) (= n p) (str/starts-with? n (str p "."))))
(defn parent [n] (if (str/includes? n ".") (subs n 0 (str/last-index-of n ".")) ""))
(defn in-node? [n node] (if (:exact node) (= n (:key node)) (under? n (:key node))))
(defn id-of [n] (if (:exact n) (str "=" (:key n)) (:key n)))
(defn strip [n p] (if (and (seq p) (str/starts-with? n (str p "."))) (subs n (inc (count p))) n))

;; ---- the map's layout: pure, from the namespaces ----

(defn node-id
  "The bubble namespace nm falls in on the map at prefix at: \"=at\" for at itself, else at's next
  segment on the way to nm."
  [at nm]
  (if (= nm at)
    (str "=" at)
    (let [seg (first (str/split (subs nm (if (seq at) (inc (count at)) 0)) #"\."))]
      (if (seq at) (str at "." seg) seg))))

(defn- new-bubble [at id]
  (let [exact (str/starts-with? id "=")
        k (if exact at id)]
    {:key k :label (last (str/split (subs k (if (or exact (not (seq at))) 0 (inc (count at)))) #"\."))
     :exact exact :group false :count 0 :vars 0}))

(defn- add-member [at by-key n]
  (let [id (node-id at (:name n))
        node (or (get by-key id) (new-bubble at id))]
    (assoc by-key id (cond-> (-> node (update :count inc) (update :vars + (or (:vars n) 0)))
                       (and (not (:exact node)) (not= (:name n) (:key node))) (assoc :group true)))))

(defn bubbles
  "The map's bubbles by id: each counts its namespaces and vars; colours go round in id order."
  [members at]
  (let [by-key (reduce #(add-member at %1 %2) {} members)]
    (reduce (fn [m [i id]] (assoc-in m [id :color] (nth colours (mod i (count colours)))))
            by-key (map-indexed vector (sort (keys by-key))))))

(defn require-pairs
  "{[from to] n}: how many requires go from one bubble to another on the map at prefix at."
  [members at by-key]
  (reduce (fn [m n]
            (reduce (fn [m rq]
                      (let [a (node-id at (:name n)) b (node-id at rq)]
                        (if (and (under? rq at) (not= a b) (contains? by-key b)) (update m [a b] (fnil inc 0)) m)))
                    m (items (:requires n))))
          {} members))

(defn- visit
  "A depth-first walk from v: [state back] after it; state 1 is on the path, 2 done."
  [out [state back] v]
  (let [[state back] (reduce (fn [[state back] w]
                               (case (get state w)
                                 1 [state (conj back [v w])]
                                 nil (visit out [state back] w)
                                 [state back]))
                             [(assoc state v 1) back] (get out v))]
    [(assoc state v 2) back]))

(defn back-edges
  "The arrows that close a circle (#{[from to]}), walking from the bubbles nobody requires first."
  [ids out has-in]
  (second (reduce (fn [[state :as acc] id] (if (contains? state id) acc (visit out acc id)))
                  [{} #{}] (concat (remove has-in ids) ids))))

(defn layer-of
  "Each bubble's layer: one below every bubble that requires it (arrows closing a circle left out)."
  [ids edges back]
  (loop [layer (zipmap ids (repeat 0)) pass 0]
    (let [stepped (reduce (fn [l [a b]] (if (or (back [a b]) (>= (l b) (inc (l a)))) l (assoc l b (inc (l a)))))
                          layer edges)]
      (if (or (= stepped layer) (> pass (count ids))) stepped (recur stepped (inc pass))))))

(defn- slots [cols] (into {} (for [c cols [i id] (map-indexed vector c)] [id (- i (/ (dec (count c)) 2))])))

(defn- near-slots [edges pos id dir]
  (for [[a b] edges :when (if (= dir 1) (= b id) (= a id))] (pos (if (= dir 1) a b))))

(defn- sort-layer [edges dir cols li]
  (let [pos (slots cols)
        c (nth cols li)
        at-pos (into {} (for [id c :let [near (near-slots edges pos id dir)]]
                          [id (if (seq near) (/ (reduce + near) (count near)) (pos id))]))]
    (assoc cols li (vec (sort-by at-pos c)))))

(defn order-columns
  "Four sweeps, down then up then down then up: each layer sorted by the mean slot of its
  neighbours in the layer before, so arrows cross less."
  [cols edges]
  (let [n (count cols)]
    (reduce (fn [cs s]
              (let [dir (if (even? s) 1 -1)]
                (reduce #(sort-layer edges dir %1 %2) cs (if (= dir 1) (range n) (range (dec n) -1 -1)))))
            cols (range 4))))

(defn- bubble-r [n] (+ 11 (min 20 (* 1.7 (js/Math.sqrt (if (:group n) (* 12 (:count n)) (:vars n)))))))

(defn layout
  "The bubbles and arrows of the map at prefix at, laid out in layers."
  [all at]
  (let [members (filter #(under? (:name %) at) all)
        by-key (bubbles members at)
        ids (vec (sort (keys by-key)))
        pairs (require-pairs members at by-key)
        edges (keys pairs)
        out (reduce (fn [m [a b]] (update m a conj b)) (zipmap ids (repeat [])) edges)
        back (back-edges ids out (set (map second edges)))
        layer (layer-of ids edges back)
        n-layers (if (seq ids) (inc (reduce max 0 (vals layer))) 0)
        cols (order-columns (vec (for [li (range n-layers)] (vec (filter #(= li (layer %)) ids)))) edges)
        rows (max 1 (reduce max 0 (map count cols)))]
    {:nodes (vec (for [[li c] (map-indexed vector cols) [i id] (map-indexed vector c)
                       :let [n (get by-key id)]]
                   (assoc n :layer li :slot (+ (/ (- rows (count c)) 2) i) :r (bubble-r n))))
     :edges (vec (for [[[a b] c] pairs] {:from a :to b :pairs c :back (contains? back [a b])}))
     :layers n-layers :rows rows}))

(defn view-at
  "The map at prefix at; a prefix with a single bubble opens straight into it."
  [all at]
  (loop [p at]
    (let [v (layout all p)]
      (if (and (= 1 (count (:nodes v))) (:group (first (:nodes v))))
        (recur (:key (first (:nodes v))))
        (assoc v :at p)))))

(defn place-nodes [nodes layers rows dir]
  [(mapv (fn [n] (if (= dir "across")
                   (assoc n :x (+ pad 50 (* (:layer n) col-w)) :y (+ pad (* (:slot n) row-h) (/ row-h 2)))
                   (assoc n :x (+ pad 60 (* (:slot n) node-w)) :y (+ pad 40 (* (:layer n) layer-h)))))
         nodes)
   (if (= dir "across")
     {:w (+ (* pad 2) 100 (* (dec layers) col-w)) :h (+ (* pad 2) (* rows row-h) 10)}
     {:w (+ (* pad 2) 120 (* (dec rows) node-w)) :h (+ (* pad 2) 100 (* (dec layers) layer-h))})])

(defn best-dir
  "The direction whose map is closest in shape to its space (the half screen is narrow, about 0.8)."
  [layers rows aim]
  (let [ratio (fn [d] (let [{:keys [w h]} (second (place-nodes [] layers rows d))] (js/Math.abs (js/Math.log (/ (/ w h) aim)))))]
    (if (< (ratio "down") (ratio "across")) "down" "across")))

(defn aim
  "The shape (width / height) the map aims for: the full screen's, else the half screen's."
  [full inner-w inner-h]
  (if full (max 1 (/ (* inner-w 0.68) (max 1 (- inner-h 60)))) 0.8))

(defn- forward-path [a b dir]
  (if (= dir "down")
    (let [y1 (+ (:y a) (:r a)) y2 (- (:y b) (:r b) 6) my (/ (+ y1 y2) 2)]
      (str "M" (:x a) " " y1 "C" (:x a) " " my " " (:x b) " " my " " (:x b) " " y2))
    (let [x1 (+ (:x a) (:r a)) x2 (- (:x b) (:r b) 6) mx (/ (+ x1 x2) 2)]
      (str "M" x1 " " (:y a) "C" mx " " (:y a) " " mx " " (:y b) " " x2 " " (:y b)))))

(defn- loop-path [a b dir]
  (if (= dir "down")
    (let [lift (+ 40 (* 0.15 (js/Math.abs (- (:y a) (:y b)))) (* 0.1 (js/Math.abs (- (:x a) (:x b)))))
          x1 (- (:x a) (:r a)) x2 (- (:x b) (:r b) 6) y2 (+ (:y b) (if (= (:y a) (:y b)) 4 0)) mx (- (min x1 x2) lift)]
      (str "M" x1 " " (:y a) "C" mx " " (:y a) " " mx " " y2 " " x2 " " y2))
    (let [lift (+ 40 (* 0.15 (js/Math.abs (- (:x a) (:x b)))) (* 0.1 (js/Math.abs (- (:y a) (:y b)))))
          x1 (:x a) y1 (- (:y a) (:r a)) x2 (+ (:x b) (if (= (:x a) (:x b)) 4 0)) y2 (- (:y b) (:r b) 6) my (- (min y1 y2) lift)]
      (str "M" x1 " " y1 "C" x1 " " my " " x2 " " my " " x2 " " y2))))

(defn edge-path
  "An arrow's path: a curve to a later layer, else a loop around the side."
  [a b back dir]
  (if (and (not back) (> (:layer b) (:layer a))) (forward-path a b dir) (loop-path a b dir)))

;; ---- what is lit, what Claude opens ----

(defn on-edge? [edge e] (and edge (= (:from e) (id-of (:from edge))) (= (:to e) (id-of (:to edge)))))

(defn lit?
  "A bubble stays bright when it is an end of the open arrow, or the focus or next to it."
  [edges edge focus n]
  (cond edge (or (= (id-of n) (id-of (:from edge))) (= (id-of n) (id-of (:to edge))))
        (not focus) true
        :else (let [f (id-of focus)]
                (or (= (id-of n) f)
                    (some #(or (and (= (:from %) f) (= (:to %) (id-of n))) (and (= (:to %) f) (= (:from %) (id-of n)))) edges)))))

(defn edge-look
  "An arrow's colour and whether it is dimmed, from the open arrow and the focused bubble."
  [e edge focus]
  (let [on (on-edge? edge e)
        f (when (and focus (not edge)) (id-of focus))]
    {:on on
     :tone (cond on "violet" (and f (= (:from e) f)) "blue" (and f (= (:to e) f)) "green" (:back e) "red" :else "muted")
     :dim (or (and edge (not on)) (and f (not= (:from e) f) (not= (:to e) f)))}))

(defn- short-label [n at] (let [s (strip (:key n) at)] (if (seq s) s (:label n))))

(defn edge-title [a b e at]
  (str (short-label a at) " → " (short-label b at)
       ": " (:pairs e) " require" (when (> (:pairs e) 1) "s") (when (:back e) " (closes a circle)") ". Click for the functions it uses."))

(defn find-node
  "The bubble with key k, else the first one k is under."
  [nodes k]
  (or (some #(when (= (:key %) k) %) nodes) (some #(when (under? k (:key %)) %) nodes)))

(defn open-target
  "What Claude's open names: {:edge {:from :to}} for \"a -> b\" (nil when an end is not on the
  map), else {:ns :var} for a namespace or ns/var."
  [o nodes]
  (let [arrow (str/split o #"\s*->\s*")]
    (if (= 2 (count arrow))
      (let [a (find-node nodes (first arrow)) b (find-node nodes (second arrow))]
        (when (and a b) {:edge {:from a :to b}}))
      (let [slash (str/index-of o "/")
            cut (and slash (pos? slash))]
        {:ns (if cut (subs o 0 slash) o) :var (when cut (subs o (inc slash)))}))))

(defn shown-on?
  "Whether namespace nsp has a bubble of its own (not a group) among nodes."
  [nodes nsp]
  (some #(and (in-node? nsp %) (not (:group %))) nodes))

(defn crumb-paths
  "The prefixes the breadcrumbs go back to: \"a\", \"a.b\", … for at \"a.b.c\"."
  [at]
  (let [crumbs (if (seq at) (str/split at #"\.") [])]
    (map-indexed (fn [i c] {:label c :path (str/join "." (take (inc i) crumbs))}) crumbs)))

(defn from-label
  "A namespace an arrow's function is used from, short when it is under the arrow's start."
  [from f]
  (let [s (strip f (if (:exact from) (parent (:key from)) (:key from)))] (if (seq s) s f)))

;; ---- the demo's answers, as the runner would give them ----

(defn demo-detail [demo pick]
  (let [d (kget (:vars demo) pick)] {:doc (:doc d) :vars (items (:vars d)) :external (items (:external d))}))

(defn demo-source [demo pick var-name]
  (let [v (some #(when (= (:name %) var-name) %) (items (:vars (kget (:vars demo) pick))))]
    (when (:code v) {:code (:code v) :row (or (:row v) 1) :lang "clj"})))

(defn- key-name [k] (if (keyword? k) (subs (str k) 1) k))

(defn- add-use [m f t nm]
  (let [id (str t "/" nm)]
    (-> m (update-in [id :count] (fnil inc 0)) (assoc-in [id :ns] t) (assoc-in [id :name] nm)
        (update-in [id :from] (fnil conj []) f))))

(defn demo-used
  "The functions that cross the arrow from -> to, from the demo's {from {to [names]}}."
  [uses from to]
  (vals (reduce (fn [m [f by]]
                  (let [f (key-name f)]
                    (if-not (in-node? f from)
                      m
                      (reduce (fn [m [t names]]
                                (let [t (key-name t)]
                                  (if-not (in-node? t to) m (reduce #(add-use %1 f t %2) m (items names)))))
                              m by))))
                {} uses)))

;; ---- the zoom: pure ----

(defn view-of
  "The window on the map for a zoom: k times closer around (cx, cy), kept inside the map."
  [frame z]
  (let [k (or (:k z) 1) w (/ (:w frame) k) hh (/ (:h frame) k)
        clamp (fn [c lo size win] (min (- (+ lo size) (/ win 2)) (max (+ lo (/ win 2)) c)))
        cx (clamp (or (:cx z) (+ (:x frame) (/ (:w frame) 2))) (:x frame) (:w frame) w)
        cy (clamp (or (:cy z) (+ (:y frame) (/ (:h frame) 2))) (:y frame) (:h frame) hh)]
    {:x (- cx (/ w 2)) :y (- cy (/ hh 2)) :w w :h hh}))

(defn viewbox [vb] (str (:x vb) " " (:y vb) " " (:w vb) " " (:h vb)))

(defn zoom-around
  "The zoom after zooming z by f at the map's point (px, py), that point kept still on the screen;
  nil for the whole map. vb: the window shown, fr: the whole map."
  [z f px py vb fr]
  (let [fx (/ (- px (:x vb)) (:w vb)) fy (/ (- py (:y vb)) (:h vb))
        k (min 16 (max 1 (* (or (:k z) 1) f)))
        w (/ (:w fr) k) hh (/ (:h fr) k)]
    (when-not (= k 1) {:k k :cx (+ (- px (* fx w)) (/ w 2)) :cy (+ (- py (* fy hh)) (/ hh 2))})))

(defn pan-start
  "Where a drag starts: the pointer, the window's middle, and map units per screen px (1 / a)."
  [vb a x y]
  {:x x :y y :cx (+ (:x vb) (/ (:w vb) 2)) :cy (+ (:y vb) (/ (:h vb) 2)) :s (/ 1 a)})

(defn panned [z p dx dy] (assoc z :cx (- (:cx p) (* dx (:s p))) :cy (- (:cy p) (* dy (:s p)))))

(defn wheel-factor [dy ctrl] (js/Math.exp (* (- dy) (if ctrl 0.01 0.002))))

(defn bounds-of
  "The drawing's box with a margin; nil while it has no width."
  [x y w h]
  (when (pos? w) {:x (- x 14) :y (- y 14) :w (+ w 28) :h (+ h 28)}))

;; ---- the zoom on the element ----

(defn- apply-zoom!
  "Puts the zoom on the svg's viewBox in the next frame; chalk masks off until it rests."
  [{:keys [zoom set-k latest frame-req rest-timer svg]}]
  (js/cancelAnimationFrame (.-current frame-req))
  (set! (.-current frame-req)
        (js/requestAnimationFrame
         (fn []
           (let [el (.-current svg) l (.-current latest)]
             (when (and el l)
               (let [vb (view-of (:frame l) (.-current zoom))]
                 (set! (.-current latest) (assoc l :vb vb))
                 (.setAttribute el "viewBox" (viewbox vb))
                 (.add (.-classList el) "moving")
                 (js/clearTimeout (.-current rest-timer))
                 (set! (.-current rest-timer)
                       (js/setTimeout (fn [] (.remove (.-classList el) "moving") (set-k (or (:k (.-current zoom)) 1))) 160)))))))))

(defn- zoom-at!
  "Zoom by f at a point of the screen (the map's middle when none), keeping that point still."
  [{:keys [svg latest zoom apply!]} f cx cy]
  (let [el (.-current svg) l (.-current latest) ctm (some-> el .getScreenCTM)]
    (when (and el l ctm)
      (let [r (.getBoundingClientRect el)
            pt (.matrixTransform (js/DOMPoint. (or cx (+ (.-left r) (/ (.-width r) 2))) (or cy (+ (.-top r) (/ (.-height r) 2))))
                                 (.inverse ctm))]
        (set! (.-current zoom) (zoom-around (.-current zoom) f (.-x pt) (.-y pt) (:vb l) (:frame l)))
        (apply!)))))

(defn- use-zoom
  "The zoom lives in a ref and moves the viewBox straight on the element (no re-render per wheel
  step); :k follows once it rests, for the buttons."
  []
  (let [zoom (h/use-ref nil) ; nil: the whole map; else {:k :cx :cy}
        [zoom-k set-zoom-k] (h/use-state 1)
        z {:zoom zoom :k zoom-k :set-k set-zoom-k
           :latest (h/use-ref nil) ; {:frame :vb}
           :frame-req (h/use-ref 0) :rest-timer (h/use-ref nil) :dragged (h/use-ref false)
           :svg (h/use-ref nil) :pan (h/use-ref nil)}
        z (assoc z :apply! #(apply-zoom! z))]
    (assoc z :zoom-at #(zoom-at! z %1 %2 %3))))

(defn- pointer-down! [{:keys [svg latest zoom pan dragged]} e]
  (let [ctm (some-> (.-current svg) .getScreenCTM)
        vb (:vb (.-current latest))]
    (when (and (.-current zoom) ctm)
      (set! (.-current pan) (pan-start vb (.-a ctm) (.-clientX e) (.-clientY e)))
      (set! (.-current dragged) false))))

(defn- pointer-move! [{:keys [svg zoom pan dragged apply!]} e]
  (when-let [p (.-current pan)]
    (let [dx (- (.-clientX e) (:x p)) dy (- (.-clientY e) (:y p))]
      (when (or (.-current dragged) (>= (js/Math.hypot dx dy) 4))
        (when-not (.-current dragged)
          (set! (.-current dragged) true)
          (some-> (.-current svg) (.setPointerCapture (.-pointerId e))))
        (when (.-current zoom)
          (set! (.-current zoom) (panned (.-current zoom) p dx dy))
          (apply!))))))

(defn- use-wheel-zoom
  "Ctrl/Cmd + wheel (a pinch), or any wheel in full screen, zooms at the pointer."
  [{:keys [svg zoom-at]} deps]
  (h/use-effect
   (fn []
     (when-let [el (.-current svg)]
       (let [wheel (fn [e]
                     (when (or (.-ctrlKey e) (.-metaKey e) js/document.fullscreenElement)
                       (.preventDefault e)
                       (zoom-at (wheel-factor (.-deltaY e) (.-ctrlKey e)) (.-clientX e) (.-clientY e))))]
         (.addEventListener el "wheel" wheel #js {:passive false})
         #(.removeEventListener el "wheel" wheel))))
   deps))

(defn- use-fit
  "After a new map (or direction, or full screen) is drawn: its bounds measured, the zoom reset."
  [{:keys [zoom set-k]} drawn set-bounds deps]
  (h/use-layout-effect
   (fn []
     (let [b (some-> (.-current drawn) .getBBox)]
       (set-bounds (when b (bounds-of (.-x b) (.-y b) (.-width b) (.-height b))))
       (set! (.-current zoom) nil)
       (set-k 1))
     nil)
   deps))

;; ---- reading: the namespaces, a namespace, a var, an arrow ----

(defn- fetch-into!
  "Reads url into set-to (a failure to on-error) unless the effect is gone first; returns the cleanup."
  [url set-to on-error]
  (let [gone (atom false)]
    (-> (api/fetch-json url)
        (.then #(when-not @gone (set-to %)))
        (.catch #(when-not @gone (on-error %))))
    #(reset! gone true)))

(defn- feed-of
  "Where the form reads from: {:demo :root :live :url-of}."
  [data server chat]
  (let [demo (when (map? (:demo data)) (:demo data))
        root (if (string? (:root data)) (:root data) "")]
    {:demo demo :root root :live (and (not demo) (seq root) chat)
     :url-of (fn [q] (api/url server (str "chats/" (js/encodeURIComponent chat) "/clojure") (merge {:root root} q)))}))

(defn- use-namespaces
  "[all error]: the namespaces, asked again every poll-ms while the page is seen ({same} until a
  file changes); the demo's at once."
  [{:keys [demo root live url-of]} server chat]
  (let [[all set-all] (h/use-state nil)
        error (hooks/use-poll {:on? live :ms poll-ms :url-of #(url-of {:fp %}) :on-answer #(set-all (:namespaces %))}
                              [live root server chat (some? demo)])]
    (h/use-effect (fn [] (when demo (set-all (vec (items (:namespaces demo))))) nil)
                  [live root server chat (some? demo)])
    [all error]))

(defn- use-detail [{:keys [demo root url-of]} pick]
  (let [[detail set-detail] (h/use-state nil)]
    (h/use-effect
     (fn []
       (set-detail nil)
       (when pick
         (if demo
           (do (set-detail (demo-detail demo pick)) nil)
           (fetch-into! (url-of {:ns pick}) set-detail #(set-detail {:vars [] :external [] :doc (.-message %)})))))
     [pick root])
    detail))

(defn- use-source [{:keys [demo root url-of]} pick var-pick]
  (let [[src set-src] (h/use-state nil)]
    (h/use-effect
     (fn []
       (set-src nil)
       (when (and pick var-pick)
         (if demo
           (do (when-let [s (demo-source demo pick var-pick)] (set-src s)) nil)
           (fetch-into! (url-of {:ns pick :var var-pick}) set-src (fn [_])))))
     [pick var-pick root])
    src))

(defn- use-used [{:keys [demo root url-of]} edge]
  (let [[used set-used] (h/use-state nil)]
    (h/use-effect
     (fn []
       (set-used nil)
       (when edge
         (let [{:keys [from to]} edge]
           (if demo
             (do (set-used (demo-used (:uses demo) from to)) nil)
             (fetch-into! (url-of {:from (:key from) :fx (if (:exact from) 1 0) :to (:key to) :tx (if (:exact to) 1 0)})
                          #(set-used (items (:used %))) (fn [_]))))))
     [(some-> edge :from :key) (some-> edge :to :key) root])
    used))

;; ---- what is picked ----

(defn- use-picks
  "The prefix shown, the picked namespace, var and arrow, the bubble under the pointer and the
  direction set by hand; at and the direction follow the form's data when Claude changes them."
  [data]
  (let [[at set-at] (h/use-state (if (string? (:at data)) (:at data) ""))
        [pick set-pick] (h/use-state nil)
        [var-pick set-var-pick] (h/use-state nil)
        [edge set-edge] (h/use-state nil)
        [hover set-hover] (h/use-state nil)
        [chosen-dir set-chosen-dir] (h/use-state (when (#{"down" "across"} (:direction data)) (:direction data)))]
    (h/use-effect (fn [] (when (#{"down" "across"} (:direction data)) (set-chosen-dir (:direction data))) nil) [(:direction data)])
    (h/use-effect (fn [] (when (string? (:at data)) (set-at (:at data))) nil) [(:at data)])
    {:at at :set-at set-at :pick pick :set-pick set-pick :var-pick var-pick :set-var-pick set-var-pick
     :edge edge :set-edge set-edge :hover hover :set-hover set-hover :chosen-dir chosen-dir :set-chosen-dir set-chosen-dir}))

(defn- open-ns!
  "Opens namespace nsp (and var v in it); the map goes to its parent when nsp has no bubble here."
  [{:keys [set-edge set-pick set-var-pick set-at]} view nsp v]
  (set-edge nil) (set-pick nsp) (set-var-pick v)
  (when (and view (not (shown-on? (:nodes view) nsp))) (set-at (parent nsp))))

(defn- use-claude-open
  "What Claude opens: a namespace, ns/var, or \"a -> b\"."
  [data view {:keys [set-pick set-edge] :as ui}]
  (h/use-effect
   (fn []
     (let [o (str/trim (str (:open data)))]
       (when (and (seq o) view)
         (let [t (open-target o (:nodes view))]
           (cond (:edge t) (do (set-pick nil) (set-edge (:edge t)))
                 (:ns t) (open-ns! ui view (:ns t) (:var t)))))
       nil))
   [(:open data) (some? view)]))

(defn- use-fullscreen [box]
  (let [[full set-full] (h/use-state false)]
    (h/use-effect (fn [] (fs/on-change! #(set-full (fs/full? (.-current box))))) [])
    full))

;; ---- the panel below the map ----

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

(defn- used-fn-row [from go u]
  [:div.cl-use
   [:button.cl-fn {:type "button" :on-click #(go (:ns u) (:name u))} (:name u)]
   [:span.cl-times (str "×" (:count u))]
   [:span.cl-from (str "← " (str/join ", " (map #(from-label from %) (:from u))))]])

(defn- edge-panel [edge used go]
  (let [froms (count (distinct (mapcat :from used)))
        {:keys [from to]} edge]
    [:<>
     [:h4.cl-title (:key from) " " [:span.cl-arrow "→"] " " (:key to)]
     (cond
       (nil? used) [:p.f-note "Reading…"]
       (empty? used) [:p.f-note "No function calls cross it (the requires are for macros, specs or loading only)."]
       :else
       [:<>
        [:p.f-note (str (count used) " function" (when (> (count used) 1) "s") " used, from " froms " namespace" (when (> froms 1) "s"))]
        (for [[nsp us] (group-by :ns used)]
          ^{:key nsp}
          [:div.cl-group
           [:div.cl-ns nsp]
           (for [u us] (with-meta (used-fn-row from go u) {:key (:name u)}))])])]))

(defn- mark-line [m] [:div.cl-mark {:style {:color (mark-colour m)}} (str "● " (:note m))])

(defn- var-row [v marks var-pick set-var-pick src]
  (let [m (some #(when (= (:var %) (:name v)) %) marks)
        open (= var-pick (:name v))]
    [:div.cl-var {:class [(when (:private v) "priv") (when open "open")]}
     [:button.cl-var-head {:type "button" :on-click #(set-var-pick (when-not open (:name v)))}
      [:span.cl-fn (:name v)]
      (when (not= "fn" (:kind v)) [:span.cl-kind (:kind v)])
      (when (:private v) [:span.cl-kind "private"])
      (for [[i a] (map-indexed vector (items (:args v)))] ^{:key i} [:span.cl-args a])]
     (when (:doc v) [:div.cl-vdoc (if open (:doc v) (first (str/split-lines (:doc v))))])
     (when m (mark-line m))
     (when open (if src [:f> source-view src] [:p.f-note "Reading…"]))]))

(defn- requires-rows [nsp detail all go]
  (let [me (some #(when (= (:name %) nsp) %) all)
        by (map :name (filter #(some #{nsp} (items (:requires %))) all))]
    [:<>
     [:div.cl-reqs
      [:span.k "requires"]
      (for [rq (items (:requires me))] ^{:key rq} [:button.cl-chip {:type "button" :on-click #(go rq)} rq])
      (for [rq (items (:external detail))] ^{:key rq} [:span.cl-chip.ext rq])
      (when (and (empty? (items (:requires me))) (empty? (items (:external detail)))) [:span.f-note "nothing"])]
     [:div.cl-reqs
      [:span.k "required by"]
      (for [rq by] ^{:key rq} [:button.cl-chip {:type "button" :on-click #(go rq)} rq])
      (when (empty? by) [:span.f-note "nobody"])]]))

(defn- ns-panel [nsp detail all var-pick set-var-pick src marks go]
  [:<>
   [:h4.cl-title nsp]
   (when (:doc detail) [:p.cl-doc (:doc detail)])
   (requires-rows nsp detail all go)
   (if-not detail
     [:p.f-note "Reading…"]
     (for [v (items (:vars detail))]
       (with-meta (var-row v marks var-pick set-var-pick src) {:key (:name v)})))
   (for [m marks :when (not (:var m))]
     (with-meta (mark-line m) {:key (:id m)}))])

;; ---- the map ----

(defn- head-view [{:keys [demo root]} {:keys [set-at set-pick set-edge chosen-dir set-chosen-dir]} view all nodes dir z full box]
  (let [k (:k z)
        reset-to (fn [p] (set-at p) (set-pick nil) (set-edge nil))]
    [:div.cl-head
     [:button.cl-crumb {:type "button" :on-click #(reset-to "")} (if demo "demo" (code/project-name root))]
     (for [[i c] (map-indexed vector (crumb-paths (:at view)))]
       ^{:key i} [:<> [:span.cl-dot "."] [:button.cl-crumb {:type "button" :on-click #(reset-to (:path c))} (:label c)]])
     [:span.cl-count (str (count nodes) " bubbles · " (count (filter #(under? (:name %) (:at view)) all)) " namespaces")]
     [:button.cl-full {:type "button" :on-click #(set-chosen-dir (if (= dir "down") "across" "down"))
                       :title (str (if chosen-dir "Set by hand" "Picked by the map's shape") "; click to lay it out " (if (= dir "down") "left to right" "top to bottom"))}
      (if (= dir "down") "↓ down" "→ across")]
     (when chosen-dir [:button.cl-full {:type "button" :on-click #(set-chosen-dir nil) :title "Pick the direction by the map's shape again"} "auto"])
     [:span.cl-zoom
      [:button.cl-full {:type "button" :on-click #((:zoom-at z) (/ 1 1.4) nil nil) :disabled (= k 1) :title "Zoom out"} "−"]
      [:button.cl-full {:type "button" :on-click #((:zoom-at z) 1.4 nil nil) :title "Zoom in (or pinch, Ctrl + scroll)"} "+"]
      (when (> k 1) [:button.cl-full {:type "button" :on-click #(do (set! (.-current (:zoom z)) nil) ((:apply! z))) :title "The whole map"} "fit"])]
     [:button.cl-full {:type "button" :title (if full "Leave full screen (Esc)" "Full screen")
                       :on-click #(fs/toggle! (.-current box))}
      (if full "✕ exit" "⤢ full screen")]]))

(defn- arrow-defs []
  [:defs
   (for [c ["muted" "blue" "green" "violet" "red"]]
     ^{:key c} [:marker {:id (str "cl-a-" c) :viewBox "0 0 10 10" :refX 8 :refY 5 :markerUnits "userSpaceOnUse" :markerWidth 11 :markerHeight 11 :orient "auto"}
                [:path {:d "M0 0L10 5L0 10z" :fill (str "var(--" c ")")}]])])

(defn- edge-view [e a b {:keys [edge set-pick set-var-pick set-edge]} focus at dir]
  (let [{:keys [on tone dim]} (edge-look e edge focus)
        d (edge-path a b (:back e) dir)]
    [:g.cl-edge {:class (when dim "dim") :on-click #(do (set-pick nil) (set-var-pick nil) (set-edge (when-not on {:from a :to b})))}
     [:path.hit {:d d}]
     [:path.line {:d d :class tone :stroke-width (+ 1.4 (* 0.9 (js/Math.log2 (inc (:pairs e)))))
                  :stroke-dasharray (when (or (:back e) (= tone "green")) "6 5") :marker-end (str "url(#cl-a-" tone ")")}]
     [:title (edge-title a b e at)]]))

(defn- click-node! [{:keys [pick set-at set-pick set-var-pick set-edge]} n]
  (set-edge nil)
  (cond (:group n) (do (set-at (:key n)) (set-pick nil) (set-var-pick nil))
        (= pick (:key n)) (do (set-pick nil) (set-var-pick nil))
        :else (do (set-pick (:key n)) (set-var-pick nil))))

(defn- node-view [n m {:keys [set-hover] :as ui} lit on]
  [:g.cl-node {:class [(when-not lit "dim") (when on "on")]
               :on-mouse-enter #(set-hover (id-of n)) :on-mouse-leave #(set-hover nil)
               :on-click #(click-node! ui n)}
   [:circle.body {:cx (:x n) :cy (:y n) :r (:r n) :style {:stroke (:color n) :fill (str "color-mix(in srgb, " (:color n) " 18%, transparent)")}}]
   (when (:group n) [:circle.ring {:cx (:x n) :cy (:y n) :r (+ (:r n) 4) :style {:stroke (:color n)}}])
   [:text.num {:x (:x n) :y (+ (:y n) 4)} (if (:group n) (:count n) (:vars n))]
   [:text.lbl {:x (:x n) :y (+ (:y n) (:r n) (if (:group n) 19 15))} (str (if (:exact n) (str (:label n) " (itself)") (:label n)) (when (:group n) " ›"))]
   (when m [:circle {:cx (+ (:x n) (* 0.72 (:r n))) :cy (- (:y n) (* 0.72 (:r n))) :r 5 :style {:fill (mark-colour m)}}
            [:title (or (:note m) (:ns m))]])
   [:title (str (:key n) (if (:group n) (str ": " (:count n) " namespaces, click to open") (str ": " (:vars n) " vars")))]])

(defn- drawing [view nodes {:keys [edge] :as ui} marks pick-node focus dir]
  (let [by-id (into {} (map (juxt id-of identity)) nodes)
        mark-on (fn [n] (some #(when (and (string? (:ns %)) (in-node? (:ns %) n)) %) marks))]
    [:<>
     [:g.cl-edges
      (for [e (:edges view)
            :let [a (by-id (:from e)) b (by-id (:to e))]
            :when (and a b)]
        (with-meta (edge-view e a b ui focus (:at view) dir) {:key (str (:from e) ">" (:to e))}))]
     (for [n nodes]
       (with-meta (node-view n (mark-on n) ui (lit? (:edges view) edge focus n) (and pick-node (= (id-of pick-node) (id-of n))))
         {:key (id-of n)}))]))

(defn- map-svg [z vb frame full box-w drawn body]
  [:svg.cl-map {:class (when (> (:k z) 1) "zoomed") :ref #(set! (.-current (:svg z)) %)
                :viewBox (viewbox vb)
                :style (when (and (not full) (pos? box-w)) {:height (* (:h frame) (min 1.1 (/ box-w (:w frame))))})
                :on-pointer-down #(pointer-down! z %)
                :on-pointer-move #(pointer-move! z %)
                :on-pointer-up #(set! (.-current (:pan z)) nil)
                :on-click-capture #(when (.-current (:dragged z)) (.stopPropagation %) (set! (.-current (:dragged z)) false))}
   (arrow-defs)
   [:g {:ref #(set! (.-current drawn) %)} body]])

(defn- legend []
  [:div.cl-legend
   [:span [:b.blue "──"] " requires"] [:span [:b.green "- -"] " required by"] [:span [:b.red "- -"] " closes a circle"]
   [:span "◎ opens · number = namespaces / vars"]])

(defn- panel [{:keys [edge pick var-pick set-var-pick] :as ui} view used detail all src marks]
  [:div.cl-panel
   (cond
     edge [edge-panel edge used #(open-ns! ui view %1 %2)]
     pick [ns-panel pick detail all var-pick set-var-pick src (filterv #(= (:ns %) pick) marks) #(open-ns! ui view % nil)]
     :else [:p.f-note "Click a bubble to open it or see its vars; click an arrow for the functions it uses."])])

;; ---- the form ----

(defn- use-map-box
  "The map's refs and measures: the box (full screen), the map's box and width, the drawing and its bounds."
  [all]
  (let [box (h/use-ref nil)
        mapbox (h/use-ref nil)
        [bounds set-bounds] (h/use-state nil)]
    {:box box :mapbox mapbox :box-w (hooks/use-width mapbox [(some? all)]) :drawn (h/use-ref nil)
     :bounds bounds :set-bounds set-bounds :full (use-fullscreen box)}))

(defn- form-body [feed ui {:keys [box mapbox box-w drawn bounds full]} z view all nodes w h dir {:keys [detail src used marks]}]
  (let [pick-node (when (:pick ui) (some #(when (in-node? (:pick ui) %) %) nodes))
        focus (if (:hover ui) (some #(when (= (id-of %) (:hover ui)) %) nodes) pick-node)
        frame (or bounds {:x 0 :y 0 :w w :h h})
        vb (view-of frame (.-current (:zoom z)))]
    (set! (.-current (:latest z)) {:frame frame :vb vb})
    [:div.cl-form {:class (when full "full") :ref #(set! (.-current box) %)}
     (head-view feed ui view all nodes dir z full box)
     [:div.cl-mapbox {:ref #(set! (.-current mapbox) %)}
      (map-svg z vb frame full box-w drawn (drawing view nodes ui marks pick-node focus dir))
      (legend)]
     (panel ui view used detail all src marks)]))

(defn clojure-form [data {:keys [server chat]}]
  (let [feed (feed-of data server chat)
        [all error] (use-namespaces feed server chat)
        ui (use-picks data)
        mb (use-map-box all)
        z (use-zoom)
        view (h/use-memo (fn [] (when all (view-at all (:at ui)))) [all (:at ui)])
        _ (use-claude-open data view ui)
        reads {:detail (use-detail feed (:pick ui)) :src (use-source feed (:pick ui) (:var-pick ui))
               :used (use-used feed (:edge ui)) :marks (items (:marks data))}
        dir (or (:chosen-dir ui) (when view (best-dir (:layers view) (:rows view) (aim (:full mb) js/innerWidth js/innerHeight))))
        [nodes {:keys [w h]}] (if view (place-nodes (:nodes view) (:layers view) (:rows view) dir) [[] {:w 0 :h 0}])]
    (use-fit z (:drawn mb) (:set-bounds mb) [view dir (:full mb)])
    (use-wheel-zoom z [(some? view)])
    (cond
      error [:p.f-note.cl-err error]
      (not view) [:p.f-note (if (or (:live feed) (:demo feed)) "Reading the project with clj-kondo…" "Open this chat to see its namespaces.")]
      :else (form-body feed ui mb z view all nodes w h dir reads))))
