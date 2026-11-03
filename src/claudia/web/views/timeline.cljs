(ns claudia.web.views.timeline
  "The timeline on the right: every step of the chat and its subagents as one feed, top to bottom in
  time order. A thin rail per agent on the left shows who ran at the same time (a free rail is
  reused by a later agent); quiet stretches get a divider with their length; a filter shows one
  agent and the agents it started. A step opens in full under itself: Edit and Write steps as their
  diff. Read from the runner (GET /api/chats/:id/timeline), every 2 s while the chat runs."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.shared.protocol :as protocol]
            [claudia.web.md :as md]))

(def ^:private rail 14)        ; px per rail
(def ^:private idle (* 60 1000)) ; a longer quiet stretch gets a divider
(def ^:private page-rows 400)  ; rows shown at first; earlier ones on request
(def ^:private palette ["sky" "violet" "teal" "pink" "amber" "lime" "orange" "indigo" "cyan" "magenta"
                        "green" "red" "yellow" "purple" "blue" "brown"])

(defn- dur [ms]
  (let [s (js/Math.round (/ ms 1000))
        m (js/Math.floor (/ s 60))
        hr (js/Math.floor (/ m 60))]
    (cond (< s 60) (if (< ms 1000) (str (.toFixed (/ ms 1000) 1) "s") (str s "s"))
          (< m 60) (str m "m " (mod s 60) "s")
          (< hr 24) (str hr "h " (mod m 60) "m")
          :else (str (js/Math.floor (/ hr 24)) "d " (mod hr 24) "h"))))

(defn- clock [t]
  (.toLocaleTimeString (js/Date. t) #js [] #js {:hour "2-digit" :minute "2-digit" :second "2-digit" :hour12 false}))

(defn- day [t]
  (.toLocaleDateString (js/Date. t) #js [] #js {:month "short" :day "numeric" :weekday "short"}))

(defn- category [tool]
  (condp re-matches tool
    #"Read|Grep|Glob|LS|ToolSearch|NotebookRead" "read"
    #"Edit|Write|NotebookEdit|MultiEdit" "edit"
    #"Bash|Monitor|TaskStop|PowerShell" "shell"
    #"WebFetch|WebSearch" "web"
    #"Agent|Task|SendMessage|Workflow" "agent"
    "other"))

(defn- tilde [p]
  (str/replace (str p) #"^/home/[^/]+/" "~/"))

(defn- api [{:keys [server chat]} & parts]
  (str "/s/" (js/encodeURIComponent server) "/api/chats/" (js/encodeURIComponent chat) "/timeline"
       (apply str (map #(str "/" (js/encodeURIComponent %)) parts))))

(defn- get-json [url]
  (-> (js/fetch url #js {:credentials "same-origin" :headers #js {:accept "application/json"}})
      (.then (fn [^js res]
               (-> (.text res)
                   (.then (fn [t] {:status (.-status res) :ok (.-ok res)
                                   :body (when (seq t) (try (protocol/read-json t) (catch :default _ nil)))})))))))

;; ---- a step in full ----

(defn- explain
  "Explain on an opened tool step: the same plain-words explanation as on the chat's steps."
  [oc e]
  (let [k [(:server oc) (:chat oc) (:id e)]
        {:keys [busy text error open]} @(rf/subscribe [:explain k])
        ask #(rf/dispatch [:ops/explain [(:server oc) (:chat oc)] (:id e)])]
    [:div.tl-explain
     [:button.explain-btn.live {:type "button" :on-click #(when-not (or busy open) (ask))}
      (if busy "…" "explain this step")]
     (when open
       [:div.explain-note {:class [(when busy "busy") (when error "err")]}
        [:span (cond busy "explaining…" error (str "Couldn't explain: " error) :else text)]
        (when-not busy [:button.explain-x {:type "button" :aria-label "Close" :on-click ask} "×"])])]))

(defn- patch-diff [hunks]
  [:table.diff
   [:colgroup [:col.ln-col] [:col.ln-col] [:col]]
   (into [:tbody]
         (mapcat
          (fn [hi {:keys [oldStart oldLines newStart newLines lines]}]
            (let [o (atom oldStart) n (atom newStart)]
              (cons
               ^{:key (str "h" hi)}
               [:tr.hunk [:td {:col-span 3} (str "@@ -" oldStart "," oldLines " +" newStart "," newLines " @@")]]
               (doall
                (for [[li line] (map-indexed vector lines)
                      :let [c (subs line 0 1)
                            text (subs line 1)
                            [kind a b] (case c
                                         "-" ["del" (dec (swap! o inc)) nil]
                                         "+" ["add" nil (dec (swap! n inc))]
                                         "\\" ["meta" nil nil]
                                         ["ctx" (dec (swap! o inc)) (dec (swap! n inc))])]]
                  ^{:key (str hi ":" li)}
                  [:tr {:class kind} [:td.ln a] [:td.ln b] [:td text]])))))
          (range) hunks))])

(defn- detail [oc lane e]
  (let [[d set-d] (h/use-state nil)
        [failed set-failed] (h/use-state false)
        done (:end e)]
    (h/use-effect
     (fn []
       (let [dead (atom false)]
         (set-d nil)
         (set-failed false)
         (-> (get-json (api oc (:id lane) (:id e)))
             (.then (fn [{:keys [ok body]}]
                      (when-not @dead (if ok (set-d body) (set-failed true)))))
             (.catch #(when-not @dead (set-failed true))))
         #(reset! dead true)))
     [(:server oc) (:chat oc) (:id lane) (:id e) done])
    [:div.tl-detail
     (when (and (= "tool" (:k e)) (= "tool" (:k d))) [explain oc e])
     (cond
       failed [:p.muted.small "Couldn't read this from the session file."]
       (nil? d) [:p.muted.small "Loading…"]
       (= "text" (:k d)) [:div.md.small {:dangerouslySetInnerHTML (r/unsafe-html (md/html (:text d)))}]
       (:patch d)
       (let [{:keys [patch]} d]
         [:<>
          [:h4 (if (:created patch) "New file" "Diff") [:span.tl-file (tilde (:file patch))]]
          (if (seq (:hunks patch)) [patch-diff (:hunks patch)] [:p.muted.small "No lines changed."])
          (when (:cut patch) [:p.muted.small "Cut at 5000 lines."])
          [:details.tl-raw
           [:summary "Input and output"]
           [:h4 "Input"] [:pre (:input d)]
           [:h4 "Output"] [:pre (if (seq (:output d)) (:output d) "(no output)")]]])
       :else
       [:<>
        [:h4 "Input"] [:pre (:input d)]
        [:h4 "Output"]
        (if (nil? (:output d))
          [:p.muted.small (if (:running lane) "Still running." "It got no result (stopped or cut off).")]
          [:pre {:class (when (:error d) "err")} (if (seq (:output d)) (:output d) "(no output)")])])
     (when (:truncated d) [:p.muted.small "Cut at 1 MB."])]))

;; ---- one row ----

(defn- step [{:keys [oc row now rails color open? on-open on-lane lanes]}]
  (let [{:keys [lane e at]} row
        rail-style (when color {"--rail" (str "var(--c-" color ")")})
        tag (when (not= "main" (:kind lane))
              [:button.tl-tag {:style rail-style :title (str (:title lane) " (" (:kind lane) ") · show only this agent")
                               :on-click (fn [ev] (.stopPropagation ev) (on-lane (:id lane)))}
               (:title lane)])
        tool? (= "tool" (:k e))
        end (when tool? (or (:end e) (when (:running lane) now)))
        cls (if tool?
              (cond (:error e) "err" (nil? (:end e)) (if (:running lane) "busy" "lost") :else "")
              (:k e))
        started (when (and tool? (:agent e)) (some #(when (= (:id %) (:agent e)) %) lanes))]
    [:li.tl-step {:class [cls (when open? "open")]}
     [:button.tl-step-row {:on-click on-open}
      [:span.tl-time (clock at)]
      rails
      [:span.tl-body
       tag
       (if tool?
         [:<>
          [:span.tl-tool {:class (category (:name e))} (:name e)]
          [:span.tl-title (:title e)]
          (when started
            [:span.link {:role "button" :on-click (fn [ev] (.stopPropagation ev) (on-lane (:id started)))} "its steps"])]
         [:<>
          [:span.tl-who {:class (:k e)}
           (case (:k e) "user" (if (= "main" (:kind lane)) "You" "Task") "note" "Note" "Claude")]
          [:span.tl-text (:text e)]])]
      (when tool?
        [:span.tl-dur
         (when-let [{:keys [added removed]} (:diff e)]
           [:span.tl-diff {:title (str added " lines added, " removed " removed")}
            (when (pos? added) [:span.plus (str "+" added)])
            (when (pos? removed) [:span.minus (str "−" removed)])])
         (when (:error e) [:span.tl-failed "failed"])
         (if (nil? end) "no result" (str (dur (- end (:start e))) (when (nil? (:end e)) " so far")))])]
     (when open? [:f> detail oc lane e])]))

;; ---- the feed ----

(defn- rails-of
  "Each lane holds one rail from its first row to its last (to the bottom while it runs); a free
  rail is taken by a later lane. The chat keeps the first rail when it is shown."
  [rows]
  (let [first-at (reduce (fn [m [i r]] (if (contains? m (get-in r [:lane :id])) m (assoc m (get-in r [:lane :id]) i)))
                         {} (map-indexed vector rows))
        last-at (reduce (fn [m [i r]] (assoc m (get-in r [:lane :id]) (if (get-in r [:lane :running]) (count rows) i)))
                        {} (map-indexed vector rows))
        low (if (contains? first-at "main") 1 0)
        [col free] (reduce (fn [[col free] id]
                             (let [f (first-at id)
                                   c (if (= id "main")
                                       0
                                       (or (first (keep-indexed (fn [i end] (when (and (>= i low) (< end f)) i)) free))
                                           (max low (count free))))
                                   free (if (< c (count free)) (assoc free c (last-at id))
                                            (conj (into free (repeat (- c (count free)) -1)) (last-at id)))]
                               [(assoc col id c) free]))
                           [{} []]
                           (sort-by first-at (keys first-at)))]
    {:first first-at :last last-at :col col :count (max 1 (count free))}))

(defn- rail-cell [rails colors i own]
  [:span.tl-rails {:style {:width (* (:count rails) rail)}}
   (for [[id c] (:col rails)
         :let [f ((:first rails) id) l ((:last rails) id)]
         :when (<= f i l)]
     ^{:key id}
     [:span.tl-line {:class [(when (== i f) "from") (when (== i l) "to")]
                     :style (merge {:left (- (+ (* c rail) (/ rail 2)) 1)}
                                   (when-let [color (colors id)] {"--rail" (str "var(--c-" color ")")}))}])
   (when own
     [:span.tl-node {:style (merge {:left (- (+ (* ((:col rails) own) rail) (/ rail 2)) 4)}
                                   (when-let [color (colors own)] {"--rail" (str "var(--c-" color ")")}))}])])

(defn timeline-view [oc]
  (let [running (boolean (get-in oc [:state :running]))
        key* [(:server oc) (:chat oc)]
        [data set-data] (h/use-state nil)
        [missing set-missing] (h/use-state false)
        [open set-open] (h/use-state nil)  ; lane:id of the step shown in full
        [only set-only] (h/use-state nil)  ; one lane, with the agents it started
        [shown set-shown] (h/use-state page-rows)
        scroller (h/use-ref nil)
        v (h/use-ref "")
        at-end (h/use-ref true)]
    ;; Another chat: start over.
    (h/use-effect
     (fn []
       (set-data nil) (set-missing false) (set-open nil) (set-only nil) (set-shown page-rows)
       (set! (.-current v) "") (set! (.-current at-end) true)
       js/undefined)
     [key*])
    ;; Read now, then every 2 s while the chat runs (once more when it stops).
    (h/use-effect
     (fn []
       (let [dead (atom false)
             load (fn []
                    (-> (get-json (str (api oc) "?v=" (js/encodeURIComponent (.-current v))))
                        (.then (fn [{:keys [status ok body]}]
                                 (when-not @dead
                                   (cond (= 404 status) (set-missing true)
                                         (not ok) nil
                                         (:same body) (do (set-missing false) (set-data #(when % (assoc % :now (:now body)))))
                                         :else (do (set-missing false)
                                                   (set! (.-current v) (:v body))
                                                   (set-data body))))))
                        (.catch (fn [_]))))
             t (when running (js/setInterval load 2000))]
         (load)
         #(do (reset! dead true) (some-> t js/clearInterval))))
     [key* running])
    (h/use-layout-effect
     (fn []
       (when-let [el (.-current scroller)]
         (when (.-current at-end) (set! (.-scrollTop el) (.-scrollHeight el))))
       js/undefined)
     [data shown only])
    (h/use-effect
     (fn []
       (let [on-key (fn [^js e] (when (and open (= "Escape" (.-key e))) (.stopPropagation e) (set-open nil)))]
         (js/window.addEventListener "keydown" on-key true)
         #(js/window.removeEventListener "keydown" on-key true)))
     [open])
    (if (or missing (nil? data))
      [:div.tl
       [:div.tl-head [:b "Timeline"]]
       [:p.muted.tl-empty (if missing
                            "Nothing yet: the timeline fills in once Claude starts working in this chat."
                            "Loading…")]]
      (let [lanes (:lanes data)
            agents (filterv #(not= "main" (:kind %)) lanes)
            colors (into {} (map-indexed (fn [i l] [(:id l) (nth palette (mod i (count palette)))])) agents)
            kept (when only
                   (if (= only "main")
                     #{"main"}
                     ;; Lanes come parent first: one pass finds everything under the chosen one.
                     (reduce (fn [s l] (if (contains? s (:parent l)) (conj s (:id l)) s)) #{only} lanes)))
            all (->> (for [lane lanes :when (or (nil? kept) (kept (:id lane))) e (:events lane)]
                       {:lane lane :e e :at (if (= "tool" (:k e)) (:start e) (:at e))})
                     (sort-by :at)
                     vec)
            rows (if (> (count all) shown) (subvec all (- (count all) shown)) all)
            rails (rails-of rows)
            running-agents (count (filter :running agents))
            only-lane (when only (some #(when (= (:id %) only) %) lanes))]
        [:div.tl
         [:div.tl-head
          [:b "Timeline"]
          [:span.muted.small
           (if (seq agents)
             (str (count agents) (if (= 1 (count agents)) " agent" " agents")
                  (when (pos? running-agents) (str " · " running-agents " running")))
             "no agents yet")]
          (when (seq agents)
            [:select.tl-filter {:value (or only "") :title "Show one agent (and the agents it started)"
                                :on-change #(set-only (not-empty (.. % -target -value)))}
             [:option {:value ""} "Everything"]
             [:option {:value "main"} "Chat only"]
             (for [l agents]
               ^{:key (:id l)} [:option {:value (:id l)} (str (:title l) (when (:running l) " (running)"))])])
          (when only-lane [:button.link {:on-click #(set-only nil)} "show everything"])]
         [:div.tl-scroll {:ref #(set! (.-current scroller) %)
                          :on-scroll (fn [^js e]
                                       (let [el (.-currentTarget e)]
                                         (set! (.-current at-end) (>= (+ (.-scrollTop el) (.-clientHeight el)) (- (.-scrollHeight el) 40)))))}
          (when (> (count all) (count rows))
            [:button.tl-more.link {:on-click #(do (set! (.-current at-end) false) (set-shown (fn [n] (+ n page-rows))))}
             (str (- (count all) (count rows)) " earlier steps · show " (min page-rows (- (count all) (count rows))) " more")])
          (into [:ul.tl-feed]
                (mapcat
                 (fn [i row]
                   (let [prev (when (pos? i) (nth rows (dec i)))
                         k (str (get-in row [:lane :id]) ":" (get-in row [:e :id]))
                         sep (cond (and prev (not= (day (:at prev)) (day (:at row))))
                                   ^{:key (str "d" i)} [:li.tl-sep.day [rail-cell rails colors (- i 0.5) nil] [:span (day (:at row))]]
                                   (and prev (> (- (:at row) (:at prev)) idle))
                                   ^{:key (str "g" i)} [:li.tl-sep [rail-cell rails colors (- i 0.5) nil] [:span (str (dur (- (:at row) (:at prev))) " quiet")]])]
                     (keep identity
                           [sep
                            ^{:key k}
                            [step {:oc oc :row row :now (:now data) :lanes lanes
                                   :rails [rail-cell rails colors i (get-in row [:lane :id])]
                                   :color (colors (get-in row [:lane :id]))
                                   :open? (= open k)
                                   :on-open #(set-open (if (= open k) nil k))
                                   :on-lane set-only}]])))
                 (range) rows))]]))))
