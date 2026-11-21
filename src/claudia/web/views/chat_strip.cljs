(ns claudia.web.views.chat-strip
  "The strip right over the message box (README decision 117, moved there from the header): model ·
  effort, the Compact button (the context ring, a started chat) and whose Claude subscription, one
  pill with the plan's 5-hour and week rings (decisions 16, 57, 116). Their popups open upwards, kept
  inside the strip. A new chat picks model and plan here before its first message (they go with
  `new`)."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.chat-context :as chat-context]
            [claudia.web.views.head-pill :as hp]
            [claudia.web.views.pure.usage :as usage-pure]
            [claudia.web.views.usage :as usage]))

;; ---- model · effort ----

(defn- model-pick
  "Model · effort in one pill; its popup lists both (claudia.web.views.usage)."
  [{:keys [new? oc server ro]}]
  (let [ms (usage/model-state (if new? {:server server} oc))]
    [:f> hp/popped {:k :model :label "Model · effort" :value (:text ms) :ro ro}
     (fn [close!] (usage/model-effort-pop ms close!))]))

;; ---- the subscription ----

(defn- plan-limit [label l now]
  [:span.plan-limit {:class (when (and l (not (usage-pure/reset-since? l now))) (usage-pure/level (:percent l)))}
   (usage-pure/limit-text label l now)])

(defn- plan-list
  "The saved plans with their week and 5-hour use (what the hub last heard) and the hours until
  each resets."
  [plans on pick]
  (let [now (hooks/use-now)
        tokens @(rf/subscribe [:tokens])
        rows (usage-pure/token-rows (:tokens tokens) (get-in tokens [:pick :name]) @(rf/subscribe [:live-limits]))]
    (h/use-effect (fn [] (rf/dispatch [:ops/load-tokens]) js/undefined) [])
    [:div.plan-list {:role "menu"}
     [:div.plan-title "This chat's Claude subscription"]
     (for [{k :key n :name :keys [week five]} (usage-pure/plan-rows plans rows)]
       ^{:key k}
       [:button.plan-item {:role "menuitemradio" :aria-checked (= k (:key on)) :class (when (= k (:key on)) "on")
                           :on-click #(pick k)}
        [:span.plan-name n]
        (plan-limit "Week" week now)
        (plan-limit "5-hour" five now)])]))

(defn- plan-limits
  "The 5-hour and week Limits of the plan a chat runs on (on; none saved: the server's own plan)."
  [on plans]
  (let [tokens @(rf/subscribe [:tokens])
        live @(rf/subscribe [:live-limits])
        rows (usage-pure/token-rows (:tokens tokens) (get-in tokens [:pick :name]) live)]
    (if on
      (some #(when (= (:key on) (:key %)) %) (usage-pure/plan-rows plans rows))
      {:five (usage-pure/limit-of (:limits live) "five_hour")
       :week (usage-pure/limit-of (:limits live) "seven_day")})))

(defn- limit-ring [label sym l now]
  (when-let [p (usage-pure/ring-percent l now)]
    [usage/mini-ring {:percent p :sym sym :tip (usage-pure/limit-text label l now)}]))

(defn- plan-rings [five week now]
  [:<> (limit-ring "5-hour" "5h" five now) (limit-ring "Week" "7d" week now)])

(defn- plan-button
  "Whose Claude subscription the chat runs on (README decisions 16, 116): one pill, clickable
  anywhere, with the plan's 5-hour and week rings and its owner's name; a click opens the saved
  plans, a pick applies at once and becomes the person's own last pick (decision 57). No plans
  saved: the server's own plan's rings, nothing to pick."
  [{:keys [plans on put! ro]} five week now]
  (if (seq plans)
    [:f> hp/popped {:k :account :label "Claude subscription" :class "plan-pill" :ro ro
                    :body [:<> [plan-rings five week now] [:span.pill-val (or (:name on) "Pick one")]]}
     (fn [close!]
       [:div.head-pop
        [:f> plan-list plans on (fn [k] (put! {:plan k}) (rf/dispatch [:ops/token-picked k]) (close!))]])]
    [:span.plan-rings [plan-rings five week now]]))

;; ---- the strip ----

(defn- strip-view [oc]
  (let [hc (chat-context/context oc @(rf/subscribe [:read-only?]))
        now (hooks/use-now)
        {:keys [five week]} (plan-limits (:on hc) (:plans hc))]
    (chat-context/use-tokens!)
    [:div.chat-strip
     [:f> model-pick hc]
     (when-not (:new? hc) [usage/compact-button oc (:ro hc)])
     [plan-button hc five week now]]))

(defn strip
  "The strip over the message box of the open chat oc (nil: a new chat)."
  [oc]
  [:f> strip-view oc])
