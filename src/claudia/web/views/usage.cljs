(ns claudia.web.views.usage
  "Meters and the model: the plan's 5-hour and week usage as two small rings in the chat list's
  header (and on the folded rail), bars with their reset countdown at the list's foot, the open
  chat's context use as a ring in its header (a click runs /compact), the model and effort pickers in
  the composer, and the / command menu."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
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

(defn limit-rings
  "The list header's two rings (5-hour, week): one button that opens what they summarise (the plan
  bars with their reset times, in Settings)."
  []
  (let [limits @(rf/subscribe [:limits])
        now (use-now!)
        shown (pure/two limits)
        tip (pure/limits-tip shown now)]
    (when (seq shown)
      [:button.limit-rings
       {:aria-label (str "Usage: " (str/join ", " (map #(str (:label %) " " (:percent %) "%") shown)))
        :on-click #(rf/dispatch [:ops/settings true])}
       (for [l shown] ^{:key (:key l)} [ring {:short (short-name (:key l) (:label l)) :percent (:percent l) :tip tip}])])))

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

(defn- account-pick [server account saved plan]
  (cond
    (and account (> (count saved) 1))
    [:label.pick.m-account {:title "Switch the Claude subscription for every chat here (applies on each chat's next message)"}
     [:select {:value account
               :on-change #(rf/dispatch [:ops/put-now {:url (str "/s/" (js/encodeURIComponent server) "/api/claude-token/use")
                                                       :method "POST" :body {:name (.. % -target -value)}
                                                       :on-done (fn [ok _] (when ok (rf/dispatch [:ops/tokens server ok])))}])}
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
        tokens @(rf/subscribe [:tokens server])
        account (get-in tokens [:inUse :name])
        plan (if account (str account "'s plan") "Plan")
        now (use-now!)]
    (when (or context (seq limits))
      [:div.meters
       (when context (context-meter context))
       (account-pick server account (map :name (:tokens tokens)) plan)
       (for [l limits]
         ^{:key (:key l)} [limit-meter l plan now])])))

(declare context-ring)

(defn rail-meters
  "The same meters on the rail (a folded list): the open chat's context (a click compacts), then a
  ring per limit, the details in the hover text."
  []
  (let [limits @(rf/subscribe [:limits])
        oc @(rf/subscribe [:open-chat])
        ro @(rf/subscribe [:read-only?])
        now (use-now!)]
    (when (or (seq limits) (get-in oc [:state :context]))
      [:div.rail-meters
       (when oc [context-ring (cond-> oc ro (assoc :pending? true))])
       (for [l limits]
         ^{:key (:key l)}
         [ring {:short (short-name (:key l) (:label l)) :percent (:percent l)
                :tip (str (:label l) " " (:percent l) "%"
                          (when (:resetsAt l) (str " · in " (countdown (- (:resetsAt l) now)) " (" (reset-label (:resetsAt l)) ")")))}])])))

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
