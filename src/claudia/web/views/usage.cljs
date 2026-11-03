(ns claudia.web.views.usage
  "Meters and the model: the plan's 5-hour and week usage as two small rings in the chat list's
  header (and on the folded rail), a click on them opening every saved token's limits (the hub's,
  how fresh on hover), bars with their reset countdown at the list's foot, the open
  chat's context use as a ring in its header (a click runs /compact), the model and effort pickers in
  the composer, and the / command menu."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.pure.usage :as pure]))

(def ^:private k-tokens pure/k-tokens)
(def pretty-model pure/pretty-model)
(def ^:private level pure/level)
(def ^:private countdown pure/countdown)
(def ^:private short-name pure/short-name)

(defn- reset-label
  "When a limit resets: \"today 14:30\", \"tomorrow 09:00\", \"Thu 23 Oct, 09:00\"."
  [ms]
  (let [d (js/Date. ms)
        hh-mm (.toLocaleTimeString d js/undefined #js {:hour "2-digit" :minute "2-digit"})
        day #(.getTime (js/Date. (.getFullYear %) (.getMonth %) (.getDate %)))
        days (js/Math.round (/ (- (day d) (day (js/Date.))) 86400000))]
    (case days
      0 (str "today " hh-mm)
      1 (str "tomorrow " hh-mm)
      (str (.toLocaleDateString d js/undefined #js {:weekday "short" :day "numeric" :month "short"}) ", " hh-mm))))

(defn- use-now! []
  (let [[now set-now] (h/use-state (js/Date.now))]
    (h/use-effect (fn [] (let [t (js/setInterval #(set-now (js/Date.now)) 1000)] #(js/clearInterval t))) [])
    now))

(def ^:private compact-icon
  "M17 7.5v6m-3.2-3.2 3.2 3.2 3.2-3.2M17 26.5v-6m-3.2 3.2 3.2-3.2 3.2 3.2M10.5 17h13")

(defn ring
  "A chalk ring with its percent; with on-click a button whose number turns into its icon on hover."
  [{short-label :short :keys [percent tip icon on-click on-context-menu]}]
  (let [r 14
        len (* 2 js/Math.PI r)]
    [(if on-click :button.ring.action :div.ring)
     {:class (level percent) :data-tip tip :on-click on-click :on-context-menu on-context-menu}
     [:svg {:viewBox "0 0 34 34" :aria-hidden true}
      [:circle.r-track {:cx 17 :cy 17 :r r}]
      [:circle.r-fill {:cx 17 :cy 17 :r r :transform "rotate(-90 17 17)"
                       :stroke-dasharray (str (* (/ (min 100 percent) 100) len) " " len)}]
      (when on-click
        [:g.r-icon {:transform "translate(17 17) scale(0.7) translate(-17 -17)"} [:path {:d icon}]])]
     [:span.r-num percent]
     [:span.r-label short-label]]))

(defn- pop-meter
  "One window of a token in the popup: bar, percent and when it resets; \"reset since\" once that
  time has passed (the percent no longer holds)."
  [label l now]
  (let [gone? (pure/reset-since? l now)
        p (if (and l (not gone?)) (:percent l) 0)]
    [:div.lp-meter {:class (level p)}
     [:span.m-label label]
     [:span.m-bar [:span {:style {:width (str (min 100 p) "%")}}]]
     [:span.m-num (cond (nil? l) "–" gone? "0%" :else (str p "%"))]
     [:span.lp-reset
      (cond (nil? l) "not heard yet"
            gone? (str "reset since " (reset-label (:resetsAt l)))
            (:resetsAt l) [:<> "resets " (reset-label (:resetsAt l)) " " [:b (str "in " (countdown (- (:resetsAt l) now)))]])]]))

(defn- pop-row [{token-name :name :keys [in-use five week heard-at]} now]
  [:div.lp-row {:class (when in-use "on") :data-tip (str "Heard " (pure/freshness heard-at now))}
   [:div.lp-name token-name (when in-use [:span.lp-use "in use"])]
   [pop-meter "Week" week now]
   [pop-meter "5-hour" five now]])

(defn- pop-place
  "Where the popup opens by the rings in box: below them (the list's header) or beside them (the rail)."
  [^js box beside?]
  (let [rc (.getBoundingClientRect box)]
    (if beside?
      {:left (+ (.-right rc) 8) :bottom (max 6 (- js/innerHeight (.-bottom rc)))}
      {:left (max 6 (.-left rc)) :top (+ (.-bottom rc) 6)})))

(defn- pop-rows
  "The popup's rows: every saved token, else the plan the chats run on (no tokens kept by the hub)."
  [tokens live]
  (or (not-empty (pure/token-rows (:tokens tokens) (get-in tokens [:inUse :name]) live))
      [{:name "Plan" :in-use true :heard-at (:at live)
        :five (pure/limit-of (:limits live) "five_hour") :week (pure/limit-of (:limits live) "seven_day")}]))

(defn limits-popup
  "Every saved token's week and 5-hour use (what the hub last heard; the token in use as fresh as
  the page has it), by the rings in box; hover a token for how fresh its numbers are."
  [{:keys [box beside? close]}]
  (let [menu (h/use-ref nil)
        now (use-now!)
        rows (pop-rows @(rf/subscribe [:tokens]) @(rf/subscribe [:live-limits]))]
    (h/use-effect (fn [] (rf/dispatch [:ops/load-tokens]) js/undefined) [])
    (chatmenu/use-dismiss! (fn [t] (or (some-> (.-current menu) (.contains t)) (some-> (.-current box) (.contains t))))
                           close)
    [:div.chat-menu.limits-pop {:ref #(set! (.-current menu) %) :role "dialog" :aria-label "Usage of every token"
                                :style (pop-place (.-current box) beside?)}
     [:div.cm-title "Usage · every token"]
     (for [r rows] ^{:key (:name r)} [pop-row r now])
     [:button.cm-item.plan-add {:on-click (fn [] (close) (rf/dispatch [:ops/settings true]))} "Tokens in Settings"]]))

(defn limit-rings
  "The list header's two rings (5-hour, week): one button that opens every token's limits."
  []
  (let [limits @(rf/subscribe [:limits])
        now (use-now!)
        shown (pure/two limits)
        [open? set-open] (h/use-state false)
        box (h/use-ref nil)]
    (when (seq shown)
      [:<>
       [:button.limit-rings
        {:ref #(set! (.-current box) %) :data-tip (when-not open? (pure/limits-tip shown now))
         :aria-label (str "Usage: " (str/join ", " (map #(str (:label %) " " (:percent %) "%") shown)))
         :aria-haspopup "dialog" :aria-expanded open?
         :on-click #(set-open (not open?))}
        (for [l shown] ^{:key (:key l)} [ring {:short (short-name (:key l) (:label l)) :percent (:percent l)}])]
       (when open? [:f> limits-popup {:box box :close #(set-open false)}])])))

(defn- meter [{:keys [label percent title]} & children]
  (into [:div.meter {:class (level percent) :title title}
         [:span.m-label label]
         [:span.m-bar [:span {:style {:width (str (min 100 percent) "%")}}]]
         [:span.m-num (str percent "%")]]
        children))

(defn- context-meter [context]
  [:div.meter {:class (level (:percent context))
               :title (str "This chat: " (k-tokens (:used context)) " of " (k-tokens (:max context)) " tokens ("
                           (pretty-model (:model context)) ") · right click to compact")
               :on-context-menu (fn [^js e] (.preventDefault e)
                                  (rf/dispatch [:ops/ctx-menu {:x (.-clientX e) :y (.-clientY e)}]))}
   [:span.m-label "Context"]
   [:span.m-bar [:span {:style {:width (str (min 100 (:percent context)) "%")}}]]
   [:span.m-num (str (:percent context) "%")]])

(defn- account-pick [account saved plan]
  (cond
    (and account (> (count saved) 1))
    [:label.pick.m-account {:title "Switch the Claude subscription for every chat on every server (applies on each chat's next message)"}
     [:select {:value account
               :on-change #(rf/dispatch [:ops/put-now {:url "/hub/claude-token/use"
                                                       :method "POST" :body {:name (.. % -target -value)}
                                                       :on-done (fn [ok _] (when ok (rf/dispatch [:ops/tokens ok])))}])}
      (when-not (some #{account} saved) [:option {:value ""} plan])
      (for [n saved] ^{:key n} [:option {:value n} (str n "'s plan")])]]
    account [:span.m-account {:title "Add or switch tokens in Settings"} plan]))

(defn- limit-meter [l plan now]
  [meter {:label (:label l) :percent (:percent l) :title (str plan ", " (:label l) " limit: " (:percent l) "% used")}
   (when (:resetsAt l)
     [:span.m-reset "resets " (reset-label (:resetsAt l)) " " [:b (str "in " (countdown (- (:resetsAt l) now)))]])])

(defn plan-meters
  "Settings' meters: the open chat's Context bar (right click: compact), whose plan the chats run
  on (a select with two saved tokens or more), each limit as a bar with when it resets and a
  ticking countdown. Nothing until there is a context or a limit."
  [server]
  (let [limits (:limits @(rf/subscribe [:meta server]))
        oc @(rf/subscribe [:open-chat])
        context (get-in oc [:state :context])
        tokens @(rf/subscribe [:tokens])
        account (get-in tokens [:inUse :name])
        plan (if account (str account "'s plan") "Plan")
        now (use-now!)]
    (when (or context (seq limits))
      [:div.meters
       (when context (context-meter context))
       (account-pick account (map :name (:tokens tokens)) plan)
       (for [l limits]
         ^{:key (:key l)} [limit-meter l plan now])])))

(declare context-ring)

(defn- rail-limit-tip [l now]
  (str (:label l) " " (:percent l) "%"
       (when (:resetsAt l) (str " · in " (countdown (- (:resetsAt l) now)) " (" (reset-label (:resetsAt l)) ")"))))

(defn rail-meters
  "The same meters on the rail (a folded list): the open chat's context (a click compacts), then a
  ring per limit, the details in the hover text; a click on a limit opens every token's limits."
  []
  (let [limits @(rf/subscribe [:limits])
        oc @(rf/subscribe [:open-chat])
        ro @(rf/subscribe [:read-only?])
        now (use-now!)
        [open? set-open] (h/use-state false)
        box (h/use-ref nil)]
    (when (or (seq limits) (get-in oc [:state :context]))
      [:div.rail-meters
       (when oc [context-ring (cond-> oc ro (assoc :pending? true))])
       (when (seq limits)
        [:button.rail-limits {:ref #(set! (.-current box) %) :aria-label "Usage of every token"
                             :aria-haspopup "dialog" :aria-expanded open? :on-click #(set-open (not open?))}
        (for [l limits]
          ^{:key (:key l)}
          [ring {:short (short-name (:key l) (:label l)) :percent (:percent l)
                 :tip (when-not open? (rail-limit-tip l now))}])])
       (when open? [:f> limits-popup {:box box :beside? true :close #(set-open false)}])])))

(defn context-ring
  "The open chat's context use; a click compacts it (not while a turn runs)."
  [{:keys [ck state pending?]}]
  (when-let [c (:context state)]
    (let [can? (and (not pending?) (not (:running state)))]
      [ring {:short "ctx" :percent (:percent c) :icon compact-icon
             :tip (str "Context " (:percent c) "% · " (k-tokens (:used c)) " of " (k-tokens (:max c))
                       (when can? " · click to compact"))
             :on-click (when can? #(rf/dispatch [:chat/compact ck]))
             :on-context-menu (fn [^js e] (.preventDefault e)
                                (rf/dispatch [:ops/ctx-menu {:x (.-clientX e) :y (.-clientY e)}]))}])))

;; ---- model and effort ----

(rf/reg-event-db :new/settings (fn [db [_ m]] (update db :new-settings merge m)))
(rf/reg-sub :new-settings (fn [db] (:new-settings db)))

(defn- model-pick [models settings running put!]
  [:label.pick.model {:title (if (seq running) (str "Model: " running (when-not (:model settings) " (your default)")) "Model for this chat")}
   [:select {:value (or (:model settings) "")
             :on-change #(put! {:model (not-empty (.. % -target -value))})}
    [:option {:value ""} (if (and (not (:model settings)) (seq running)) running "Default model")]
    (for [m models :when (not= "default" (:value m))]
      ^{:key (:value m)} [:option {:value (:value m)} (:name m)])]])

(defn- effort-pick [efforts settings put!]
  [:label.pick.effort {:title (str "Effort: " (or (:effort settings) "your default"))}
   [:select {:value (or (:effort settings) "")
             :on-change #(put! {:effort (not-empty (.. % -target -value))})}
    [:option {:value ""} "effort"]
    (for [e efforts] ^{:key e} [:option {:value e} e])]])

(defn pickers
  "The chat's model and effort (applied on its next start; a live session takes them at once). A
  new chat keeps them until it is made (events.cljs sends them with `created`)."
  [{:keys [ck server state pending?]}]
  (let [server (or server @(rf/subscribe [:new-server]))
        server-meta @(rf/subscribe [:meta server])
        models (:models server-meta)
        settings (if ck (:settings state) @(rf/subscribe [:new-settings]))
        put! (fn [m] (if ck (rf/dispatch [:chat/settings ck m]) (rf/dispatch [:new/settings m])))
        efforts (:efforts (pure/chosen-model models settings))
        running (pretty-model (or (not-empty (:model state)) (when-not (:model settings) (:defaultModel server-meta))))]
    (when (and (not pending?) (seq models) (not @(rf/subscribe [:read-only?])))
      [:<>
       (model-pick models settings running put!)
       (when (seq efforts) (effort-pick efforts settings put!))])))

;; ---- the / menu ----

(def matches pure/matches)

(defn slash-key!
  "The composer's keys while the menu shows: arrows move, Tab and Enter complete. True when handled."
  [e draft-key items pick]
  (when (seq items)
    (let [n (count items)]
      (case (.-key e)
        "ArrowDown" (do (.preventDefault e) (rf/dispatch [:slash/pick (mod (inc pick) n)]) true)
        "ArrowUp" (do (.preventDefault e) (rf/dispatch [:slash/pick (mod (+ (dec pick) n) n)]) true)
        ("Tab" "Enter") (if (and (= "Enter" (.-key e)) (.-shiftKey e))
                          false
                          (do (.preventDefault e)
                              (rf/dispatch [:draft/set draft-key (str "/" (:name (nth items (min pick (dec n)))) " ")])
                              (rf/dispatch [:slash/pick 0])
                              true))
        false))))

(defn slash-menu [draft-key items pick]
  (when (seq items)
    [:ul.slash-menu {:role "listbox"}
     (for [[i c] (map-indexed vector items)]
       ^{:key (:name c)}
       [:li {:role "option" :aria-selected (= i pick) :class (when (= i pick) "on")
             :on-mouse-down (fn [e] (.preventDefault e)
                              (rf/dispatch [:draft/set draft-key (str "/" (:name c) " ")])
                              (rf/dispatch [:slash/pick 0]))
             :on-mouse-enter #(rf/dispatch [:slash/pick i])}
        [:b (str "/" (:name c))] (when (seq (:hint c)) [:span.hint " " (:hint c)])
        [:span.desc (:description c)]])]))
