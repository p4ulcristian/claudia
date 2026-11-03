(ns claudia.web.views.timeline
  "The timeline on the right: every step of the chat and its subagents as one feed, top to bottom in
  time order. A thin rail per agent on the left shows who ran at the same time (a free rail is
  reused by a later agent); quiet stretches get a divider with their length; a filter shows one
  agent and the agents it started. A step opens in full under itself: Edit and Write steps as their
  diff. Read from the runner (GET /api/chats/:id/timeline), every 2 s while the chat runs."
  (:require [claudia.web.format :as format]
            [claudia.web.api :as api]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.md :as md]
            [claudia.web.views.pure.timeline :as pure]))

(def ^:private rail 14)        ; px per rail
(def ^:private idle (* 60 1000)) ; a longer quiet stretch gets a divider
(def ^:private page-rows 400)  ; rows shown at first; earlier ones on request

(def ^:private dur pure/dur)

(defn- clock [t]
  (.toLocaleTimeString (js/Date. t) #js [] #js {:hour "2-digit" :minute "2-digit" :second "2-digit" :hour12 false}))

(defn- day [t]
  (.toLocaleDateString (js/Date. t) #js [] #js {:month "short" :day "numeric" :weekday "short"}))

(defn- timeline-url [{:keys [server chat]} & parts]
  (str "/s/" (js/encodeURIComponent server) "/api/chats/" (js/encodeURIComponent chat) "/timeline"
       (apply str (map #(str "/" (js/encodeURIComponent %)) parts))))

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
          (fn [hi {:keys [oldStart oldLines newStart newLines] :as hunk}]
            (cons
             ^{:key (str "h" hi)}
             [:tr.hunk [:td {:col-span 3} (str "@@ -" oldStart "," oldLines " +" newStart "," newLines " @@")]]
             (map-indexed (fn [li {:keys [kind a b text]}]
                            ^{:key (str hi ":" li)}
                            [:tr {:class kind} [:td.ln a] [:td.ln b] [:td text]])
                          (pure/hunk-rows hunk))))
          (range) hunks))])

(defn- use-step-detail
  "The step in full from the runner (again when it ends): [detail failed?]."
  [oc lane e]
  (let [[d set-d] (h/use-state nil)
        [failed set-failed] (h/use-state false)]
    (h/use-effect
     (fn []
       (let [dead (atom false)]
         (set-d nil)
         (set-failed false)
         (-> (api/fetch-answer (timeline-url oc (:id lane) (:id e)))
             (.then (fn [{:keys [ok body]}]
                      (when-not @dead (if ok (set-d body) (set-failed true)))))
             (.catch #(when-not @dead (set-failed true))))
         #(reset! dead true)))
     [(:server oc) (:chat oc) (:id lane) (:id e) (:end e)])
    [d failed]))

(defn- patch-detail [{:keys [patch] :as d}]
  [:<>
   [:h4 (if (:created patch) "New file" "Diff") [:span.tl-file (format/tilde (:file patch))]]
   (if (seq (:hunks patch)) [patch-diff (:hunks patch)] [:p.muted.small "No lines changed."])
   (when (:cut patch) [:p.muted.small "Cut at 5000 lines."])
   [:details.tl-raw
    [:summary "Input and output"]
    [:h4 "Input"] [:pre (:input d)]
    [:h4 "Output"] [:pre (if (seq (:output d)) (:output d) "(no output)")]]])

(defn- tool-detail [lane d]
  [:<>
   [:h4 "Input"] [:pre (:input d)]
   [:h4 "Output"]
   (if (nil? (:output d))
     [:p.muted.small (if (:running lane) "Still running." "It got no result (stopped or cut off).")]
     [:pre {:class (when (:error d) "err")} (if (seq (:output d)) (:output d) "(no output)")])])

(defn- detail [oc lane e]
  (let [[d failed] (use-step-detail oc lane e)]
    [:div.tl-detail
     (when (and (= "tool" (:k e)) (= "tool" (:k d))) [explain oc e])
     (cond
       failed [:p.muted.small "Couldn't read this from the session file."]
       (nil? d) [:p.muted.small "Loading…"]
       (= "text" (:k d)) [:div.md.small {:dangerouslySetInnerHTML (r/unsafe-html (md/html (:text d)))}]
       (:patch d) (patch-detail d)
       :else (tool-detail lane d))
     (when (:truncated d) [:p.muted.small "Cut at 1 MB."])]))

;; ---- one row ----

(defn- lane-tag [lane rail-style on-lane]
  (when (not= "main" (:kind lane))
    [:button.tl-tag {:style rail-style :title (str (:title lane) " (" (:kind lane) ") · show only this agent")
                     :on-click (fn [ev] (.stopPropagation ev) (on-lane (:id lane)))}
     (:title lane)]))

(defn- step-body [lane e started on-lane]
  (if (= "tool" (:k e))
    [:<>
     [:span.tl-tool {:class (pure/category (:name e))} (:name e)]
     [:span.tl-title (:title e)]
     (when started
       [:span.link {:role "button" :on-click (fn [ev] (.stopPropagation ev) (on-lane (:id started)))} "its steps"])]
    [:<>
     [:span.tl-who {:class (:k e)} (pure/who lane e)]
     [:span.tl-text (:text e)]]))

(defn- step-dur [lane e now]
  (let [end (or (:end e) (when (:running lane) now))]
    [:span.tl-dur
     (when-let [{:keys [added removed]} (:diff e)]
       [:span.tl-diff {:title (str added " lines added, " removed " removed")}
        (when (pos? added) [:span.plus (str "+" added)])
        (when (pos? removed) [:span.minus (str "−" removed)])])
     (when (:error e) [:span.tl-failed "failed"])
     (if (nil? end) "no result" (str (dur (- end (:start e))) (when (nil? (:end e)) " so far")))]))

(defn- step [{:keys [oc row now rails color open? on-open on-lane lanes]}]
  (let [{:keys [lane e at]} row
        rail-style (when color {"--rail" (str "var(--c-" color ")")})
        tool? (= "tool" (:k e))
        started (when (and tool? (:agent e)) (some #(when (= (:id %) (:agent e)) %) lanes))]
    [:li.tl-step {:class [(pure/step-class lane e) (when open? "open")]}
     [:button.tl-step-row {:on-click on-open}
      [:span.tl-time (clock at)]
      rails
      [:span.tl-body
       (lane-tag lane rail-style on-lane)
       (step-body lane e started on-lane)]
      (when tool? (step-dur lane e now))]
     (when open? [:f> detail oc lane e])]))

;; ---- the feed ----

(defn- rail-style [colors id style]
  (merge style (when-let [color (colors id)] {"--rail" (str "var(--c-" color ")")})))

(defn- rail-cell [rails colors i own]
  [:span.tl-rails {:style {:width (* (:count rails) rail)}}
   (for [[id c] (:col rails)
         :let [f ((:first rails) id) l ((:last rails) id)]
         :when (<= f i l)]
     ^{:key id}
     [:span.tl-line {:class [(when (== i f) "from") (when (== i l) "to")]
                     :style (rail-style colors id {:left (- (+ (* c rail) (/ rail 2)) 1)})}])
   (when own
     [:span.tl-node {:style (rail-style colors own {:left (- (+ (* ((:col rails) own) rail) (/ rail 2)) 4)})}])])

;; ---- the timeline's state ----

(defn- use-start-over!
  "Another chat: start over."
  [key* {:keys [set-data set-missing set-open set-only set-shown]} v at-end]
  (h/use-effect
   (fn []
     (set-data nil) (set-missing false) (set-open nil) (set-only nil) (set-shown page-rows)
     (set! (.-current v) "") (set! (.-current at-end) true)
     js/undefined)
   [key*]))

(defn- loaded!
  "An answer of the timeline: 404 means none yet; the same version only moves its clock."
  [{:keys [status ok body]} {:keys [set-data set-missing]} v]
  (cond (= 404 status) (set-missing true)
        (not ok) nil
        (:same body) (do (set-missing false) (set-data #(when % (assoc % :now (:now body)))))
        :else (do (set-missing false)
                  (set! (.-current v) (:v body))
                  (set-data body))))

(defn- use-reads!
  "Read now, then every 2 s while the chat runs (once more when it stops)."
  [oc key* running setters v]
  (h/use-effect
   (fn []
     (let [dead (atom false)
           load (fn []
                  (-> (api/fetch-answer (str (timeline-url oc) "?v=" (js/encodeURIComponent (.-current v))))
                      (.then #(when-not @dead (loaded! % setters v)))
                      (.catch (fn [_]))))
           t (when running (js/setInterval load 2000))]
       (load)
       #(do (reset! dead true) (some-> t js/clearInterval))))
   [key* running]))

(defn- use-stay-at-end!
  "The feed keeps its end in view while the person is there."
  [scroller at-end deps]
  (h/use-layout-effect
   (fn []
     (when-let [el (.-current scroller)]
       (when (.-current at-end) (set! (.-scrollTop el) (.-scrollHeight el))))
     js/undefined)
   deps))

(defn- use-escape-closes!
  "Escape closes the step shown in full."
  [open set-open]
  (h/use-effect
   (fn []
     (let [on-key (fn [^js e] (when (and open (= "Escape" (.-key e))) (.stopPropagation e) (set-open nil)))]
       (js/window.addEventListener "keydown" on-key true)
       #(js/window.removeEventListener "keydown" on-key true)))
   [open]))

;; ---- the timeline ----

(defn- empty-view [missing]
  [:div.tl
   [:div.tl-head [:b "Timeline"]]
   [:p.muted.tl-empty (if missing
                        "Nothing yet: the timeline fills in once Claude starts working in this chat."
                        "Loading…")]])

(defn- head [{:keys [lanes agents only set-only]}]
  [:div.tl-head
   [:b "Timeline"]
   [:span.muted.small (pure/agents-summary agents)]
   (when (seq agents)
     [:select.tl-filter {:value (or only "") :title "Show one agent (and the agents it started)"
                         :on-change #(set-only (not-empty (.. % -target -value)))}
      [:option {:value ""} "Everything"]
      [:option {:value "main"} "Chat only"]
      (for [l agents]
        ^{:key (:id l)} [:option {:value (:id l)} (str (:title l) (when (:running l) " (running)"))])])
   (when (and only (some #(when (= (:id %) only) %) lanes))
     [:button.link {:on-click #(set-only nil)} "show everything"])])

(defn- separator [rows rails colors i row]
  (let [prev (when (pos? i) (nth rows (dec i)))]
    (case (pure/divider prev row idle day)
      :day ^{:key (str "d" i)} [:li.tl-sep.day [rail-cell rails colors (- i 0.5) nil] [:span (day (:at row))]]
      :quiet ^{:key (str "g" i)} [:li.tl-sep [rail-cell rails colors (- i 0.5) nil] [:span (str (dur (- (:at row) (:at prev))) " quiet")]]
      nil)))

(defn- feed-items [{:keys [oc data rows rails colors lanes open set-open set-only]}]
  (mapcat
   (fn [i row]
     (let [k (str (get-in row [:lane :id]) ":" (get-in row [:e :id]))]
       (keep identity
             [(separator rows rails colors i row)
              ^{:key k}
              [step {:oc oc :row row :now (:now data) :lanes lanes
                     :rails [rail-cell rails colors i (get-in row [:lane :id])]
                     :color (colors (get-in row [:lane :id]))
                     :open? (= open k)
                     :on-open #(set-open (if (= open k) nil k))
                     :on-lane set-only}]])))
   (range) rows))

(defn- more-button [all rows at-end set-shown]
  [:button.tl-more.link {:on-click #(do (set! (.-current at-end) false) (set-shown (fn [n] (+ n page-rows))))}
   (str (- (count all) (count rows)) " earlier steps · show " (min page-rows (- (count all) (count rows))) " more")])

(defn- feed-view [{:keys [data only shown scroller at-end set-shown] :as props}]
  (let [lanes (:lanes data)
        agents (filterv #(not= "main" (:kind %)) lanes)
        all (pure/all-rows lanes (pure/kept-lanes lanes only))
        rows (pure/last-rows all shown)
        parts (assoc props :lanes lanes :agents agents :rows rows :rails (pure/rails-of rows)
                     :colors (pure/agent-colors agents))]
    [:div.tl
     (head parts)
     [:div.tl-scroll {:ref #(set! (.-current scroller) %)
                      :on-scroll (fn [^js e]
                                   (let [el (.-currentTarget e)]
                                     (set! (.-current at-end) (>= (+ (.-scrollTop el) (.-clientHeight el)) (- (.-scrollHeight el) 40)))))}
      (when (> (count all) (count rows))
        (more-button all rows at-end set-shown))
      (into [:ul.tl-feed] (feed-items parts))]]))

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
        at-end (h/use-ref true)
        setters {:set-data set-data :set-missing set-missing :set-open set-open :set-only set-only :set-shown set-shown}]
    (use-start-over! key* setters v at-end)
    (use-reads! oc key* running setters v)
    (use-stay-at-end! scroller at-end [data shown only])
    (use-escape-closes! open set-open)
    (if (or missing (nil? data))
      (empty-view missing)
      (feed-view (assoc setters :oc oc :data data :open open :only only :shown shown
                        :scroller scroller :at-end at-end)))))
