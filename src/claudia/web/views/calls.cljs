(ns claudia.web.views.calls
  "The calls form: a Clojure project's function calls around one var, in three tabs. Unfold: the
  var's code, each call to a project var a link that opens that var's code nested under the calling
  line. Neighbours: callers left (with the calling line), callees right (with their doc line). Map:
  columns by steps away, boxes coloured by folder, arrows for calls. Read live through the runner
  (the clojure form's clj-kondo index); what is shown is read again when the project changes."
  (:require [clojure.string :as str]
            [claudia.web.hooks :as hooks]
            [reagent.hooks :as h]
            [claudia.web.api :as api]
            [claudia.web.shiki :as shiki]
            [claudia.web.views.code :as code]))

(def ^:private poll-ms 4000)
(def ^:private long-n 40)
(def ^:private many 30)
(def ^:private colours ["var(--c-sky)" "var(--c-amber)" "var(--c-teal)" "var(--c-pink)" "var(--c-lime)" "var(--c-violet)"
                        "var(--c-orange)" "var(--c-cyan)" "var(--c-red)" "var(--c-indigo)" "var(--c-yellow)" "var(--c-green)"])
(def ^:private mark-colour code/mark-colour)
(def ^:private items code/items)

(defn short-id
  "\"state-gate/check\" for features....state-gate/check; a .sql file by its name."
  [id]
  (if (str/starts-with? id "file:")
    (last (str/split id #"/"))
    (let [slash (str/last-index-of id "/")]
      (if slash (str (last (str/split (subs id 0 slash) #"\.")) "/" (subs id (inc slash))) id))))

(defn same? [v id]
  (or (= v id) (str/ends-with? id (str "." v))
      (and (str/starts-with? id "file:") (str/ends-with? id (str "/" (str/replace v #"^file:" ""))))))

(defn- mark-for [marks id] (some #(when (same? (:var %) id) %) marks))
(defn- file-row [n] (str (last (str/split (:file n) #"/")) ":" (:row n)))
(defn- bare-id [id] (str/replace id #"^file:" ""))

;; ---- where the data comes from: the runner, or the form's demo ----

(defn- live-source [url-of]
  (let [cache (atom {})
        ask (fn [q]
              (or (get @cache q)
                  (let [p (-> (api/fetch-json (url-of q))
                              (.catch (fn [e] (swap! cache dissoc q) {:error (.-message e)})))]
                    (swap! cache assoc q p)
                    p)))]
    {:get (fn [id] (ask {:var id}))
     :map (fn [id up down] (ask {:var id :map 1 :up up :down down}))}))

(defn- re-esc [s] (str/replace s #"[.*+?^${}()|\[\]\\]" "\\$&"))

(defn demo-by-id
  "The demo's vars by string id (EDN keys may come as keywords)."
  [demo]
  (into {} (for [[k v] demo] [(if (keyword? k) (subs (str k) 1) (str k)) v])))

(defn- brief [demo id]
  (let [v (demo id) slash (str/last-index-of id "/") sql (str/starts-with? id "file:")]
    {:id id :kind (if sql "sql" "var") :ns (when-not sql (subs id 0 slash))
     :name (if sql (last (str/split id #"/")) (subs id (inc slash))) :file (:file v)
     :folder (str/join "/" (butlast (str/split (str (:file v)) #"/"))) :row (or (:row v) 1) :private (:private v)}))

(defn demo-doc
  "A var's doc line found in its code: its docstring, or a .sql file's first -- comment."
  [demo id]
  (let [c (str (:code (demo id)))]
    (if (str/starts-with? id "file:")
      (when (str/starts-with? c "--") (str/replace (first (str/split-lines c)) #"^--\s*" ""))
      (some-> (re-find #"(?m)^\s+\"((?:\\.|[^\"\\])*)\"" c) second (str/replace "\\\"" "\"") (str/replace #"\s*\n\s*" " ")))))

(defn- called-name [to]
  (if (str/starts-with? to "file:")
    (str ":" (-> (last (str/split to #"/")) (str/replace #"\.sql$" "") (str/replace "_" "-")))
    (subs to (inc (str/last-index-of to "/")))))

(defn call-spots
  "Where var from calls to in its code (not on its first line): [{:row :col :endCol}]."
  [demo from to]
  (let [lines (str/split-lines (:code (demo from)))
        nm (called-name to)
        re (js/RegExp. (str "(^|[\\s(\\[{#'@])((?:[\\w.\\-]+/)?" (re-esc nm) ")(?=$|[\\s)\\]}])") "g")]
    (vec (for [[i l] (map-indexed vector lines) :when (pos? i)
               m (array-seq (.from js/Array (.matchAll l re)))
               :let [col (+ (.-index m) (count (aget m 1)) 1 (- (count (aget m 2)) (count nm)))]]
           {:row (+ (or (:row (demo from)) 1) i) :col col :endCol (+ col (count nm))}))))

(defn- calls-of [demo id] (filter demo (items (:calls (demo id)))))
(defn- callers-of [demo id] (filter #(some #{id} (calls-of demo %)) (keys demo)))
(defn- find-id [demo s] (some #(when (same? s %) %) (keys demo)))

(defn- caller-lines [demo f id]
  (vec (for [a (call-spots demo f id)]
         {:row (:row a) :text (str/trim (nth (str/split-lines (:code (demo f))) (- (:row a) (or (:row (demo f)) 1)) ""))})))

(defn demo-var
  "The demo's answer for one var, as the runner's: its code, its callees (with where they are
  called) and its callers (with the calling lines)."
  [demo s]
  (if-let [id (find-id demo s)]
    (let [v (demo id) c (str (:code v))]
      (merge (brief demo id)
             {:endRow (+ (or (:row v) 1) (dec (count (str/split-lines c)))) :lang (last (str/split (str (:file v)) #"\."))
              :args [] :doc (demo-doc demo id) :code c
              :callees (vec (for [t (calls-of demo id)] (assoc (brief demo t) :doc (demo-doc demo t) :at (call-spots demo id t))))
              :callers (vec (for [f (callers-of demo id)] (assoc (brief demo f) :lines (caller-lines demo f id))))}))
    {:error (str "no var " s " in the demo")}))

(defn- walk-out
  "dist with the vars up to steps calls from start (following step) added, at sign × their distance."
  [dist start steps sign step]
  (loop [k 1 front [start] dist dist]
    (if (and (<= k steps) (seq front))
      (let [found (vec (remove #(contains? dist %) (distinct (mapcat step front))))]
        (recur (inc k) found (reduce #(assoc %1 %2 (* sign k)) dist found)))
      dist)))

(defn demo-map
  "The demo's call map around var s: its callers up to up steps, its callees down to down steps."
  [demo s up down]
  (if-let [start (find-id demo s)]
    (let [dist (-> {start 0}
                   (walk-out start down 1 #(calls-of demo %))
                   (walk-out start up -1 #(callers-of demo %)))]
      {:start start :nodes (vec (for [[id n] dist] (assoc (brief demo id) :dist n)))
       :edges (vec (for [a (keys dist) b (calls-of demo a) :when (contains? dist b)] [a b])) :cut {}})
    {:error (str "no var " s " in the demo")}))

(defn- demo-source
  "The demo: {\"<id>\" {file row code private? calls}}; where each call stands, the callers and the
  docs are found in the code, the way clj-kondo would give them."
  [demo]
  (let [by-id (demo-by-id demo)]
    {:get (fn [s] (js/Promise.resolve (demo-var by-id s)))
     :map (fn [s up down] (js/Promise.resolve (demo-map by-id s up down)))}))

(defn- use-got
  "A promise-returning read, shown when it lands; nil while it is read. src: the source it reads
  through (a new one when the project changed: read again, the last answer shown meanwhile)."
  [f k src]
  (let [[got set-got] (h/use-state nil)]
    (h/use-effect
     (fn []
       (let [gone (atom false)]
         (.then (f) #(when-not @gone (set-got {:k k :d %})))
         #(reset! gone true)))
     [k src])
    (when (= k (:k got)) (:d got))))

;; ---- code with its calls as links ----

(defn- base-segments [text ^js toks]
  (if (and toks (pos? (.-length toks)))
    (mapv (fn [^js t] {:text (.-content t) :color (.-color t) :italic (pos? (bit-and (or (.-fontStyle t) 0) 1))}) (array-seq toks))
    [{:text text}]))

(defn- cut-segment
  "Piece sg (starting at pos) cut at the cuts inside it, each piece with the link it is in."
  [out sg pos cuts links]
  (let [end (+ pos (count (:text sg)))]
    (loop [from pos cs (concat (sort (filter #(and (> % pos) (< % end)) cuts)) [end]) out out]
      (if-let [c (first cs)]
        (let [piece (subs (:text sg) (- from pos) (- c pos))
              link (some #(when (and (>= from (:start %)) (< from (:end %))) %) links)]
          (recur c (rest cs) (if (seq piece) (conj out (assoc sg :text piece :to (:to link))) out)))
        out))))

(defn cut-at-links
  "Pieces [{:text …}] cut where a link {:start :end :to} starts and ends; a piece in a link gets its :to."
  [base text links]
  (if (empty? links)
    base
    (let [cuts (set (concat [0 (count text)] (mapcat (fn [l] [(:start l) (:end l)]) links)))]
      (loop [ss base pos 0 out []]
        (if-let [sg (first ss)]
          (recur (rest ss) (+ pos (count (:text sg))) (cut-segment out sg pos cuts links))
          out)))))

(defn- segments
  "A line's pieces: Shiki's colours, cut where a call's link starts and ends: [{:text :color :italic :to}]."
  [text ^js toks links]
  (cut-at-links (base-segments text toks) text links))

(defn- segment-view [k s row open on-call]
  (if (:to s)
    ^{:key k} [:button.ca-call {:type "button" :class (when (contains? open (str row " " (:to s))) "open")
                                :on-click #(on-call (:to s) row) :title (bare-id (:to s))}
               (:text s)]
    ^{:key k} [:span {:style {:color (:color s) :font-style (when (:italic s) "italic")}} (:text s)]))

(defn- line-view [row text toks links open on-call marks]
  (let [m (some #(when (= (:line %) row) %) marks)]
    [:<>
     [:div.ca-ln {:class (when m "marked") :style (when m {"--mk" (mark-colour m)})}
      [:span.ca-no row]
      (into [:span.ca-tx]
            (concat
             (for [[k s] (map-indexed vector (segments text toks links))] (segment-view k s row open on-call))
             (when-not (seq text) [" "])))]
     (when (:note m) [:div.ca-note {:style {:color (mark-colour m)}} (str "● " (:note m))])]))

(defn shown-rows
  "The rows of long code kept open: its first three and last, and around each call and kept row."
  [d kept]
  (let [last-row (+ (:row d) (dec (count (str/split (:code d) #"\n" -1))))
        hot (concat (for [c (:callees d) a (:at c)] (:row a)) kept)]
    (set (concat (range (:row d) (+ (:row d) 3)) [last-row]
                 (for [r hot x (range (dec r) (+ r 2)) :when (<= (:row d) x last-row)] x)))))

(defn links-by-row
  "Each row's call links: {row [{:start :end :to}]}, columns from 0."
  [callees]
  (reduce (fn [m c] (reduce (fn [m a] (update m (:row a) (fnil conj []) {:start (dec (:col a)) :end (dec (:endCol a)) :to (:id c)})) m (:at c)))
          {} callees))

(defn fold-plan
  "What the code draws, in order: [:line i] for a line kept open (nested cards may follow it),
  [:open i] for a hidden line drawn anyway (its fold opened, or under 3 lines), [:fold i j row] for
  hidden lines i..j-1 (row: the fold's first row). shown: the rows kept open (nil: all)."
  [n first-row shown unfolded]
  (loop [i 0 out []]
    (if (>= i n)
      out
      (let [row (+ first-row i)]
        (if (and shown (not (shown row)))
          (let [j (loop [j i] (if (and (< j n) (not (shown (+ first-row j)))) (recur (inc j)) j))]
            (recur j (if (or (unfolded row) (< (- j i) 3))
                       (into out (map (fn [k] [:open k]) (range i j)))
                       (conj out [:fold i j row]))))
          (recur (inc i) (conj out [:line i])))))))

(defn- code-view [d on-call open under marks]
  (let [lines (str/split (:code d) #"\n" -1)
        tokens (shiki/use-tokens (:code d) (or (:lang d) "clj") false)
        by-row (links-by-row (:callees d))
        line-marks (filter #(number? (:line %)) marks)
        [unfolded set-unfolded] (h/use-state #{})
        _ (h/use-effect (fn [] (set-unfolded #{}) nil) [(:id d)])
        shown (when (> (count lines) long-n) (shown-rows d (map :line line-marks)))
        line (fn [k] (let [row (+ (:row d) k)]
                       ^{:key k} [line-view row (nth lines k) (shiki/line tokens k) (get by-row row []) open on-call line-marks]))]
    (into [:div.ca-code]
          (mapcat (fn [[kind i j row]]
                    (case kind
                      :fold [^{:key (str "f" i)} [:button.ca-fold {:type "button" :on-click #(set-unfolded (conj unfolded row))}
                                                  (str "… " (- j i) " line" (when (> (- j i) 1) "s"))]]
                      :open [(line i)]
                      (cond-> [(line i)]
                        under (into (when-let [x (under (+ (:row d) i))] [^{:key (str "u" i)} [:<> x]])))))
                  (fold-plan (count lines) (:row d) shown unfolded)))))

(defn- card-head [d mine]
  [:<>
   [:div.ca-hd
    [:b {:title (bare-id (:id d))} (short-id (:id d))]
    (when (:private d) [:i "private"])
    (when (and (:varKind d) (not= "fn" (:varKind d))) [:i (:varKind d)])
    [:span (str (:file d) ":" (:row d))]]
   (for [m mine :when (and (not (number? (:line m))) (:note m))]
     ^{:key (:id m)} [:div.ca-note.ca-pad {:style {:color (mark-colour m)}} (str "● " (:note m))])])

(defn- card [src id marks head on-call open under]
  (let [got (use-got #((:get src) id) id src)]
    (cond
      (nil? got) [:div.ca-card [:p.f-note.ca-pad "Reading…"]]
      (:error got) [:div.ca-card [:p.f-note.cl-err (:error got)]]
      :else
      (let [mine (filter #(same? (:var %) (:id got)) marks)]
        [:div.ca-card
         (card-head got mine)
         (when head (head got))
         [:f> code-view got on-call (or open #{}) under mine]]))))

;; ---- A · Unfold ----

(defn toggle-nest
  "The nested cards with {:row :to} opened, or closed when it was open."
  [nests row to]
  (if (some #(and (= (:row %) row) (= (:to %) to)) nests)
    (vec (remove #(and (= (:row %) row) (= (:to %) to)) nests))
    (conj nests {:row row :to to})))

(defn- callers-chips [reroot d]
  [:div.ca-up
   [:span.k "called by"]
   (if (seq (:callers d))
     (for [c (:callers d)] ^{:key (:id c)} [:button.cl-chip {:type "button" :title (:id c) :on-click #(reroot (:id c))} (short-id (:id c))])
     [:span.f-note "nobody in this project"])])

(defn- unfold-card [src id marks top reroot]
  (let [[nests set-nests] (h/use-state [])
        _ (h/use-effect (fn [] (set-nests []) nil) [id])
        toggle (fn [to row] (set-nests #(toggle-nest % row to)))]
    [:f> card src id marks
     (when top #(callers-chips reroot %))
     toggle
     (set (map #(str (:row %) " " (:to %)) nests))
     (fn [row]
       (let [here (filter #(= (:row %) row) nests)]
         (when (seq here)
           (for [n here] ^{:key (str (:row n) " " (:to n))} [:div.ca-nest [:f> unfold-card src (:to n) marks false nil]]))))]))

;; ---- B · Neighbours ----

(defn- trail-bar [trail set-trail]
  [:div.ca-trail
   [:span.k "path"]
   (for [[i t] (map-indexed vector trail)]
     ^{:key i}
     [:<> (when (pos? i) [:span.ca-sep "→"])
      [:button.cl-chip {:type "button" :class (when (= i (dec (count trail))) "on") :title t :on-click #(set-trail (subvec trail 0 (inc i)))} (short-id t)]])])

(defn- mark-dot [marks id]
  (when-let [m (mark-for marks id)] [:i.ca-dot {:style {:background (mark-colour m)} :title (:note m)}]))

(defn- callers-side [d all set-all marks go]
  [:div.ca-side.ca-left
   [:div.ca-lab (str "← called by" (when d (str " · " (count (:callers d)))))]
   (when (and d (empty? (:callers d))) [:p.f-note "nobody in this project"])
   (for [c (if all (:callers d) (take many (:callers d)))]
     ^{:key (:id c)}
     [:button.ca-item {:type "button" :on-click #(go (:id c)) :title (:id c)}
      [:b (mark-dot marks (:id c)) (short-id (:id c))] [:span (file-row c)]
      (for [l (:lines c)] ^{:key (:row l)} [:code [:em (:row l)] (:text l)])])
   (when (and d (not all) (> (count (:callers d)) many))
     [:button.ca-fold {:type "button" :on-click #(set-all true)} (str "… " (- (count (:callers d)) many) " more callers")])])

(defn- callees-side [d marks go]
  [:div.ca-side.ca-right
   [:div.ca-lab (str "calls →" (when d (str " · " (count (:callees d)))))]
   (when (and d (empty? (:callees d))) [:p.f-note "no project vars"])
   (for [c (:callees d)]
     ^{:key (:id c)}
     [:button.ca-item {:type "button" :on-click #(go (:id c)) :title (bare-id (:id c))}
      [:b (mark-dot marks (:id c)) (short-id (:id c))] [:span (file-row c)]
      (when (:doc c) [:code.doc (:doc c)])])])

(defn- neighbours [src start marks]
  (let [[trail set-trail] (h/use-state [start])
        _ (h/use-effect (fn [] (set-trail [start]) nil) [start])
        here (peek trail)
        got (use-got #((:get src) here) here src)
        go (fn [id] (set-trail #(if (= (peek %) id) % (conj % id))))
        [all set-all] (h/use-state false)
        _ (h/use-effect (fn [] (set-all false) nil) [here])
        d (when (and got (not (:error got))) got)]
    [:<>
     (trail-bar trail set-trail)
     [:div.ca-hood
      (callers-side d all set-all marks go)
      [:div.ca-mid [:f> card src here marks nil (fn [to _] (go to)) nil nil]]
      (callees-side d marks go)]]))

;; ---- C · Map ----

(defn rounded
  "A path through pts with its corners rounded."
  [pts]
  (let [rr 7]
    (str (apply str "M" (first (first pts)) "," (second (first pts))
                (for [i (range 1 (dec (count pts)))
                      :let [[px py] (nth pts (dec i)) [cx cy] (nth pts i) [nx ny] (nth pts (inc i))
                            l1 (js/Math.hypot (- cx px) (- cy py)) l2 (js/Math.hypot (- nx cx) (- ny cy))
                            k1 (/ (min rr (/ l1 2)) (if (pos? l1) l1 1)) k2 (/ (min rr (/ l2 2)) (if (pos? l2) l2 1))]]
                  (str " L" (- cx (* (- cx px) k1)) "," (- cy (* (- cy py) k1)) " Q" cx "," cy " " (+ cx (* (- nx cx) k2)) "," (+ cy (* (- ny cy) k2)))))
         " L" (first (last pts)) "," (second (last pts)))))

(defn- mid-y [r] (+ (:top r) (/ (:height r) 2)))

(defn- forward-arrow [a b ra rb]
  (let [x1 (:right ra) y1 (mid-y ra) x2 (- (:left rb) 2) y2 (mid-y rb) mx (/ (+ x1 x2) 2)]
    {:a a :b b :d (str "M" x1 "," y1 " C" mx "," y1 " " mx "," y2 " " x2 "," y2)}))

(defn- back-arrow
  "An arrow to a box not to the right: down below every box, along, and up; backs before it go lower."
  [a b ra rb floor backs]
  (let [ya (mid-y ra) yb (mid-y rb)
        low (+ floor 12 (* 7 (mod backs 4)))
        left (< (:right rb) (- (:left ra) 2))
        gb (if left (+ (:right rb) 7) (- (:left rb) 7))]
    {:a a :b b :back true
     :d (rounded [[(:right ra) ya] [(+ (:right ra) 7) ya] [(+ (:right ra) 7) low] [gb low] [gb yb]
                  [(if left (+ (:right rb) 2) (- (:left rb) 2)) yb]])}))

(defn arrow-paths
  "The map's arrows [{:a :b :d :back}] between the boxes' rects ({id {:left :right :top :bottom
  :height}}, relative to the map) for edges [[a b]]; edges whose boxes are missing are left out."
  [rects edges]
  (let [floor (reduce max 0 (map :bottom (vals rects)))]
    (first
     (reduce (fn [[out backs] [a b]]
               (let [ra (rects a) rb (rects b)]
                 (cond
                   (not (and ra rb)) [out backs]
                   (> (:left rb) (:right ra)) [(conj out (forward-arrow a b ra rb)) backs]
                   :else [(conj out (back-arrow a b ra rb floor backs)) (inc backs)])))
             [[] 0] edges))))

(defn- rect-in [^js r ^js r0]
  {:left (- (.-left r) (.-left r0)) :right (- (.-right r) (.-left r0))
   :top (- (.-top r) (.-top r0)) :bottom (- (.-bottom r) (.-top r0)) :height (.-height r)})

(defn folder-list
  "The map's folders, nearest the start first: the order their colours go in."
  [nodes]
  (vec (distinct (map :folder (sort-by (comp js/Math.abs :dist) nodes)))))

(defn map-columns
  "The map's columns by steps away ({:k :nodes}), each sorted by folder, then name."
  [nodes]
  (vec (for [k (sort (distinct (map :dist nodes)))]
         {:k k :nodes (sort (fn [a b] (let [c (.localeCompare (str (:folder a)) (str (:folder b)))] (if (zero? c) (.localeCompare (str (:name a)) (str (:name b))) c)))
                            (filter (fn [n] (= k (:dist n))) nodes))})))

(defn column-label [k] (cond (neg? k) (str (- k) " up") (zero? k) "start" :else (str k " down")))

(defn depth-step [n by] (max 0 (min 8 (+ n by))))

(defn- use-arrows
  "The arrows between the map's boxes, measured from the page after a draw and when it resizes."
  [data wrap boxes]
  (let [[paths set-paths] (h/use-state [])
        [size set-size] (h/use-state {:w 0 :h 0})
        measure (fn []
                  (when-let [w (and data (.-current wrap))]
                    (let [r0 (.getBoundingClientRect w)
                          rects (into {} (for [[id el] (es6-iterator-seq (.entries (.-current boxes)))] [id (rect-in (.getBoundingClientRect el) r0)]))]
                      (set-size {:w (.-width r0) :h (.-height r0)})
                      (set-paths (arrow-paths rects (:edges data))))))]
    (h/use-layout-effect (fn [] (measure) nil) [data])
    (hooks/use-resize wrap (fn [_] (measure)) [(some? data)])
    [paths size]))

(defn- depth-bar [data up down set-up set-down]
  [:div.ca-depth
   [:span.k "callers"]
   [:button.cl-full {:type "button" :disabled (zero? up) :on-click #(set-up (depth-step up -1))} "−"] [:b up]
   [:button.cl-full {:type "button" :disabled (= up 8) :on-click #(set-up (depth-step up 1))} "+"]
   [:span.k "callees"]
   [:button.cl-full {:type "button" :disabled (zero? down) :on-click #(set-down (depth-step down -1))} "−"] [:b down]
   [:button.cl-full {:type "button" :disabled (= down 8) :on-click #(set-down (depth-step down 1))} "+"]
   [:span.ca-count (str (count (:nodes data)) " vars · " (count (:edges data)) " calls")]])

(defn- folder-legend [folders]
  [:div.ca-legend
   (for [[i f] (map-indexed vector folders)] ^{:key f} [:span {:style {"--c" (nth colours (mod i (count colours)))}} [:i] (if (seq f) f "(top)")])])

(defn- arrows-svg [size paths hot?]
  [:svg.ca-arrows {:width (:w size) :height (:h size)}
   [:defs (for [c ["muted" "accent"]]
            ^{:key c} [:marker {:id (str "ca-a-" c) :viewBox "0 0 8 8" :refX 7 :refY 4 :markerWidth 7 :markerHeight 7 :orient "auto"}
                       [:path {:d "M0,0L8,4L0,8z" :fill (str "var(--" c ")")}]])]
   (for [p (remove hot? paths)] ^{:key (str (:a p) ">" (:b p))} [:path.e {:d (:d p) :class (when (:back p) "back") :marker-end "url(#ca-a-muted)"}])
   (for [p (filter hot? paths)] ^{:key (str (:a p) ">" (:b p))} [:path.e.hot {:d (:d p) :class (when (:back p) "back") :marker-end "url(#ca-a-accent)"}])])

(defn- map-box [n mk boxes picked start colour set-sel]
  [:button.ca-box {:type "button" :ref #(if % (.set (.-current boxes) (:id n) %) (.delete (.-current boxes) (:id n)))
                   :class [(when (= (:id n) picked) "sel") (when (= (:id n) start) "start")]
                   :style {"--c" colour} :on-click #(set-sel (:id n))
                   :title (str (bare-id (:id n)) (when (:note mk) (str "\n● " (:note mk))))}
   [:b (when mk [:i.ca-dot {:style {:background (mark-colour mk)}}]) (:name n) (when (:private n) [:<> " " [:em "private"]])]
   [:span (file-row n)]])

(defn- map-column [c data marks boxes picked colour-of set-sel]
  [:div.ca-col
   [:div.ca-dl (column-label (:k c))]
   [:div.ca-stack
    (for [n (:nodes c)]
      (with-meta (map-box n (mark-for marks (:id n)) boxes picked (:start data) (colour-of n) set-sel) {:key (:id n)}))
    (when-let [cut (or (get (:cut data) (keyword (str (:k c)))) (get (:cut data) (str (:k c))))]
      [:div.f-note.ca-cut (str "+ " cut " more")])]])

(defn- call-map [src start marks up down set-up set-down]
  (let [m (use-got #((:map src) start up down) (str start " " up " " down) src)
        [sel set-sel] (h/use-state nil)
        _ (h/use-effect (fn [] (set-sel nil) nil) [start])
        data (when (and m (not (:error m))) m)
        picked (or sel (:start data))
        folders (h/use-memo #(folder-list (:nodes data)) [data])
        colour-of (fn [n] (nth colours (mod (max 0 (.indexOf (clj->js folders) (:folder n))) (count colours))))
        cols (h/use-memo #(map-columns (:nodes data)) [data])
        wrap (h/use-ref nil)
        boxes (h/use-ref nil)
        _ (when-not (.-current boxes) (set! (.-current boxes) (js/Map.)))
        [paths size] (use-arrows data wrap boxes)]
    (cond
      (nil? m) [:p.f-note.ca-pad "Reading…"]
      (:error m) [:p.f-note.cl-err (:error m)]
      :else
      [:<>
       (depth-bar data up down set-up set-down)
       (folder-legend folders)
       [:div.ca-wrap {:ref #(set! (.-current wrap) %)}
        (arrows-svg size paths (fn [e] (and picked (or (= (:a e) picked) (= (:b e) picked)))))
        [:div.ca-cols
         (for [c cols] (with-meta (map-column c data marks boxes picked colour-of set-sel) {:key (:k c)}))]]
       (when picked [:f> card src picked marks nil (fn [to _] (set-sel to)) nil nil])])))

;; ---- the form ----

(def ^:private views
  [{:v "unfold" :label "A · Unfold" :hint "Click a blue call: its code opens right under that line; click again to close. \"called by\" goes up."}
   {:v "neighbours" :label "B · Neighbours" :hint "Who calls it on the left (with the calling line), what it calls on the right (with its doc line). Click anything to move there."}
   {:v "map" :label "C · Map" :hint "Columns are steps away from the start. Colour is the folder. Click a box: its code shows below and its arrows light up."}])

(defn view-of [v] (if (#{"neighbours" "map"} v) v "unfold"))
(defn depth [v d] (if (and (number? v) (>= v 0)) (min 8 (js/Math.floor v)) d))

(defn- use-choices
  "The tab, the var the unfold starts from and the map's depths, each following the form's data."
  [data open]
  (let [[view set-view] (h/use-state (view-of (:view data)))
        [root-var set-root-var] (h/use-state open)
        [up set-up] (h/use-state (depth (:up data) 2))
        [down set-down] (h/use-state (depth (:down data) 3))]
    (h/use-effect (fn [] (set-view (view-of (:view data))) nil) [(:view data)])
    (h/use-effect (fn [] (set-root-var open) nil) [open])
    (h/use-effect (fn [] (set-up (depth (:up data) 2)) nil) [(:up data)])
    (h/use-effect (fn [] (set-down (depth (:down data) 3)) nil) [(:down data)])
    {:view view :set-view set-view :root-var root-var :set-root-var set-root-var
     :up up :set-up set-up :down down :set-down set-down}))

(defn- form-head [view set-view demo root open]
  [:<>
   [:div.ca-head
    [:div.ca-tabs {:role "tablist"}
     (for [x views]
       ^{:key (:v x)} [:button {:type "button" :role "tab" :aria-selected (= view (:v x)) :class (when (= view (:v x)) "on") :on-click #(set-view (:v x))} (:label x)])]
    [:span.ca-where {:title open} (str (if demo "demo" (code/project-name root)) " · " (short-id open))]]
   [:p.ca-hint (:hint (some #(when (= (:v %) view) %) views))]])

(defn- form-body [src open marks {:keys [view root-var set-root-var up down set-up set-down]}]
  [:div.ca-body
   (case view
     "unfold" [:<>
               (when (not= root-var open)
                 [:button.ca-fold.ca-back {:type "button" :on-click #(set-root-var open)} (str "↺ back to " (short-id open))])
               ^{:key root-var} [:f> unfold-card src root-var marks true set-root-var]]
     "neighbours" [:f> neighbours src open marks]
     [:f> call-map src open marks up down set-up set-down])])

(defn calls-form [data {:keys [server chat]}]
  (let [demo (when (map? (:demo data)) (:demo data))
        root (if (string? (:root data)) (:root data) "")
        open (str/trim (str (:open data)))
        live (and (not demo) (seq root) chat)
        url-of (fn [q] (api/url server (str "chats/" (js/encodeURIComponent chat) "/calls") (merge {:root root} q)))
        [fp set-fp] (h/use-state nil)
        ;; A new source (an empty cache) whenever the project's fingerprint changes.
        error (hooks/use-poll {:on? live :ms poll-ms :url-of #(url-of {:fp %}) :on-answer #(set-fp (:fp %))}
                              [live root server chat])
        choices (use-choices data open)
        src (h/use-memo #(cond demo (demo-source demo) (and live fp) (live-source url-of)) [(some? demo) live root fp])
        marks (filterv #(string? (:var %)) (items (:marks data)))]
    (cond
      error [:p.f-note.cl-err error]
      (not (seq open)) [:p.f-note "No var to start from (open: \"ns/var\")."]
      (not src) [:p.f-note (if live "Reading the project with clj-kondo…" "Open this chat to see its calls.")]
      :else
      [:div.ca-form
       (form-head (:view choices) (:set-view choices) demo root open)
       (form-body src open marks choices)])))
