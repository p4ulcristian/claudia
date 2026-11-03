(ns claudia.web.views.git
  "The git form: a repo's commit graph in chalk lanes, read live through the runner
  (claudia.slave.git) and asked again every few seconds with the graph's fingerprint, so it redraws
  when the repo changes. A click on a commit opens its files and diff beside the graph. Read only."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.api :as api]
            [claudia.web.hl :as hl]
            [claudia.web.views.code :as code]))

(def ^:private poll-ms 2500)
(def ^:private row-h 50)
(def ^:private lane-w 18)
(def ^:private pad 8)
(def ^:private gutter 34)
(def ^:private lane-colours ["var(--green)" "var(--blue)" "var(--violet)" "var(--amber)" "var(--red)" "var(--c-teal)" "var(--c-pink)"
                             "var(--c-orange)" "var(--c-lime)" "var(--c-sky)" "var(--c-purple)" "var(--c-yellow)" "var(--c-cyan)" "var(--c-magenta)"])
(def ^:private grey "color-mix(in srgb, var(--muted) 55%, transparent)")
(defn- lane-colour [k] (if (number? k) (nth lane-colours (mod k (count lane-colours))) grey))
(def ^:private working-colour "var(--amber)")
(def ^:private items code/items)

(def ^:private gap-min (* 10 60))
(defn- gap-px [s] (if (> s gap-min) (min 110 (* 12 (js/Math.log2 (/ s gap-min)))) 0))
(defn- ago [s]
  (let [m (/ s 60) hr (/ m 60) d (/ hr 24)]
    (cond (< m 60) (str (js/Math.round m) "m") (< hr 24) (str (js/Math.round hr) "h") (< d 14) (str (js/Math.round d) "d")
          (< d 63) (str (js/Math.round (/ d 7)) "w") (< d 548) (str (js/Math.round (/ d 30.4)) "mo") :else (str (.toFixed (/ d 365) 1) "y"))))

(defn- matches? [c rev head]
  (if (= rev "HEAD") (= (:hash c) head)
      (or (and (>= (count rev) 2) (str/starts-with? (:hash c) rev)) (some #(= (:name %) rev) (:refs c)))))

;; ---- the demo's layout (the runner lays out live graphs) ----

(defn- layout [commits]
  (let [present (set (map :hash commits))
        free #(or (first (keep-indexed (fn [i x] (when (nil? x) i)) %)) (count %))
        put (fn [ls i v] (assoc (if (>= i (count ls)) (conj ls nil) ls) i v))
        with-cols (loop [cs commits lanes [] out []]
                    (if-let [c (first cs)]
                      (let [col (or (first (keep-indexed #(when (= %2 (:hash c)) %1) lanes)) (free lanes))
                            lanes (put (mapv #(if (= % (:hash c)) nil %) lanes) col nil)
                            ps (filter present (:parents c))
                            lanes (if (empty? ps) lanes
                                      (reduce (fn [ls p] (if (some #{p} ls) ls (put ls (free ls) p))) (put lanes col (first ps)) (rest ps)))]
                        (recur (rest cs) lanes (conj out (assoc c :col col))))
                      out))
        idx (into {} (map-indexed (fn [i c] [(:hash c) i])) with-cols)
        tips (filter #(some (fn [r] (#{"branch" "head" "remote"} (:type r))) (:refs %)) with-cols)]
    (first (reduce (fn [[cs n] tip]
                     (loop [cs cs i (idx (:hash tip))]
                       (if (and i (nil? (:colorKey (nth cs i))))
                         (recur (assoc-in cs [i :colorKey] n) (some-> (first (:parents (nth cs i))) idx))
                         [cs (inc n)])))
                   [(mapv #(assoc % :colorKey nil) with-cols) 0] tips))))

;; ---- a unified diff, with the changed words marked ----

(defn- word-diff [a b]
  (let [split #(or (re-seq #"\s+|\w+|[^\w\s]" %) [])
        x (vec (split a)) y (vec (split b))
        nx (count x) ny (count y)]
    (when (<= (* nx ny) 40000)
      (let [L (make-array (inc nx))]
        (dotimes [i (inc nx)] (aset L i (js/Uint16Array. (inc ny))))
        (doseq [i (range (dec nx) -1 -1) j (range (dec ny) -1 -1)]
          (aset (aget L i) j (if (= (x i) (y j)) (inc (aget (aget L (inc i)) (inc j))) (max (aget (aget L (inc i)) j) (aget (aget L i) (inc j))))))
        (let [push (fn [r s e] (if (and (seq r) (= (second (peek r)) s)) (conj (pop r) [(first (peek r)) e]) (conj r [s e])))
              [ra rb] (loop [i 0 j 0 pa 0 pb 0 ra [] rb []]
                        (cond
                          (and (< i nx) (< j ny) (= (x i) (y j))) (recur (inc i) (inc j) (+ pa (count (x i))) (+ pb (count (y j))) ra rb)
                          (and (< j ny) (or (>= i nx) (>= (aget (aget L i) (inc j)) (aget (aget L (inc i)) j))))
                          (recur i (inc j) pa (+ pb (count (y j))) ra (push rb pb (+ pb (count (y j)))))
                          (< i nx) (recur (inc i) j (+ pa (count (x i))) pb (push ra pa (+ pa (count (x i)))) rb)
                          :else [ra rb]))
              whole? (fn [r s] (> (reduce + (map (fn [[p q]] (- q p)) r)) (* 0.7 (count (str/trim s)))))]
          [(when (and (seq ra) (not (whole? ra a))) ra) (when (and (seq rb) (not (whole? rb b))) rb)])))))

(defn- parse-patch [patch]
  (let [rows (loop [ls (str/split-lines patch) n 0 in? false out []]
               (if-let [l (first ls)]
                 (if-let [[_ _ nn tail] (re-matches #"^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)$" l)]
                   (recur (rest ls) (js/Number nn) true (conj out {:kind :hunk :text (str "line " nn (when (seq tail) (str " ·" tail)))}))
                   (cond
                     (or (not in?) (str/starts-with? l "\\")) (recur (rest ls) n in? out)
                     (str/starts-with? l "+") (recur (rest ls) (inc n) in? (conj out {:kind :line :sign "+" :n n :text (subs l 1)}))
                     (str/starts-with? l "-") (recur (rest ls) n in? (conj out {:kind :line :sign "-" :n nil :text (subs l 1)}))
                     (str/starts-with? l " ") (recur (rest ls) (inc n) in? (conj out {:kind :line :sign "" :n n :text (subs l 1)}))
                     :else (recur (rest ls) n in? out)))
                 out))]
    ;; Each run of removed lines followed by added lines is paired line by line; the words that
    ;; differ in each pair are marked.
    (loop [i 0 rows rows]
      (if (>= i (count rows))
        rows
        (let [run (fn [from sign] (take-while #(and (< % (count rows)) (= sign (:sign (nth rows %)))) (iterate inc from)))
              del (vec (run i "-"))
              add (vec (run (+ i (count del)) "+"))]
          (if (and (empty? del) (empty? add))
            (recur (inc i) rows)
            (recur (+ i (count del) (count add))
                   (reduce (fn [rows k]
                             (let [[wa wb] (word-diff (:text (nth rows (del k))) (:text (nth rows (add k))))]
                               (cond-> rows wa (assoc-in [(del k) :words] wa) wb (assoc-in [(add k) :words] wb))))
                           rows (range (min (count del) (count add)))))))))))

(defn- marked-text [text words]
  (loop [pos 0 ws words out []]
    (if-let [[p q] (first ws)]
      (recur q (rest ws) (conj out (subs text pos p) ^{:key p} [:span.w (subs text p q)]))
      (conj out (subs text pos)))))

(defn diff-view [patch path]
  (let [rows (h/use-memo #(parse-patch patch) [patch])
        lines (filter #(= :line (:kind %)) rows)
        coloured (h/use-memo #(hl/lines (str/join "\n" (map :text lines)) (hl/ext-of path)) [rows path])]
    (into [:div.f-lines.g-diff]
          (first
           (reduce (fn [[out k] [i r]]
                     (if (= :hunk (:kind r))
                       [(conj out ^{:key i} [:div.f-fold.g-hunk (str "⋯ " (:text r))]) k]
                       [(conj out ^{:key i}
                              [:div.f-ln {:class (case (:sign r) "+" "add" "-" "del" nil)}
                               [:span.num (or (:n r) "")]
                               [:span.sign (if (= "-" (:sign r)) "−" (:sign r))]
                               (cond
                                 (not (seq (:text r))) [:code " "]
                                 (:words r) (into [:code] (marked-text (:text r) (:words r)))
                                 (nth coloured k nil) [:code {:dangerouslySetInnerHTML (r/unsafe-html (nth coloured k))}]
                                 :else [:code (:text r)])])
                        (inc k)]))
                   [[] 0] (map-indexed vector rows))))))

(defn- st-class [s] (str "st st-" (case s "?" "U" "D" "D" "A" "A" "M")))

(defn- file-patch [url-of rev f patch]
  (let [[text set-text] (h/use-state patch)]
    (h/use-effect
     (fn []
       (cond (some? patch) (set-text patch)
             (not (:omitted f)) (set-text "")
             :else (-> (api/fetch-json (url-of {:commit rev :file (:path f)}))
                       (.then #(set-text (or (:diff %) "")))
                       (.catch (fn [_] (set-text "")))))
       nil)
     [patch (:path f)])
    (cond (nil? text) [:p.f-note "…"]
          (seq text) [:f> diff-view text (:path f)]
          :else [:p.f-note "No text changes."])))

(defn- commit-view [url-of rev fp close]
  (let [[d set-d] (h/use-state nil)
        [shut set-shut] (h/use-state nil)]
    (h/use-effect
     (fn []
       (let [gone (atom false)]
         (-> (api/fetch-json (url-of {:commit rev}))
             (.then #(when-not @gone (set-d %)))
             (.catch #(when-not @gone (set-d {:error (.-message %)}))))
         #(reset! gone true)))
     [rev fp])
    (let [patches (h/use-memo
                   (fn []
                     (into {} (for [sec (str/split (or (:diff d) "") #"(?m)^(?=diff --git )")
                                    :let [plus (second (re-find #"(?m)^\+\+\+ (.+)$" sec))
                                          p (if (and plus (not= plus "/dev/null")) plus (second (re-find #"(?m)^--- (.+)$" sec)))]
                                    :when p]
                                [(str/replace p #"\t.*$" "") sec])))
                   [d])]
      (cond
        (nil? d) [:div.g-detail [:p.f-note "…"]]
        (:error d) [:div.g-detail [:p.f-note.f-code-err (:error d)]]
        :else
        (let [{:keys [meta files stats]} d
              small (and (<= (count files) 8) (<= (+ (:additions stats) (:deletions stats)) 600))
              open? #(if shut (not (shut %)) small)
              toggle (fn [p] (set-shut #(let [s (or % (if small #{} (set (map :path files))))] (if (s p) (disj s p) (conj s p)))))]
          [:div.g-detail
           [:div.g-detail-head
            [:div
             [:b.g-detail-subj (:subject meta)]
             [:div.g-meta
              (when-not (= "WORKING" (:hash meta))
                [:<> [:span.h (subs (str (:hash meta)) 0 10)] (str " · " (:author meta) " · " (.toLocaleString (js/Date. (:date meta))) " · ")])
              (str (:files stats) " file" (when (not= 1 (:files stats)) "s") " ")
              [:span.st-A (str "+" (:additions stats))] " " [:span.st-D (str "−" (:deletions stats))]
              (when (> (count (:parents meta)) 1) " · merge (diff against its first parent)")]]
            [:button.f-copy {:type "button" :on-click close} "close"]]
           (when (seq (:body meta)) [:pre.g-body (:body meta)])
           (when (empty? files) [:p.f-note "No file changes."])
           (for [f files]
             ^{:key (:path f)}
             [:div.g-file
              [:button.g-file-row {:type "button" :class (when (open? (:path f)) "open") :on-click #(toggle (:path f))}
               [:span {:class (st-class (:status f))} (if (= "?" (:status f)) "U" (:status f))]
               [:span.nm (str (when (:oldPath f) (str (:oldPath f) " → ")) (:path f))]
               (if (:binary f) [:span.g-meta-in "binary"] [:<> [:span.st-A (str "+" (:additions f))] [:span.st-D (str "−" (:deletions f))]])]
              (when (and (open? (:path f)) (not (:binary f)))
                [:f> file-patch url-of rev f (get patches (:path f))])])])))))

(defn- sync-mark [r]
  (cond (:gone r) [:span.g-sync.gone {:title "its upstream was deleted"} "gone"]
        (not (:tracked r)) nil
        (and (zero? (or (:ahead r) 0)) (zero? (or (:behind r) 0))) [:span.g-sync.ok {:title "same as its upstream"} "✓"]
        :else [:span.g-sync.drift {:title "commits ahead / behind its upstream"}
               (str (when (pos? (:ahead r)) (str "↑" (:ahead r))) (when (pos? (:behind r)) (str "↓" (:behind r))))]))

(defn- ref-chip [r colour]
  (let [local (#{"head" "branch"} (:type r))]
    [:span.g-ref {:class (:type r) :style (when local {"--rc" colour})}
     (str (case (:type r) "head" "⎇ " "tag" "◆ " "") (:name r))
     (cond local (if (or (:tracked r) (:gone r)) (sync-mark r) [:span.g-sync.only {:title "not on any remote"} "⌂"])
           (= "remote" (:type r)) [:span.g-sync.only {:title "only on the remote"} "☁"])]))

(def ^:private kind-mark {"both" ["⇅" "local + remote"] "local" ["⌂" "local only"] "remote" ["☁" "remote only"]})

(defn- branch-bar [branches commits only on-toggle on-all]
  (let [colours (into {} (for [c commits r (:refs c) :when (not (#{"tag" "detached"} (:type r)))] [(:name r) (lane-colour (:colorKey c))]))]
    (when (seq branches)
      [:div.g-branches
       (for [[i b] (map-indexed vector branches)
             :let [on (or (nil? only) (some #{(:name b)} only))
                   c (colours (:name b))
                   [k say] (get kind-mark (:kind b) (kind-mark "local"))]]
         ^{:key (:name b)}
         [:<>
          (when (and (not (:local b)) (pos? i) (:local (nth branches (dec i)))) [:span.g-sep])
          [:button.g-branch {:type "button" :class [(when (:current b) "current") (when-not on "off")]
                             :title (str (:name b) " · " say (when (:upstream b) (str " (tracks " (:upstream b) ")")) " · click to " (if on "hide" "show"))
                             :on-click #(on-toggle (:name b))}
           [:span.k k]
           [:i {:style {:background (if (and on c) c "transparent") :border-color (if (and on c) c "var(--muted)")}}]
           (:name b)
           (sync-mark b)]])
       (when only [:button.f-copy {:type "button" :on-click on-all} "show all"])])))

(defn- graph [{:keys [commits head dirty]} open-hash mark-of on-open]
  (let [head-commit (some #(when (= (:hash %) head) %) commits)
        working (and (pos? (:total dirty 0)) head-commit)
        off (if working 1 0)
        row-of (into {} (map-indexed (fn [i c] [(:hash c) (+ i off)])) commits)
        n (+ (count commits) off)
        times (vec (concat (when working [(/ (js/Date.now) 1000)]) (map :time commits)))
        gap (vec (map-indexed (fn [r t] (if (pos? r) (max 0 (- (times (dec r)) t)) 0)) times))
        tops (vec (reductions (fn [top r] (+ top row-h (gap-px (gap r)))) 0 (range 1 n)))
        height (if (pos? n) (+ (peek tops) row-h) 0)
        width (+ (* (inc (reduce max 0 (map #(or (:col %) 0) commits))) lane-w) pad)
        x (fn [col] (+ (/ pad 2) (* (or col 0) lane-w) (/ lane-w 2)))
        y (fn [r] (+ (tops r) (/ row-h 2)))
        w-mark (mark-of "WORKING")]
    [:div.g-graph {:style {:height height "--lanes" (str (+ gutter width) "px")}}
     (for [[r g] (map-indexed vector gap) :when (>= (gap-px g) 18)]
       ^{:key (str "g" r)} [:span.g-gap {:style {:top (- (tops r) (/ (gap-px g) 2) 8)} :title (str (ago g) " between these commits")} (ago g)])
     [:svg.g-lanes {:width width :height height :style {:left gutter}}
      [:g.g-paths
       [:rect {:width width :height height :fill "none"}]
       (when working [:path {:d (str "M" (x (:col head-commit)) " " (y 0) "V" (y 1)) :style {:stroke working-colour} :stroke-dasharray "3 4"}])
       (for [c commits p (:parents c) :let [j (row-of p)] :when j
             :let [parent (nth commits (- j off))
                   x1 (x (:col c)) y1 (y (row-of (:hash c))) x2 (x (:col parent)) y2 (y j) mid (/ (+ y1 y2) 2)]]
         ^{:key (str (:hash c) p)}
         [:path {:d (str "M" x1 " " y1 "C" x1 " " mid "," x2 " " mid "," x2 " " y2) :style {:stroke (lane-colour (:colorKey parent))}}])]
      (when working [:circle.node.working {:cx (x (:col head-commit)) :cy (y 0) :r 5.5 :style {:stroke working-colour}}])
      (for [[i c] (map-indexed vector commits)]
        ^{:key (:hash c)}
        [:circle.node {:class (when (= (:hash c) head) "head") :cx (x (:col c)) :cy (y (+ i off)) :r (if (= (:hash c) head) 6.5 4.5)
                       :style {:stroke (lane-colour (:colorKey c))}}])]
     (when working
       [:button.g-row.working {:type "button" :class [(when (= open-hash "WORKING") "open") (when w-mark "marked")]
                               :style {:top 0 "--mark" (or (code/color-var (:color w-mark)) "var(--amber)")}
                               :on-click #(on-open "WORKING") :data-item (:id w-mark)}
        [:span.g-subj [:span.g-ref.working "uncommitted"] (str (:total dirty) " change" (when (not= 1 (:total dirty)) "s"))
         [:span.g-meta-in (str/join " · " (remove nil? [(when (pos? (:staged dirty 0)) (str (:staged dirty) " staged"))
                                                        (when (pos? (:unstaged dirty 0)) (str (:unstaged dirty) " unstaged"))
                                                        (when (pos? (:untracked dirty 0)) (str (:untracked dirty) " untracked"))]))]]
        [:span.g-meta
         (if (:note w-mark)
           [:span.g-note (:note w-mark)]
           [:<>
            (for [f (take 4 (:files dirty))] ^{:key (:path f)} [:span.g-wf {:class (st-class (:status f))} (last (str/split (:path f) #"/"))])
            (when (> (count (:files dirty)) 4) [:span (str "+" (- (count (:files dirty)) 4))])])]])
     (for [[i c] (map-indexed vector commits)
           :let [m (mark-of c) colour (lane-colour (:colorKey c))]]
       ^{:key (:hash c)}
       [:button.g-row {:type "button" :class [(when (= (:hash c) head) "is-head") (when (= open-hash (:hash c)) "open") (when m "marked")]
                       :style {:top (tops (+ i off)) "--mark" (or (code/color-var (:color m)) "var(--amber)")}
                       :on-click #(on-open (:hash c)) :data-item (:id m) :title (:subject c)}
        (into [:span.g-subj] (concat (for [r (:refs c)] ^{:key (str (:type r) (:name r))} [ref-chip r colour]) [(:subject c)]))
        [:span.g-meta [:span.h (subs (str (:hash c)) 0 (min 7 (count (:hash c))))] (str " · " (:author c) " · " (:when c))
         (when (:note m) [:span.g-note (:note m)])]])]))

(defn git-form [data {:keys [server chat]}]
  (let [demo (when (map? (:demo data)) (:demo data))
        repo (if (string? (:repo data)) (:repo data) "")
        live (and (not demo) (seq repo) chat)
        [log set-log] (h/use-state nil)
        [error set-error] (h/use-state nil)
        [only set-only] (h/use-state (when (sequential? (:branches data)) (mapv str (:branches data))))
        [limit set-limit] (h/use-state (if (pos? (:limit data 0)) (:limit data) 100))
        [open set-open] (h/use-state (when (string? (:commit data)) (:commit data)))
        fp (h/use-ref nil)
        url-of (fn [q] (api/url server (str "chats/" (js/encodeURIComponent chat) "/git") (merge {:repo repo} q)))]
    (h/use-effect (fn [] (when (sequential? (:branches data)) (set-only (mapv str (:branches data)))) nil) [(:branches data)])
    (h/use-effect (fn [] (when (pos? (:limit data 0)) (set-limit (:limit data))) nil) [(:limit data)])
    (h/use-effect (fn [] (when (string? (:commit data)) (set-open (:commit data))) nil) [(:commit data)])
    (h/use-effect
     (fn []
       (when live
         (set! (.-current fp) nil)
         (let [gone (atom false) timer (atom nil)
               ask (fn ask []
                     (-> (if js/document.hidden
                           (js/Promise.resolve nil)
                           (-> (api/fetch-json (url-of {:limit limit :branches (when only (str/join "," only)) :fp (.-current fp)}))
                               (.then (fn [j] (when-not (or @gone (:same j)) (set! (.-current fp) (:fp j)) (set-log j) (set-error nil))))
                               (.catch (fn [e] (when-not @gone (set-error (.-message e)))))))
                         (.then (fn [_] (when-not @gone (reset! timer (js/setTimeout ask poll-ms)))))))]
           (ask)
           #(do (reset! gone true) (js/clearTimeout @timer)))))
     [live repo server chat limit only])
    (let [shown (h/use-memo #(if demo (update demo :commits (fn [cs] (layout (mapv (fn [c] (update c :refs items)) (items cs))))) log) [demo log])
          branches (or (:branches shown)
                       ;; The demo names no branch list: its refs are the branches.
                       (vec (distinct (for [c (:commits shown) r (:refs c) :when (#{"head" "branch" "remote"} (:type r))]
                                        {:name (:name r) :local (not= "remote" (:type r)) :kind (if (= "remote" (:type r)) "remote" "local")
                                         :current (= "head" (:type r))}))))
          marks (items (:marks data))
          mark-of (fn [c] (some #(when (and (string? (:commit %)) (if (= c "WORKING") (= (:commit %) "WORKING") (matches? c (:commit %) (:head shown)))) %) marks))
          toggle-branch (fn [nm]
                          (let [all (map :name branches) cur (or only all)
                                next (if (some #{nm} cur) (vec (remove #{nm} cur)) (conj (vec cur) nm))]
                            (set-only (when-not (or (= (count next) (count all)) (empty? next)) next))))
          open-hash (when (and open shown) (if (= open "WORKING") "WORKING" (or (some #(when (matches? % open (:head shown)) (:hash %)) (:commits shown)) open)))
          home (str/replace repo #"^/home/[^/]+" "~")
          cut (inc (or (str/last-index-of home "/") -1))]
      [:div.g-git
       [:div.f-code-head
        [:span.f-file {:title repo} [:span.dir (if demo "~/Work/apps/" (subs home 0 cut))] [:b (if demo "shop" (subs home cut))]]
        (when shown [:span.g-on {:title (if (:detached shown) "HEAD is not on a branch" "the checked-out branch")}
                     (if (:detached shown) "detached" (str "⎇ " (:currentBranch shown)))])
        (when live [:span.f-kind.real {:title "Read live from the repo; redraws when it changes"} "live"])
        (when demo [:span.f-kind.made "example"])]
       (when error [:p.f-note.f-code-err error])
       (cond
         (and (not live) (not demo)) [:p.f-note "Open this chat to see its repo."]
         (not shown) (when-not error [:p.f-note "…"])
         :else
         [:<>
          [branch-bar branches (:commits shown) only toggle-branch #(set-only nil)]
          [:div.g-main {:class (when open "split")}
           [:div.g-scroll
            (if (seq (:commits shown))
              [graph shown open-hash mark-of #(set-open (if (= open-hash %) nil %))]
              [:p.f-note "No commits yet."])
            (when (:more shown) [:button.f-fold {:type "button" :on-click #(set-limit (+ limit 100))} "⋯ more commits"])]
           (when open
             (if demo
               [:p.f-note.g-detail-note "A commit's files and diff open here."]
               ^{:key open} [:f> commit-view url-of open (when (= open "WORKING") (:fp log)) #(set-open nil)]))]])])))
