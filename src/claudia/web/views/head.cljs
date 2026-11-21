(ns claudia.web.views.head
  "The chat's header (README decisions 22, 90): one panel lightly tinted with the chat's colour,
  three rows, the same for a new chat and a started one. Row 1: the title (a click renames it) and
  the server's state when it is off or unreached. Row 2: pills of one look (claudia.web.views.head-pill):
  the server and the project (claudia.web.views.head-place), model · effort, the prompt profile, the
  voice that reads its replies (decision 21), the notify sound (claudia.web.views.notify) and the
  colour. Row 3 (decision 116): on the left the Compact button (the context ring) and whose Claude
  subscription, one pill with the plan's 5-hour and week rings; on the right buttons with words: Files, System (decision 96), Fork, Sudo (its owner), Archive. A new chat picks all of them
  here before its first message (they go with `new`); once it has started a server pick moves it
  (decision 82) and every other pick changes the chat."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.shared.colours :as colours-pure]
            [claudia.shared.sounds :as sounds]
            [claudia.web.folders :as folders]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.head-pill :as hp]
            [claudia.web.views.head-place :as place]
            [claudia.web.views.notify :as notify]
            [claudia.web.views.prompts :as prompts]
            [claudia.web.views.talk :as talk]
            [claudia.web.views.pure.chat :as pure]
            [claudia.web.views.pure.menu :as menu-pure]
            [claudia.web.views.pure.usage :as usage-pure]
            [claudia.web.views.pure.words :as words]
            [claudia.web.views.usage :as usage]))

(def ^:private colours words/colours-with-ash)

;; ---- small parts ----

(defn archive-icon
  "A box with a lid; the arrow points out of it for Unarchive."
  [out?]
  [:svg {:width 16 :height 16 :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width 1.5
         :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden true}
   [:rect {:x 1.5 :y 2.5 :width 13 :height 3.5 :rx 0.8}]
   [:path {:d "M2.8 6v6.5a1 1 0 0 0 1 1h8.4a1 1 0 0 0 1-1V6"}]
   (if out? [:path {:d "M8 12V8.2M6.3 9.8 8 8.1l1.7 1.7"}] [:path {:d "M6.3 9h3.4"}])])

;; ---- row 1: the title ----

(defn- title-edit [title rename! cancel!]
  [:input.title-edit {:default-value title :auto-focus true
                      :on-blur #(rename! (.. % -target -value))
                      :on-key-down #(case (.-key %)
                                      "Enter" (rename! (.. % -currentTarget -value))
                                      "Escape" (cancel!)
                                      nil)}])

(defn- title-line
  "The title; with edit! (a renamable chat) a click on it renames."
  [title edit!]
  [:h1 {:class (when edit! "renamable") :title (when edit! "Click to rename") :on-click edit!}
   [:span.t title]])

(def ^:private state-paths
  {:off ["M12 3v8" "M6.5 6.5a8 8 0 1 0 11 0"]
   :reconnecting ["M20 12a8 8 0 0 0-14-5.3L4 9" "M4 4v5h5" "M4 12a8 8 0 0 0 14 5.3l2-2.3" "M20 20v-5h-5"]
   :offline ["M3 3l18 18" "M8.5 16.4a5 5 0 0 1 7 0" "M4.9 12.8a10 10 0 0 1 4.6-2.6" "M19.1 12.8a10 10 0 0 0-2.7-1.9" "M12 20h.01"]})

(def ^:private state-tips {:offline "no network" :reconnecting "reconnecting…"})

(defn- state-icon
  "Whether the server is there: a power sign when it is off, a struck-through wave when the device
  has no network, turning arrows while the page reconnects, nothing when it is connected."
  [status label]
  (when-let [s (pure/server-state status)]
    (let [tip (if (= :off s) (str label " is off") (state-tips s))]
      [:span.head-state {:class (name s) :role "img" :aria-label tip :data-tip tip}
       [:svg {:viewBox "0 0 24 24" :aria-hidden true}
        (for [d (state-paths s)] ^{:key d} [:path {:d d}])]])))

(defn- head-names
  "The title (a click renames a started chat) and the server's state."
  [{:keys [ck server row state status ro]}]
  (let [[editing set-editing] (h/use-state false)
        title (or (:title row) (:title state) "New chat")
        rename! (fn [typed]
                  (set-editing false)
                  (when-let [t (and row (pure/new-title typed title))] (rf/dispatch [:chat/rename ck t])))]
    [:div.head-title
     (if editing
       (title-edit title rename! #(set-editing false))
       (title-line title (when (and row (not ro)) #(set-editing true))))
     (state-icon (or status @(rf/subscribe [:server-status server])) @(rf/subscribe [:server-label server]))]))

(defn- row-1 [hc]
  [:div.head-row.r1 [:f> head-names hc]])

;; ---- row 2: server, project, model · effort, prompt, voice, notify, colour ----

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

(defn- swatch [c color pick taken]
  (let [off? (and (taken c) (not= c color))
        n (colours-pure/name-of c)]
    [:button.swatch {:class [(when (= c color) "on") (when (= c "ash") "passive") (when off? "taken")]
                     :style {:background (words/colour-fill c)} :disabled off? :aria-label n
                     :data-tip (cond (= c "ash") "Grey: passive, sinks to the bottom of the rail"
                                     off? (str n ": another chat wears it")
                                     :else n)
                     :on-click #(pick c)}]))

(defn- palette
  "The solid colours and ash, then the gradients on rows of their own (README decision 80); the ones
  another open chat wears struck through (decision 26). k: the chat ([server id]; nil: the new one)."
  [k color pick]
  (let [taken @(rf/subscribe [:taken-colours k])]
    [:div.palette {:role "menu"}
     (for [c colours] ^{:key c} [swatch c color pick taken])
     [:span.swatch-break]
     (for [c words/gradient-colours] ^{:key c} [swatch c color pick taken])]))

(defn- model-pick
  "Model · effort in one pill; its popup lists both (claudia.web.views.usage)."
  [{:keys [new? oc server ro]}]
  (let [ms (usage/model-state (if new? {:server server} oc))]
    [:f> hp/popped {:k :model :label "Model · effort" :value (:text ms) :ro ro}
     (fn [close!] (usage/model-effort-pop ms close!))]))

(defn- prompt-pick
  "The prompt profile on top of Claudia's built-in part (README decision 60)."
  [{:keys [server settings put! ro]}]
  (let [picked (prompts/use-profiles server settings)]
    [:f> hp/popped {:k :prompt :label "Prompt profile" :value (or picked "built-in") :ro ro}
     (fn [close!] [prompts/pick-pop server settings put! close!])]))

(defn- voice-pick
  "The voice that reads the chat's replies aloud, Off by default (README decision 21); only when
  the hub has a voice."
  [{:keys [new? ck ro]}]
  (let [k (when-not new? ck)
        picked @(rf/subscribe [:voice k])]
    (when (talk/use-voice?)
      [:f> hp/popped {:k :voice :label "Voice" :value (talk/voice-name picked) :ro ro
                   :class (when (seq picked) "on")}
       (fn [close!] [talk/voice-pop k picked close!])])))

(defn- notify-pick
  "When the chat finishes or waits on you: its sound (README decision 90), or Off (its bell off: no
  chime, popup or phone push, decision 21)."
  [{:keys [new? ck ro color]}]
  (let [k (when-not new? ck)
        picked @(rf/subscribe [:notify k])]
    [:f> hp/popped {:k (if (seq picked) :bell :bell-off) :label "Notify" :value (sounds/notify-name picked) :ro ro}
     (fn [close!] [notify/notify-pop k picked color close!])]))

(defn- colour-pick
  "The chat's colour: a filled dot and its short name (README decision 89); the popup is the palette
  (a new chat's pick goes with `new`)."
  [{:keys [new? ck ro color put!]}]
  (let [c (or color "ash")]
    [:f> hp/popped {:dot c :label "Colour" :value (colours-pure/name-of c) :ro ro}
     (fn [close!]
       [:div.head-pop.colour-pop
        [palette (when-not new? ck) c (fn [p] (if new? (put! {:color p}) (rf/dispatch [:ops/color ck p])) (close!))]])]))

(defn- row-2 [hc]
  [:div.head-row.r2
   [place/server-pick hc]
   [place/project-pick hc]
   [:f> model-pick hc]
   [:f> prompt-pick hc]
   [:f> voice-pick hc]
   [notify-pick hc]
   [colour-pick hc]])

;; ---- row 3: rings, actions ----

(defn- act-button
  "One of row 3's buttons: an icon and a word, filled while on (class names the fill's colour)."
  [{:keys [icon word tip on? class on-click]}]
  [:button.head-btn {:type "button" :class [class (when on? "on")] :aria-pressed (boolean on?)
                     :data-tip tip :on-click on-click}
   icon [:span word]])

(defn- files-button
  "Files: the File Browser in the right half (README decision 70), filled while it shows."
  []
  (let [on? (= :files @(rf/subscribe [:right-view]))]
    [act-button {:icon [hp/icon :folder] :word "Files" :on? on? :class "files-btn"
                 :tip (if on? "Hide the File Browser" "Show the File Browser")
                 :on-click #(rf/dispatch [:right/toggle :files])}]))

(defn- system-button
  "System: every server's health, ports and services in the right half (README decision 96), filled
  while it shows."
  []
  (let [on? (= :system @(rf/subscribe [:right-view]))]
    [act-button {:icon [hp/icon :system] :word "System" :on? on? :class "system-btn"
                 :tip (if on? "Hide System" "Show System: the servers' health, ports and services")
                 :on-click #(rf/dispatch [:right/toggle :system])}]))

(defn- fork-button
  "Fork: a new chat with this one up to its last finished turn, in the same folder (README decision
  99); Forking… while the server makes it."
  [ck]
  (let [{:keys [forking?]} (menu-pure/fork-state @(rf/subscribe [:fork]) ck)]
    [act-button {:icon [hp/icon :fork] :word (if forking? "Forking…" "Fork") :class "fork-btn" :on? forking?
                 :tip "A new chat with this one up to its last finished turn, in the same folder"
                 :on-click #(when-not forking? (rf/dispatch [:ops/fork ck nil]))}]))

(defn- root-button
  "The chat's root switch (README decision 52), only for its owner: red while on; turning it on asks
  first."
  [ck]
  (let [on? @(rf/subscribe [:root? ck])]
    [act-button {:icon [hp/icon :root] :word (if on? "Sudo on" "Sudo off") :on? on? :class "root-btn"
                 :tip (if on? "Root on: sudo runs without asking. Click to turn it off"
                          "Root off: each sudo command asks you first")
                 :on-click #(rf/dispatch (if on? [:chat/root ck false] [:root/ask ck]))}]))

(defn- archive-button [ck row]
  (let [out? (boolean (:archived row))]
    [act-button {:icon [archive-icon out?] :word (if out? "Unarchive" "Archive") :class "archive-btn"
                 :tip (if out? "Back to the open chats" "Archive this chat")
                 :on-click #(rf/dispatch (if out? [:chat/archive ck false] [:archive/ask ck]))}]))

(defn- actions
  "Row 3's buttons on the right: Files and System (not in a read-only view, which shows the picture
  only), Fork (a started chat), Sudo (its owner, a started chat), Archive (a started chat)."
  [{:keys [ck row ro view-ro]}]
  [:span.head-acts
   (when-not view-ro [files-button])
   (when-not view-ro [system-button])
   (when (and row (not ro)) [fork-button ck])
   (when (and row (not ro) @(rf/subscribe [:owner?])) [root-button ck])
   (when (and row (not ro)) (archive-button ck row))])

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

(defn- row-3
  "On the left the Compact button (a started chat) and the subscription pill, the buttons on the right."
  [{:keys [new? oc plans on ro] :as hc}]
  (let [now (hooks/use-now)
        {:keys [five week]} (plan-limits on plans)]
    [:div.head-row.r3
     [:span.head-rings
      (when-not new? [usage/compact-button oc ro])
      [plan-button hc five week now]]
     (actions hc)]))

;; ---- the header ----

(defn- use-tokens!
  "The hub's saved tokens with their limits, loaded now and every minute."
  []
  (h/use-effect (fn [] (rf/dispatch [:ops/load-tokens])
                  (let [t (js/setInterval #(rf/dispatch [:ops/load-tokens]) 60000)] #(js/clearInterval t)))
                []))

(defn- head-context
  "What the rows need, the same for a new chat (oc nil or not started) and a started one: where it
  runs (folders/chat-place), its settings and how a pick is put, its plans and the plan it runs on."
  [{:keys [ck state row pending?] :as oc} ro]
  (let [place (folders/chat-place oc @(rf/subscribe [:new-server]) @(rf/subscribe [:new-settings]))
        {:keys [new? server]} place
        settings (if new? @(rf/subscribe [:new-settings]) (:settings state))
        {:keys [plans planKey]} @(rf/subscribe [:meta server])]
    (assoc (if new? {} oc)
           :new? new? :oc oc :server server :settings settings :plans plans
           :on (pure/chat-plan plans (:plan settings) planKey)
           :folder (:folder place) :view-ro ro
           :color (if new? (:color settings) (:color row))
           :ro (or ro (and (not new?) (or pending? (not row))))
           :put! (if new? #(rf/dispatch [:new/settings %]) #(rf/dispatch [:chat/settings ck %])))))

(defn- head-view [oc ro]
  (let [hc (head-context oc ro)]
    (use-tokens!)
    [:header.chat-head
     [:div.head-panel {:style (words/colour-style (:color hc))}
      (row-1 hc)
      (row-2 hc)
      [:f> row-3 hc]]]))

(defn header
  "The header of the open chat oc (nil: a new chat)."
  [oc]
  [:f> head-view oc @(rf/subscribe [:read-only?])])
