(ns claudia.web.views.usage
  "Meters and the model: the chalk rings in the chat's header (the chat's context, a click runs
  /compact; its plan's 5-hour and week usage: claudia.web.views.head), where a popup opens beside
  a button (pop-place), the model and effort pickers in the header, and the / command menu."
  (:require [re-frame.core :as rf]
            [claudia.web.views.pure.usage :as pure]))

(def ^:private k-tokens pure/k-tokens)
(def pretty-model pure/pretty-model)
(def ^:private level pure/level)

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

(defn pop-place
  "Where the popup opens by the rings in box: below them (the list's header) or beside them (the rail)."
  [^js box beside?]
  (let [rc (.getBoundingClientRect box)]
    (if beside?
      {:left (+ (.-right rc) 8) :bottom (max 6 (- js/innerHeight (.-bottom rc)))}
      {:left (max 6 (.-left rc)) :top (+ (.-bottom rc) 6)})))

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

(defn- effort-pick
  "The chat's effort; with none picked, the effort its session runs with (running, known from its
  first tool call on)."
  [efforts settings running put!]
  [:label.pick.effort {:title (str "Effort: " (or (:effort settings) (some-> running (str " (your default)")) "your default"))}
   [:select {:value (or (:effort settings) "")
             :on-change #(put! {:effort (not-empty (.. % -target -value))})}
    [:option {:value ""} (or (when-not (:effort settings) running) "…")]
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
       (when (seq efforts)
         (effort-pick efforts settings
                      (pure/effort-shown (:effort state) (:defaultEfforts server-meta)
                                         (or (:model settings) ""))
                      put!))])))

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
