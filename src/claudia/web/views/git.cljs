(ns claudia.web.views.git
  "The git form: a repo's commit graph in chalk lanes, read live through the runner
  (claudia.slave.git) and asked again every few seconds with the graph's fingerprint, so it redraws
  when the repo changes. A click on a commit opens its files and diff beside the graph. Read only."
  (:require [clojure.string :as str]
            [claudia.web.hooks :as hooks]
            [reagent.hooks :as h]
            [claudia.shared.git-layout :as layout]
            [claudia.web.api :as api]
            [claudia.web.shiki :as shiki]
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
      (or (and (>= (count rev) 4) (str/starts-with? (:hash c) rev)) (some #(= (:name %) rev) (:refs c)))))

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

(defn- pieces
  "A line's Shiki tokens cut where the marked words start and end: [[token-or-nil text hot?]]."
  [^js toks text words]
  (let [toks (if (and toks (pos? (.-length toks))) (array-seq toks) [#js {:content text}])]
    (if-not words
      (map (fn [^js t] [t (.-content t) false]) toks)
      (loop [ts toks at 0 out []]
        (if-let [^js t (first ts)]
          (let [content (.-content t)
                n (count content)
                out (loop [s 0 out out]
                      (if (>= s n)
                        out
                        (let [pos (+ at s)
                              w (some (fn [[p q]] (when (and (>= pos p) (< pos q)) [p q])) words)
                              nxt (if w (second w) (reduce min js/Infinity (filter #(> % pos) (map first words))))
                              len (min (- n s) (- nxt pos))]
                          (recur (+ s len) (conj out [t (subs content s (+ s len)) (boolean w)])))))]
            (recur (rest ts) (+ at n) out))
          out)))))

(defn diff-view [patch path]
  (let [rows (h/use-memo #(parse-patch patch) [patch])
        lines (filter #(= :line (:kind %)) rows)
        lang (code/ext-of path)
        tokens (shiki/use-tokens (h/use-memo #(str/join "\n" (map :text lines)) [rows]) lang)]
    (into [:div.f-lines.g-diff]
          (first
           (reduce (fn [[out k] [i r]]
                     (if (= :hunk (:kind r))
                       [(conj out ^{:key i} [:div.f-fold.g-hunk (str "⋯ " (:text r))]) k]
                       [(conj out ^{:key i}
                              [:div.f-ln {:class (case (:sign r) "+" "add" "-" "del" nil)}
                               [:span.num (or (:n r) "")]
                               [:span.sign (if (= "-" (:sign r)) "−" (:sign r))]
                               (if (seq (:text r))
                                 (into [:code]
                                       (map-indexed (fn [j [^js t txt hot]]
                                                      ^{:key j} [:span {:class (when hot "w")
                                                                        :style (merge {:color (some-> t .-color)} (shiki/font-style (some-> t .-fontStyle)))}
                                                                 txt])
                                                    (pieces (shiki/line tokens k) (:text r) (:words r))))
                                 [:code " "])])
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


(defn split-patches
  "A commit's whole diff as {path its-section}, by the +++ path (the --- one for a deleted file)."
  [diff]
  (into {} (for [sec (str/split (or diff "") #"(?m)^(?=diff --git )")
                 :let [plus (second (re-find #"(?m)^\+\+\+ (.+)$" sec))
                       p (if (and plus (not= plus "/dev/null")) plus (second (re-find #"(?m)^--- (.+)$" sec)))]
                 :when p]
             [(str/replace p #"\t.*$" "") sec])))

(defn small-commit?
  "A commit small enough to open every file's diff at once."
  [files stats]
  (and (<= (count files) 8) (<= (+ (:additions stats) (:deletions stats)) 600)))

(defn toggle-shut
  "The files closed by hand after a click on path p (nil at first: the small commit's none, else all)."
  [shut small files p]
  (let [s (or shut (if small #{} (set (map :path files))))] (if (s p) (disj s p) (conj s p))))

(defn- use-commit [url-of rev fp]
  (let [[d set-d] (h/use-state nil)]
    (h/use-effect
     (fn []
       (let [gone (atom false)]
         (-> (api/fetch-json (url-of {:commit rev}))
             (.then #(when-not @gone (set-d %)))
             (.catch #(when-not @gone (set-d {:error (.-message %)}))))
         #(reset! gone true)))
     [rev fp])
    d))

(defn- commit-head [cmeta stats close]
  [:div.g-detail-head
   [:div
    [:b.g-detail-subj (:subject cmeta)]
    [:div.g-meta
     (when-not (#{"WORKING" "RANGE"} (:hash cmeta))
       [:<> [:span.h (subs (str (:hash cmeta)) 0 10)] (str " · " (:author cmeta) " · " (.toLocaleString (js/Date. (:date cmeta))) " · ")])
     (str (:files stats) " file" (when (not= 1 (:files stats)) "s") " ")
     [:span.st-A (str "+" (:additions stats))] " " [:span.st-D (str "−" (:deletions stats))]
     (when (> (count (:parents cmeta)) 1) " · merge (diff against its first parent)")]]
   [:button.f-copy {:type "button" :on-click close} "close"]])

(defn- commit-file [f open toggle patch-view]
  [:div.g-file
   [:button.g-file-row {:type "button" :class (when open "open") :on-click #(toggle (:path f))}
    [:span {:class (st-class (:status f))} (if (= "?" (:status f)) "U" (:status f))]
    [:span.nm (str (when (:oldPath f) (str (:oldPath f) " → ")) (:path f))]
    (if (:binary f) [:span.g-meta-in "binary"] [:<> [:span.st-A (str "+" (:additions f))] [:span.st-D (str "−" (:deletions f))]])]
   (when (and open (not (:binary f))) patch-view)])

(defn- commit-view [url-of rev fp close]
  (let [d (use-commit url-of rev fp)
        [shut set-shut] (h/use-state nil)
        patches (h/use-memo #(split-patches (:diff d)) [d])]
    (cond
      (nil? d) [:div.g-detail [:p.f-note "…"]]
      (:error d) [:div.g-detail [:p.f-note.f-code-err (:error d)]]
      :else
      (let [{cmeta :meta files :files stats :stats} d
            small (small-commit? files stats)
            open? #(if shut (not (shut %)) small)
            toggle (fn [p] (set-shut #(toggle-shut % small files p)))]
        [:div.g-detail
         (commit-head cmeta stats close)
         (when (seq (:body cmeta)) [:pre.g-body (:body cmeta)])
         (when (empty? files) [:p.f-note "No file changes."])
         (for [f files]
           (with-meta (commit-file f (open? (:path f)) toggle [:f> file-patch url-of rev f (get patches (:path f))])
             {:key (:path f)}))]))))

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

(defn branch-colours
  "Each branch's lane colour, from the commit its ref points at."
  [commits]
  (into {} (for [c commits r (:refs c) :when (not (#{"tag" "detached"} (:type r)))] [(:name r) (lane-colour (:colorKey c))])))

(defn- branch-button [b on c on-toggle]
  (let [[k say] (get kind-mark (:kind b) (kind-mark "local"))]
    [:button.g-branch {:type "button" :class [(when (:current b) "current") (when-not on "off")]
                       :title (str (:name b) " · " say (when (:upstream b) (str " (tracks " (:upstream b) ")")) " · click to " (if on "hide" "show"))
                       :on-click #(on-toggle (:name b))}
     [:span.k k]
     [:i {:style {:background (if (and on c) c "transparent") :border-color (if (and on c) c "var(--muted)")}}]
     (:name b)
     (sync-mark b)]))

(defn- branch-bar [branches commits only on-toggle on-all]
  (let [colours (branch-colours commits)]
    (when (seq branches)
      [:div.g-branches
       (for [[i b] (map-indexed vector branches)]
         ^{:key (:name b)}
         [:<>
          (when (and (not (:local b)) (pos? i) (:local (nth branches (dec i)))) [:span.g-sep])
          (branch-button b (or (nil? only) (some #{(:name b)} only)) (colours (:name b)) on-toggle)])
       (when only [:button.f-copy {:type "button" :on-click on-all} "show all"])])))

;; ---- the commit graph ----

(defn graph-geometry
  "Where the graph's rows go: an uncommitted row first when the tree is dirty, rows pushed apart by
  the time between commits. {:head-commit :working :off :row-of :n :gap :tops :height :width}."
  [{:keys [commits head dirty] :as log} now]
  (let [head-commit (some #(when (= (:hash %) head) %) commits)
        working (and (pos? (:total dirty 0)) head-commit)
        off (if working 1 0)
        n (+ (count commits) off)
        times (vec (concat (when working [(if (:demo? log) (:time head-commit) now)]) (map :time commits)))
        gap (vec (map-indexed (fn [r t] (if (pos? r) (max 0 (- (times (dec r)) t)) 0)) times))
        tops (vec (reductions (fn [top r] (+ top row-h (gap-px (gap r)))) 0 (range 1 n)))]
    {:head-commit head-commit :working working :off off :n n :gap gap :tops tops
     :row-of (into {} (map-indexed (fn [i c] [(:hash c) (+ i off)])) commits)
     :height (if (pos? n) (+ (peek tops) row-h) 0)
     :width (+ (* (inc (reduce max 0 (map #(or (:col %) 0) commits))) lane-w) pad)}))

(defn lane-x [col] (+ (/ pad 2) (* (or col 0) lane-w) (/ lane-w 2)))

(defn- row-y [tops r] (+ (tops r) (/ row-h 2)))

(defn- gap-labels [gap tops]
  (for [[r g] (map-indexed vector gap) :when (>= (gap-px g) 18)]
    ^{:key (str "g" r)} [:span.g-gap {:style {:top (- (tops r) (/ (gap-px g) 2) 8)} :title (str (ago g) " between these commits")} (ago g)]))

(defn- lane-paths [commits {:keys [working head-commit row-of off tops width height]}]
  [:g.g-paths
   [:rect {:width width :height height :fill "none"}]
   (when working [:path {:d (str "M" (lane-x (:col head-commit)) " " (row-y tops 0) "V" (row-y tops 1)) :style {:stroke working-colour} :stroke-dasharray "3 4"}])
   (for [c commits p (:parents c) :let [j (row-of p)] :when j
         :let [parent (nth commits (- j off))
               x1 (lane-x (:col c)) y1 (row-y tops (row-of (:hash c))) x2 (lane-x (:col parent)) y2 (row-y tops j) mid (/ (+ y1 y2) 2)]]
     ^{:key (str (:hash c) p)}
     [:path {:d (str "M" x1 " " y1 "C" x1 " " mid "," x2 " " mid "," x2 " " y2) :style {:stroke (lane-colour (:colorKey parent))}}])])

(defn- lanes-svg [commits head {:keys [working head-commit off tops width height] :as geo}]
  [:svg.g-lanes {:width width :height height :style {:left gutter}}
   (lane-paths commits geo)
   (when working [:circle.node.working {:cx (lane-x (:col head-commit)) :cy (row-y tops 0) :r 5.5 :style {:stroke working-colour}}])
   (for [[i c] (map-indexed vector commits)]
     ^{:key (:hash c)}
     [:circle.node {:class (when (= (:hash c) head) "head") :cx (lane-x (:col c)) :cy (row-y tops (+ i off)) :r (if (= (:hash c) head) 6.5 4.5)
                    :style {:stroke (lane-colour (:colorKey c))}}])])

(defn dirty-summary
  "\"2 staged · 1 untracked\": what the uncommitted changes are, the kinds there are."
  [dirty]
  (str/join " · " (remove nil? [(when (pos? (:staged dirty 0)) (str (:staged dirty) " staged"))
                                (when (pos? (:unstaged dirty 0)) (str (:unstaged dirty) " unstaged"))
                                (when (pos? (:untracked dirty 0)) (str (:untracked dirty) " untracked"))])))

(defn- working-row [dirty open-hash w-mark on-open]
  [:button.g-row.working {:type "button" :class [(when (= open-hash "WORKING") "open") (when w-mark "marked")]
                          :style {:top 0 "--mark" (or (code/color-var (:color w-mark)) "var(--amber)")}
                          :on-click #(on-open "WORKING") :data-item (:id w-mark)}
   [:span.g-subj [:span.g-ref.working "uncommitted"] (str (:total dirty) " change" (when (not= 1 (:total dirty)) "s"))
    [:span.g-meta-in (dirty-summary dirty)]]
   [:span.g-meta
    (if (:note w-mark)
      [:span.g-note (:note w-mark)]
      [:<>
       (for [f (take 4 (:files dirty))] ^{:key (:path f)} [:span.g-wf {:class (st-class (:status f))} (last (str/split (:path f) #"/" -1))])
       (when (> (count (:files dirty)) 4) [:span (str "+" (- (count (:files dirty)) 4))])])]])

(defn- commit-row [c top head open-hash m on-open]
  (let [colour (lane-colour (:colorKey c))]
    [:button.g-row {:type "button" :class [(when (= (:hash c) head) "is-head") (when (= open-hash (:hash c)) "open") (when m "marked")]
                    :style {:top top "--mark" (or (code/color-var (:color m)) "var(--amber)")}
                    :on-click #(on-open (:hash c)) :data-item (:id m) :title (:subject c)}
     (into [:span.g-subj] (concat (for [r (:refs c)] ^{:key (str (:type r) (:name r))} [ref-chip r colour]) [(:subject c)]))
     [:span.g-meta [:span.h (subs (str (:hash c)) 0 (min 7 (count (:hash c))))] (str " · " (:author c) " · " (:when c))
      (when (:note m) [:span.g-note (:note m)])]]))

(defn- graph [{:keys [commits head dirty] :as log} open-hash mark-of on-open]
  (let [{:keys [working off tops height width gap] :as geo} (graph-geometry log (/ (js/Date.now) 1000))]
    [:div.g-graph {:style {:height height "--lanes" (str (+ gutter width) "px")}}
     (gap-labels gap tops)
     (lanes-svg commits head geo)
     (when working (working-row dirty open-hash (mark-of "WORKING") on-open))
     (for [[i c] (map-indexed vector commits)]
       (with-meta (commit-row c (tops (+ i off)) head open-hash (mark-of c) on-open) {:key (:hash c)}))]))

;; ---- the form ----

(defn demo-log
  "The demo's log as the runner gives it: its commits laid out in lanes."
  [demo]
  (update (assoc demo :demo? true) :commits (fn [cs] (layout/layout-graph (mapv (fn [c] (update c :refs items)) (items cs))))))

(defn branches-of
  "The branch list shown; the demo names none: its refs are the branches."
  [shown]
  (or (:branches shown)
      (vec (distinct (for [c (:commits shown) r (:refs c) :when (#{"head" "branch" "remote"} (:type r))]
                       {:name (:name r) :local (not= "remote" (:type r)) :kind (if (= "remote" (:type r)) "remote" "local")
                        :current (= "head" (:type r))})))))

(defn toggle-branch
  "The branches shown after a click on nm: nil (all) when every one or none would be."
  [branches only nm]
  (let [all (map :name branches) cur (or only all)
        picked (if (some #{nm} cur) (vec (remove #{nm} cur)) (conj (vec cur) nm))]
    (when-not (or (= (count picked) (count all)) (empty? picked)) picked)))

(defn mark-of-commit
  "The mark on commit c (or on \"WORKING\", the uncommitted row)."
  [marks head c]
  (some #(when (and (string? (:commit %)) (if (= c "WORKING") (= (:commit %) "WORKING") (matches? c (:commit %) head))) %) marks))

(defn open-hash-of
  "The full hash of the commit open names (a hash prefix, a ref, HEAD or WORKING)."
  [open shown]
  (when (and open shown)
    (if (= open "WORKING") "WORKING" (or (some #(when (matches? % open (:head shown)) (:hash %)) (:commits shown)) open))))

(defn repo-parts
  "The repo's path for the head, ~ for the home folder: [folder name]."
  [repo]
  (let [home (str/replace repo #"^/home/[^/]+" "~")
        cut (inc (or (str/last-index-of home "/") -1))]
    [(subs home 0 cut) (subs home cut)]))

(defn- git-head [demo repo shown live]
  (let [[folder nm] (repo-parts repo)]
    [:div.f-code-head
     [:span.f-file {:title repo} [:span.dir (if demo "~/Work/apps/" folder)] [:b (if demo "shop" nm)]]
     (when shown [:span.g-on {:title (if (:detached shown) "HEAD is not on a branch" "the checked-out branch")}
                  (if (:detached shown) "detached" (str "⎇ " (:currentBranch shown)))])
     (when (:fetched shown)
       [:span.g-fetched {:title (str "last fetch: " (.toLocaleString (js/Date. (:fetched shown))))}
        (str "fetched " (ago (/ (- (js/Date.now) (js/Date.parse (:fetched shown))) 1000)) " ago")])
     (when live [:span.f-kind.real {:title "Read live from the repo; redraws when it changes"} "live"])
     (when demo [:span.f-kind.made "example"])]))

(defn- git-main [{:keys [demo shown log url-of open set-open limit set-limit open-hash mark-of]}]
  [:div.g-main {:class (when open "split")}
   [:div.g-scroll
    (if (seq (:commits shown))
      [graph shown open-hash mark-of #(set-open (if (= open-hash %) nil %))]
      [:p.f-note "No commits yet."])
    (when (:more shown) [:button.f-fold {:type "button" :on-click #(set-limit (+ limit 100))} "⋯ more commits"])]
   (when open
     (if demo
       [:p.f-note.g-detail-note "A commit's files and diff open here."]
       ^{:key open} [:f> commit-view url-of open (when (= open "WORKING") (:fp log)) #(set-open nil)]))])

(defn- use-git-choices
  "The branches shown, how many commits and the open commit, each following the form's data."
  [data]
  (let [[only set-only] (h/use-state (when (sequential? (:branches data)) (mapv str (:branches data))))
        [limit set-limit] (h/use-state (if (pos? (:limit data 0)) (:limit data) 100))
        [open set-open] (h/use-state (when (string? (:commit data)) (:commit data)))]
    (h/use-effect (fn [] (when (sequential? (:branches data)) (set-only (mapv str (:branches data)))) nil) [(:branches data)])
    (h/use-effect (fn [] (when (pos? (:limit data 0)) (set-limit (:limit data))) nil) [(:limit data)])
    (h/use-effect (fn [] (when (string? (:commit data)) (set-open (:commit data))) nil) [(:commit data)])
    {:only only :set-only set-only :limit limit :set-limit set-limit :open open :set-open set-open}))

(defn git-form [data {:keys [server chat]}]
  (let [demo (when (map? (:demo data)) (:demo data))
        repo (if (string? (:repo data)) (:repo data) "")
        live (and (not demo) (seq repo) chat)
        [log set-log] (h/use-state nil)
        {:keys [only set-only limit open] :as choices} (use-git-choices data)
        url-of (fn [q] (api/url server (str "chats/" (js/encodeURIComponent chat) "/git") (merge {:repo repo} q)))
        error (hooks/use-poll {:on? live :ms poll-ms :on-answer set-log
                               :url-of #(url-of {:limit limit :branches (when only (str/join "," only)) :fp %})}
                              [live repo server chat limit only])
        shown (h/use-memo #(if demo (demo-log demo) log) [demo log])
        branches (branches-of shown)
        marks (items (:marks data))]
    [:div.g-git
     (git-head demo repo shown live)
     (when error [:p.f-note.f-code-err error])
     (cond
       (and (not live) (not demo)) [:p.f-note "Open this chat to see its repo."]
       (not shown) (when-not error [:p.f-note "…"])
       :else
       [:<>
        [branch-bar branches (:commits shown) only #(set-only (toggle-branch branches only %)) #(set-only nil)]
        (git-main (assoc choices :demo demo :shown shown :log log :url-of url-of
                         :open-hash (open-hash-of open shown) :mark-of #(mark-of-commit marks (:head shown) %)))])]))
