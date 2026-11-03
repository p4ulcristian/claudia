(ns claudia.web.views.calls
  "The calls form: a Clojure project's function calls around one var, in three tabs. Unfold: the
  var's code, each call to a project var a link that opens that var's code nested under the calling
  line. Neighbours: callers left (with the calling line), callees right (with their doc line). Map:
  columns by steps away, boxes coloured by folder, arrows for calls. Read live through the runner
  (the clojure form's clj-kondo index); what is shown is read again when the project changes."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.api :as api]
            [claudia.web.hl :as hl]
            [claudia.web.views.code :as code]))

(def ^:private poll-ms 4000)
(def ^:private long-n 40)
(def ^:private many 30)
(def ^:private colours ["var(--c-sky)" "var(--c-amber)" "var(--c-teal)" "var(--c-pink)" "var(--c-lime)" "var(--c-violet)"
                        "var(--c-orange)" "var(--c-cyan)" "var(--c-red)" "var(--c-indigo)" "var(--c-yellow)" "var(--c-green)"])
(def ^:private mark-colours {"red" "var(--red)" "green" "var(--green)" "amber" "var(--amber)" "blue" "var(--blue)" "violet" "var(--violet)" "grey" "var(--muted)"})
(defn- mark-colour [m] (get mark-colours (:color m) "var(--amber)"))
(def ^:private items code/items)

(defn- short-id
  "\"state-gate/check\" for features....state-gate/check; a .sql file by its name."
  [id]
  (if (str/starts-with? id "file:")
    (last (str/split id #"/"))
    (let [slash (str/last-index-of id "/")]
      (if slash (str (last (str/split (subs id 0 slash) #"\.")) "/" (subs id (inc slash))) id))))

(defn- same? [v id]
  (or (= v id) (str/ends-with? id (str "." v))
      (and (str/starts-with? id "file:") (str/ends-with? id (str "/" (str/replace v #"^file:" ""))))))

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

(defn- demo-source
  "The demo: {\"<id>\" {file row code private? calls}}; where each call stands, the callers and the
  docs are found in the code, the way clj-kondo would give them."
  [demo]
  (let [demo (into {} (for [[k v] demo] [(if (keyword? k) (subs (str k) 1) (str k)) v]))
        ids (keys demo)
        resolve (fn [s] (some #(when (same? s %) %) ids))
        brief (fn [id]
                (let [v (demo id) slash (str/last-index-of id "/") sql (str/starts-with? id "file:")]
                  {:id id :kind (if sql "sql" "var") :ns (when-not sql (subs id 0 slash))
                   :name (if sql (last (str/split id #"/")) (subs id (inc slash))) :file (:file v)
                   :folder (str/join "/" (butlast (str/split (str (:file v)) #"/"))) :row (or (:row v) 1) :private (:private v)}))
        doc-of (fn [id]
                 (let [c (str (:code (demo id)))]
                   (if (str/starts-with? id "file:")
                     (when (str/starts-with? c "--") (str/replace (first (str/split-lines c)) #"^--\s*" ""))
                     (some-> (re-find #"(?m)^\s+\"((?:\\.|[^\"\\])*)\"" c) second (str/replace "\\\"" "\"") (str/replace #"\s*\n\s*" " ")))))
        where (fn [from to]
                (let [lines (str/split-lines (:code (demo from)))
                      nm (if (str/starts-with? to "file:")
                           (str ":" (-> (last (str/split to #"/")) (str/replace #"\.sql$" "") (str/replace "_" "-")))
                           (subs to (inc (str/last-index-of to "/"))))
                      re (js/RegExp. (str "(^|[\\s(\\[{#'@])((?:[\\w.\\-]+/)?" (re-esc nm) ")(?=$|[\\s)\\]}])") "g")]
                  (vec (for [[i l] (map-indexed vector lines) :when (pos? i)
                             m (array-seq (.from js/Array (.matchAll l re)))
                             :let [col (+ (.-index m) (count (aget m 1)) 1 (- (count (aget m 2)) (count nm)))]]
                         {:row (+ (or (:row (demo from)) 1) i) :col col :endCol (+ col (count nm))}))))
        calls-of (fn [id] (filter demo (items (:calls (demo id)))))
        callers-of (fn [id] (filter #(some #{id} (calls-of %)) ids))]
    {:get (fn [s]
            (js/Promise.resolve
             (if-let [id (resolve s)]
               (let [v (demo id) c (str (:code v))]
                 (merge (brief id)
                        {:endRow (+ (or (:row v) 1) (dec (count (str/split-lines c)))) :lang (last (str/split (str (:file v)) #"\."))
                         :args [] :doc (doc-of id) :code c
                         :callees (vec (for [t (calls-of id)] (assoc (brief t) :doc (doc-of t) :at (where id t))))
                         :callers (vec (for [f (callers-of id)]
                                         (assoc (brief f) :lines (vec (for [a (where f id)]
                                                                        {:row (:row a) :text (str/trim (nth (str/split-lines (:code (demo f))) (- (:row a) (or (:row (demo f)) 1)) ""))})))))}))
               {:error (str "no var " s " in the demo")})))
     :map (fn [s up down]
            (js/Promise.resolve
             (if-let [start (resolve s)]
               (let [dist (atom {start 0})
                     walk (fn [steps sign next]
                            (loop [k 1 front [start]]
                              (when (and (<= k steps) (seq front))
                                (let [found (vec (remove #(contains? @dist %) (distinct (mapcat next front))))]
                                  (doseq [n found] (swap! dist assoc n (* sign k)))
                                  (recur (inc k) found)))))]
                 (walk down 1 calls-of)
                 (walk up -1 callers-of)
                 {:start start :nodes (vec (for [[id n] @dist] (assoc (brief id) :dist n)))
                  :edges (vec (for [a (keys @dist) b (calls-of a) :when (contains? @dist b)] [a b])) :cut {}})
               {:error (str "no var " s " in the demo")})))}))

(defn- use-got
  "A promise-returning read, shown when it lands; nil while it is read."
  [f k]
  (let [[got set-got] (h/use-state nil)]
    (h/use-effect
     (fn []
       (let [gone (atom false)]
         (.then (f) #(when-not @gone (set-got {:k k :d %})))
         #(reset! gone true)))
     [k])
    (when (= k (:k got)) (:d got))))

;; ---- code with its calls as links ----

(defn- segments
  "A line's HTML pieces cut where a call's link starts and ends: [{:text :html? :to?}]."
  [text links]
  (if (empty? links)
    [{:text text}]
    (let [cuts (sort (distinct (concat [0 (count text)] (mapcat (fn [l] [(:start l) (:end l)]) links))))]
      (vec (for [[a b] (partition 2 1 cuts) :when (< a b (inc (count text)))
                 :let [l (some #(when (and (>= a (:start %)) (< a (:end %))) %) links)]]
             {:text (subs text a (min b (count text))) :to (:to l)})))))

(defn- line-view [row text html links open on-call marks]
  (let [m (some #(when (= (:line %) row) %) marks)]
    [:<>
     [:div.ca-ln {:class (when m "marked") :style (when m {"--mk" (mark-colour m)})}
      [:span.ca-no row]
      (if (and html (empty? links))
        [:span.ca-tx {:dangerouslySetInnerHTML (r/unsafe-html (if (seq html) html " "))}]
        (into [:span.ca-tx]
              (concat
               (for [[k s] (map-indexed vector (segments text links))]
                 (if (:to s)
                   ^{:key k} [:button.ca-call {:type "button" :class (when (contains? open (str row " " (:to s))) "open")
                                               :on-click #(on-call (:to s) row) :title (str/replace (:to s) #"^file:" "")}
                              (:text s)]
                   ^{:key k} [:span (:text s)]))
               (when-not (seq text) [" "]))))]
     (when (:note m) [:div.ca-note {:style {:color (mark-colour m)}} (str "● " (:note m))])]))

(defn- shown-rows [d keep]
  (let [last-row (+ (:row d) (dec (count (str/split (:code d) #"\n"))))
        hot (concat (for [c (:callees d) a (:at c)] (:row a)) keep)]
    (set (concat (range (:row d) (+ (:row d) 3)) [last-row]
                 (for [r hot x (range (dec r) (+ r 2)) :when (<= (:row d) x last-row)] x)))))

(defn- code-view [d on-call open under marks]
  (let [lines (str/split (:code d) #"\n")
        html (h/use-memo #(hl/lines (:code d) (or (:lang d) "clj")) [(:code d) (:lang d)])
        by-row (reduce (fn [m c] (reduce (fn [m a] (update m (:row a) (fnil conj []) {:start (dec (:col a)) :end (dec (:endCol a)) :to (:id c)})) m (:at c)))
                       {} (:callees d))
        line-marks (filter #(number? (:line %)) marks)
        [unfolded set-unfolded] (h/use-state #{})
        _ (h/use-effect (fn [] (set-unfolded #{}) nil) [(:id d)])
        shown (when (> (count lines) long-n) (shown-rows d (map :line line-marks)))
        line (fn [k] (let [row (+ (:row d) k)]
                       ^{:key k} [line-view row (nth lines k) (nth html k nil) (get by-row row []) open on-call line-marks]))]
    (into [:div.ca-code]
          (loop [i 0 out []]
            (if (>= i (count lines))
              out
              (let [row (+ (:row d) i)]
                (if (and shown (not (shown row)))
                  (let [j (loop [j i] (if (and (< j (count lines)) (not (shown (+ (:row d) j)))) (recur (inc j)) j))]
                    (recur j (if (or (unfolded row) (< (- j i) 3))
                               (into out (map line (range i j)))
                               (conj out ^{:key (str "f" i)} [:button.ca-fold {:type "button" :on-click #(set-unfolded (conj unfolded row))}
                                                              (str "… " (- j i) " line" (when (> (- j i) 1) "s"))]))))
                  (recur (inc i) (cond-> (conj out (line i))
                                   under (into (when-let [x (under row)] [^{:key (str "u" i)} [:<> x]])))))))))))

(defn- card [src id marks head on-call open under]
  (let [got (use-got #((:get src) id) id)]
    (cond
      (nil? got) [:div.ca-card [:p.f-note.ca-pad "Reading…"]]
      (:error got) [:div.ca-card [:p.f-note.cl-err (:error got)]]
      :else
      (let [d got
            mine (filter #(same? (:var %) (:id d)) marks)]
        [:div.ca-card
         [:div.ca-hd
          [:b {:title (str/replace (:id d) #"^file:" "")} (short-id (:id d))]
          (when (:private d) [:i "private"])
          (when (and (:varKind d) (not= "fn" (:varKind d))) [:i (:varKind d)])
          [:span (str (:file d) ":" (:row d))]]
         (for [m mine :when (and (not (number? (:line m))) (:note m))]
           ^{:key (:id m)} [:div.ca-note.ca-pad {:style {:color (mark-colour m)}} (str "● " (:note m))])
         (when head (head d))
         [:f> code-view d on-call (or open #{}) under mine]]))))

;; ---- A · Unfold ----

(defn- unfold-card [src id marks top reroot]
  (let [[nests set-nests] (h/use-state [])
        _ (h/use-effect (fn [] (set-nests []) nil) [id])
        toggle (fn [to row] (set-nests (fn [n] (if (some #(and (= (:row %) row) (= (:to %) to)) n)
                                                 (vec (remove #(and (= (:row %) row) (= (:to %) to)) n))
                                                 (conj n {:row row :to to})))))]
    [:f> card src id marks
     (when top
       (fn [d]
         [:div.ca-up
          [:span.k "called by"]
          (if (seq (:callers d))
            (for [c (:callers d)] ^{:key (:id c)} [:button.cl-chip {:type "button" :title (:id c) :on-click #(reroot (:id c))} (short-id (:id c))])
            [:span.f-note "nobody in this project"])]))
     toggle
     (set (map #(str (:row %) " " (:to %)) nests))
     (fn [row]
       (let [here (filter #(= (:row %) row) nests)]
         (when (seq here)
           (for [n here] ^{:key (str (:row n) " " (:to n))} [:div.ca-nest [:f> unfold-card src (:to n) marks false nil]]))))]))

;; ---- B · Neighbours ----

(defn- neighbours [src start marks]
  (let [[trail set-trail] (h/use-state [start])
        _ (h/use-effect (fn [] (set-trail [start]) nil) [start])
        here (peek trail)
        got (use-got #((:get src) here) here)
        go (fn [id] (set-trail #(if (= (peek %) id) % (conj % id))))
        [all set-all] (h/use-state false)
        _ (h/use-effect (fn [] (set-all false) nil) [here])
        d (when (and got (not (:error got))) got)
        dot (fn [id] (when-let [m (some #(when (same? (:var %) id) %) marks)] [:i.ca-dot {:style {:background (mark-colour m)} :title (:note m)}]))
        callers (if all (:callers d) (take many (:callers d)))]
    [:<>
     [:div.ca-trail
      [:span.k "path"]
      (for [[i t] (map-indexed vector trail)]
        ^{:key i}
        [:<> (when (pos? i) [:span.ca-sep "→"])
         [:button.cl-chip {:type "button" :class (when (= i (dec (count trail))) "on") :title t :on-click #(set-trail (subvec trail 0 (inc i)))} (short-id t)]])]
     [:div.ca-hood
      [:div.ca-side.ca-left
       [:div.ca-lab (str "← called by" (when d (str " · " (count (:callers d)))))]
       (when (and d (empty? (:callers d))) [:p.f-note "nobody in this project"])
       (for [c callers]
         ^{:key (:id c)}
         [:button.ca-item {:type "button" :on-click #(go (:id c)) :title (:id c)}
          [:b (dot (:id c)) (short-id (:id c))] [:span (str (last (str/split (:file c) #"/")) ":" (:row c))]
          (for [l (:lines c)] ^{:key (:row l)} [:code [:em (:row l)] (:text l)])])
       (when (and d (not all) (> (count (:callers d)) many))
         [:button.ca-fold {:type "button" :on-click #(set-all true)} (str "… " (- (count (:callers d)) many) " more callers")])]
      [:div.ca-mid [:f> card src here marks nil (fn [to _] (go to)) nil nil]]
      [:div.ca-side.ca-right
       [:div.ca-lab (str "calls →" (when d (str " · " (count (:callees d)))))]
       (when (and d (empty? (:callees d))) [:p.f-note "no project vars"])
       (for [c (:callees d)]
         ^{:key (:id c)}
         [:button.ca-item {:type "button" :on-click #(go (:id c)) :title (str/replace (:id c) #"^file:" "")}
          [:b (dot (:id c)) (short-id (:id c))] [:span (str (last (str/split (:file c) #"/")) ":" (:row c))]
          (when (:doc c) [:code.doc (:doc c)])])]]]))

;; ---- C · Map ----

(defn- rounded [pts]
  (let [rr 7]
    (str (apply str "M" (first (first pts)) "," (second (first pts))
                (for [i (range 1 (dec (count pts)))
                      :let [[px py] (nth pts (dec i)) [cx cy] (nth pts i) [nx ny] (nth pts (inc i))
                            l1 (js/Math.hypot (- cx px) (- cy py)) l2 (js/Math.hypot (- nx cx) (- ny cy))
                            k1 (/ (min rr (/ l1 2)) (if (pos? l1) l1 1)) k2 (/ (min rr (/ l2 2)) (if (pos? l2) l2 1))]]
                  (str " L" (- cx (* (- cx px) k1)) "," (- cy (* (- cy py) k1)) " Q" cx "," cy " " (+ cx (* (- nx cx) k2)) "," (+ cy (* (- ny cy) k2)))))
         " L" (first (last pts)) "," (second (last pts)))))

(defn- call-map [src start marks up down set-up set-down]
  (let [m (use-got #((:map src) start up down) (str start " " up " " down))
        [sel set-sel] (h/use-state nil)
        _ (h/use-effect (fn [] (set-sel nil) nil) [start])
        data (when (and m (not (:error m))) m)
        picked (or sel (:start data))
        folders (h/use-memo #(vec (distinct (map :folder (sort-by (comp js/Math.abs :dist) (:nodes data))))) [data])
        colour-of (fn [n] (nth colours (mod (max 0 (.indexOf (clj->js folders) (:folder n))) (count colours))))
        cols (h/use-memo #(vec (for [k (sort (distinct (map :dist (:nodes data))))]
                                 {:k k :nodes (sort-by (juxt :folder :name) (filter (fn [n] (= k (:dist n))) (:nodes data)))}))
                         [data])
        wrap (h/use-ref nil)
        boxes (h/use-ref nil)
        _ (when-not (.-current boxes) (set! (.-current boxes) (js/Map.)))
        [paths set-paths] (h/use-state [])
        [size set-size] (h/use-state {:w 0 :h 0})
        measure (fn []
                  (when-let [w (and data (.-current wrap))]
                    (let [r0 (.getBoundingClientRect w)
                          rects (into {} (for [[id el] (es6-iterator-seq (.entries (.-current boxes)))] [id (.getBoundingClientRect el)]))
                          floor (reduce max 0 (map #(- (.-bottom %) (.-top r0)) (vals rects)))
                          x #(- % (.-left r0)) y #(- % (.-top r0))]
                      (set-size {:w (.-width r0) :h (.-height r0)})
                      (set-paths
                       (first
                        (reduce (fn [[out backs] [a b]]
                                  (let [ra (rects a) rb (rects b)]
                                    (cond
                                      (not (and ra rb)) [out backs]
                                      (> (.-left rb) (.-right ra))
                                      (let [x1 (x (.-right ra)) y1 (y (+ (.-top ra) (/ (.-height ra) 2))) x2 (- (x (.-left rb)) 2) y2 (y (+ (.-top rb) (/ (.-height rb) 2))) mx (/ (+ x1 x2) 2)]
                                        [(conj out {:a a :b b :d (str "M" x1 "," y1 " C" mx "," y1 " " mx "," y2 " " x2 "," y2)}) backs])
                                      :else
                                      (let [ya (y (+ (.-top ra) (/ (.-height ra) 2))) yb (y (+ (.-top rb) (/ (.-height rb) 2)))
                                            low (+ floor 12 (* 7 (mod backs 4)))
                                            left (< (.-right rb) (- (.-left ra) 2))
                                            gb (if left (+ (x (.-right rb)) 7) (- (x (.-left rb)) 7))]
                                        [(conj out {:a a :b b :back true
                                                    :d (rounded [[(x (.-right ra)) ya] [(+ (x (.-right ra)) 7) ya] [(+ (x (.-right ra)) 7) low] [gb low] [gb yb]
                                                                 [(if left (+ (x (.-right rb)) 2) (- (x (.-left rb)) 2)) yb]])})
                                         (inc backs)]))))
                                [[] 0] (:edges data)))))))]
    (h/use-layout-effect (fn [] (measure) nil) [data])
    (h/use-effect
     (fn []
       (when-let [w (.-current wrap)]
         (let [ro (js/ResizeObserver. #(measure))] (.observe ro w) #(.disconnect ro))))
     [(some? data)])
    (cond
      (nil? m) [:p.f-note.ca-pad "Reading…"]
      (:error m) [:p.f-note.cl-err (:error m)]
      :else
      (let [hot? (fn [e] (and picked (or (= (:a e) picked) (= (:b e) picked))))
            mark-of (fn [id] (some #(when (same? (:var %) id) %) marks))
            step (fn [n set by] (set (max 0 (min 8 (+ n by)))))]
        [:<>
         [:div.ca-depth
          [:span.k "callers"]
          [:button.cl-full {:type "button" :disabled (zero? up) :on-click #(step up set-up -1)} "−"] [:b up]
          [:button.cl-full {:type "button" :disabled (= up 8) :on-click #(step up set-up 1)} "+"]
          [:span.k "callees"]
          [:button.cl-full {:type "button" :disabled (zero? down) :on-click #(step down set-down -1)} "−"] [:b down]
          [:button.cl-full {:type "button" :disabled (= down 8) :on-click #(step down set-down 1)} "+"]
          [:span.ca-count (str (count (:nodes data)) " vars · " (count (:edges data)) " calls")]]
         [:div.ca-legend
          (for [[i f] (map-indexed vector folders)] ^{:key f} [:span {:style {"--c" (nth colours (mod i (count colours)))}} [:i] (if (seq f) f "(top)")])]
         [:div.ca-wrap {:ref #(set! (.-current wrap) %)}
          [:svg.ca-arrows {:width (:w size) :height (:h size)}
           [:defs (for [c ["muted" "accent"]]
                    ^{:key c} [:marker {:id (str "ca-a-" c) :viewBox "0 0 8 8" :refX 7 :refY 4 :markerWidth 7 :markerHeight 7 :orient "auto"}
                               [:path {:d "M0,0L8,4L0,8z" :fill (str "var(--" c ")")}]])]
           (for [p (remove hot? paths)] ^{:key (str (:a p) ">" (:b p))} [:path.e {:d (:d p) :class (when (:back p) "back") :marker-end "url(#ca-a-muted)"}])
           (for [p (filter hot? paths)] ^{:key (str (:a p) ">" (:b p))} [:path.e.hot {:d (:d p) :class (when (:back p) "back") :marker-end "url(#ca-a-accent)"}])]
          [:div.ca-cols
           (for [c cols]
             ^{:key (:k c)}
             [:div.ca-col
              [:div.ca-dl (cond (neg? (:k c)) (str (- (:k c)) " up") (zero? (:k c)) "start" :else (str (:k c) " down"))]
              [:div.ca-stack
               (for [n (:nodes c) :let [mk (mark-of (:id n))]]
                 ^{:key (:id n)}
                 [:button.ca-box {:type "button" :ref #(if % (.set (.-current boxes) (:id n) %) (.delete (.-current boxes) (:id n)))
                                  :class [(when (= (:id n) picked) "sel") (when (= (:id n) (:start data)) "start")]
                                  :style {"--c" (colour-of n)} :on-click #(set-sel (:id n))
                                  :title (str (str/replace (:id n) #"^file:" "") (when (:note mk) (str "\n● " (:note mk))))}
                  [:b (when mk [:i.ca-dot {:style {:background (mark-colour mk)}}]) (:name n) (when (:private n) [:<> " " [:em "private"]])]
                  [:span (str (last (str/split (:file n) #"/")) ":" (:row n))]])
               (when-let [cut (or (get (:cut data) (keyword (str (:k c)))) (get (:cut data) (str (:k c))))]
                 [:div.f-note.ca-cut (str "+ " cut " more")])]])]]
         (when picked [:f> card src picked marks nil (fn [to _] (set-sel to)) nil nil])]))))

;; ---- the form ----

(def ^:private views
  [{:v "unfold" :label "A · Unfold" :hint "Click a blue call: its code opens right under that line; click again to close. \"called by\" goes up."}
   {:v "neighbours" :label "B · Neighbours" :hint "Who calls it on the left (with the calling line), what it calls on the right (with its doc line). Click anything to move there."}
   {:v "map" :label "C · Map" :hint "Columns are steps away from the start. Colour is the folder. Click a box: its code shows below and its arrows light up."}])

(defn- view-of [v] (if (#{"neighbours" "map"} v) v "unfold"))
(defn- depth [v d] (if (and (number? v) (>= v 0)) (min 8 (js/Math.floor v)) d))

(defn calls-form [data {:keys [server chat]}]
  (let [demo (when (map? (:demo data)) (:demo data))
        root (if (string? (:root data)) (:root data) "")
        open (str/trim (str (:open data)))
        live (and (not demo) (seq root) chat)
        url-of (fn [q] (api/url server (str "chats/" (js/encodeURIComponent chat) "/calls") (merge {:root root} q)))
        [fp set-fp] (h/use-state nil)
        [error set-error] (h/use-state nil)
        [view set-view] (h/use-state (view-of (:view data)))
        [root-var set-root-var] (h/use-state open)
        [up set-up] (h/use-state (depth (:up data) 2))
        [down set-down] (h/use-state (depth (:down data) 3))]
    (h/use-effect (fn [] (set-view (view-of (:view data))) nil) [(:view data)])
    (h/use-effect (fn [] (set-root-var open) nil) [open])
    (h/use-effect (fn [] (set-up (depth (:up data) 2)) nil) [(:up data)])
    (h/use-effect (fn [] (set-down (depth (:down data) 3)) nil) [(:down data)])
    ;; A new source (an empty cache) whenever the project's fingerprint changes.
    (h/use-effect
     (fn []
       (when live
         (let [gone (atom false) timer (atom nil) known (atom nil)
               ask (fn ask []
                     (-> (if js/document.hidden (js/Promise.resolve nil)
                             (-> (api/fetch-json (url-of {:fp @known}))
                                 (.then (fn [j] (when-not @gone (set-error nil) (when-not (:same j) (reset! known (:fp j)) (set-fp (:fp j))))))
                                 (.catch (fn [e] (when-not @gone (set-error (.-message e)))))))
                         (.then (fn [_] (when-not @gone (reset! timer (js/setTimeout ask poll-ms)))))))]
           (ask)
           #(do (reset! gone true) (js/clearTimeout @timer)))))
     [live root server chat])
    (let [src (h/use-memo #(cond demo (demo-source demo) (and live fp) (live-source url-of)) [(some? demo) live root fp])
          marks (filterv #(string? (:var %)) (items (:marks data)))]
      (cond
        error [:p.f-note.cl-err error]
        (not (seq open)) [:p.f-note "No var to start from (open: \"ns/var\")."]
        (not src) [:p.f-note (if live "Reading the project with clj-kondo…" "Open this chat to see its calls.")]
        :else
        [:div.ca-form
         [:div.ca-head
          [:div.ca-tabs {:role "tablist"}
           (for [x views]
             ^{:key (:v x)} [:button {:type "button" :role "tab" :aria-selected (= view (:v x)) :class (when (= view (:v x)) "on") :on-click #(set-view (:v x))} (:label x)])]
          [:span.ca-where {:title open} (str (if demo "demo" (or (last (filter seq (str/split root #"/"))) "project")) " · " (short-id open))]]
         [:p.ca-hint (:hint (some #(when (= (:v %) view) %) views))]
         [:div.ca-body
          (case view
            "unfold" [:<>
                      (when (not= root-var open)
                        [:button.ca-fold.ca-back {:type "button" :on-click #(set-root-var open)} (str "↺ back to " (short-id open))])
                      ^{:key root-var} [:f> unfold-card src root-var marks true set-root-var]]
            "neighbours" [:f> neighbours src open marks]
            [:f> call-map src open marks up down set-up set-down])]]))))
