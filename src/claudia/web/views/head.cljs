(ns claudia.web.views.head
  "The chat's header (README decision 22): one panel in the chat's colour, three rows, the same
  for a new chat and a started one. Row 1: the title (a click renames it), the server's state, the
  sounds (a bell), talking mode (a person speaking) and Archive. Row 2: the computer the chat runs on, whose Claude subscription, the
  project (with the chat's colour). Row 3: model and effort, then the context, 5-hour and week
  rings. A new chat picks all of them here before its first message (they go with `new`); once
  it has started the computer is fixed and every other pick changes the chat."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.alerts :as alerts]
            [claudia.web.api :as api]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.dot :as dot]
            [claudia.web.views.talk :as talk]
            [claudia.web.views.pure.chat :as pure]
            [claudia.web.views.pure.systems :as systems-pure]
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

(defn- use-closes-away!
  "While open, a pointer down outside box or Escape calls close."
  [box open close]
  (h/use-effect
   (fn []
     (when open
       (let [away (fn [e] (when-not (some-> (.-current box) (.contains (.-target e))) (close)))
             esc (fn [e] (when (= "Escape" (.-key e)) (close)))]
         (js/window.addEventListener "pointerdown" away)
         (js/window.addEventListener "keydown" esc)
         #(do (js/window.removeEventListener "pointerdown" away)
              (js/window.removeEventListener "keydown" esc)))))
   [open]))

(defn- use-popup
  "A popup's state: [open? toggle! close! box-ref], closing on a pointer down outside or Escape."
  []
  (let [[open set-open] (h/use-state false)
        box (h/use-ref nil)
        close! #(set-open false)]
    (use-closes-away! box open close!)
    [open #(set-open not) close! box]))

(def ^:private field-icons
  "Row 2's icons in place of words: the computer, Claude's spark (whose Claude plan), the project's folder."
  {:computer "M3.5 5h17v11h-17zM9 20h6M12 16v4"
   :account "M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6 5.6 18.4"
   :project "M3 7.5V18a1.5 1.5 0 0 0 1.5 1.5h15A1.5 1.5 0 0 0 21 18V9a1.5 1.5 0 0 0-1.5-1.5h-7.7L9.5 5H4.5A1.5 1.5 0 0 0 3 6.5z"})

(defn- field
  "One pick of row 2, its icon (field-icons, named by k) before it; label names it for screen readers and the tip."
  [k label & body]
  (into [:div.head-field {:data-tip label}
         [:svg.head-ico {:viewBox "0 0 24 24" :role "img" :aria-label label :fill "none" :stroke "currentColor"
                         :stroke-width 1.7 :stroke-linecap "round" :stroke-linejoin "round"}
          [:path {:d (field-icons k)}]]]
        body))

;; ---- row 1: names, talking mode, archive ----

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
   :reconnecting ["M20 12a8 8 0 0 0-14-5.3L4 9" "M4 4v5h5" "M4 12a8 8 0 0 0 14 5.3l2-2.3" "M20 20v-5h-5"]})

(defn- state-icon
  "Whether the server is there: a power sign when it is off, turning arrows while the page
  reconnects, nothing when it is connected."
  [status label]
  (when-let [s (pure/server-state status)]
    (let [tip (if (= :off s) (str label " is off") "reconnecting…")]
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

(defn- archive-button [ck row]
  (let [out? (boolean (:archived row))]
    [:button.icon.archive {:title (if out? "Unarchive" "Archive") :aria-label (if out? "Unarchive" "Archive")
                           :on-click #(rf/dispatch [:chat/archive ck (not out?)])}
     [archive-icon out?]]))

(defn- row-1 [{:keys [ck row ro] :as hc}]
  [:div.head-row.r1
   [:f> head-names hc]
   [alerts/sounds-button]
   [talk/toggle]
   (when (and row (not ro)) (archive-button ck row))])

;; ---- row 2: computer, account, project ----

(defn- computer-pick
  "A new chat's computer (a pick of the servers there are); a started chat's, as its name."
  [{:keys [new? server ro]}]
  (let [servers @(rf/subscribe [:server-list])
        all @(rf/subscribe [:servers])
        label @(rf/subscribe [:server-label server])]
    (field :computer "Computer"
           (if (and new? (not ro) (> (count servers) 1))
             [:label.pick.computer {:title "The computer this chat runs on"}
              [:select {:value (str server) :on-change #(rf/dispatch [:new/server (.. % -target -value)])}
               (for [{n :name l :label} servers]
                 ^{:key n} [:option {:value n} (str l (when (= :closed (get-in all [n :status])) " (off)"))])]]
             [:span.head-val {:title (str "Runs on " label)} label]))))

(defn- plan-limit [label l now]
  [:span.plan-limit {:class (when (and l (not (usage-pure/reset-since? l now))) (usage-pure/level (:percent l)))}
   (usage-pure/limit-text label l now)])

(defn- plan-list
  "The saved plans with their week and 5-hour use (what the hub last heard) and the hours until
  each resets."
  [plans on pick]
  (let [now (hooks/use-now)
        tokens @(rf/subscribe [:tokens])
        rows (usage-pure/token-rows (:tokens tokens) (get-in tokens [:inUse :name]) @(rf/subscribe [:live-limits]))]
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

(defn- account-pick
  "Whose Claude subscription the chat runs on (README decision 16): a click opens the saved plans;
  a pick applies at once."
  [{:keys [plans on put! ro]}]
  (let [[open toggle! close! box] (use-popup)]
    (when (seq plans)
      (field :account "Claude subscription"
             [:div.plan-pick {:ref #(set! (.-current box) %)}
              [:button.head-btn {:disabled ro :on-click toggle! :aria-haspopup "true" :aria-expanded open
                                 :title "Claude subscription of this chat"}
               (or (:name on) "Default")]
              (when open [:f> plan-list plans on (fn [k] (put! {:plan k}) (close!))])]))))

(defn- palette [color pick]
  [:div.palette {:role "menu"}
   (for [c colours]
     ^{:key c}
     [:button.swatch {:class [(when (= c color) "on") (when (= c "ash") "passive")]
                      :style {:background (str "var(--c-" c ")")}
                      :title (if (= c "ash") "Passive: sinks to the bottom of the rail" c) :aria-label c
                      :on-click #(pick c)}])
   [:button.swatch.none {:class (when-not color "on") :title "No colour" :aria-label "No colour"
                         :on-click #(pick nil)}]])

(defn- use-machine
  "The chat's computer's newest sample (GET /hub/servers/<server>/system, once), nil until it answers."
  [server]
  (let [[sample set-sample] (h/use-state nil)]
    (h/use-effect
     (fn []
       (-> (api/fetch-json (str "/hub/servers/" (js/encodeURIComponent server) "/system"))
           (.then set-sample)
           (.catch (fn [_] (set-sample {:error true}))))
       js/undefined)
     [server])
    sample))

(defn- where-card
  "Where the chat works: the folder's full path, then its computer: name, system, kernel, processor,
  memory and uptime."
  [{:keys [server folder]}]
  (let [sample (use-machine server)
        label @(rf/subscribe [:server-label server])]
    [:div.where
     [:div.where-path {:title folder} (if (seq folder) folder "The computer's working folder (no project)")]
     [:div.where-machine
      [:b label]
      (cond (nil? sample) [:span.muted "…"]
            (:error sample) [:span.muted "not reached"]
            :else (for [line (systems-pure/machine-lines sample)] ^{:key line} [:span line]))]]))

(defn- project-pop
  "Where the chat works (where-card), the chat's colour (a new one's goes with `new`), and the
  projects it can move to (a new chat: start in). A pick closes it."
  [{:keys [new? ck server row settings put!] :as hc} close!]
  (let [act (fn [f] (fn [] (f) (close!)))]
    [:div.proj-pop {:role "menu"}
     [:f> where-card hc]
     [:div.plan-title "Colour"]
     (if new?
       (palette (:color settings) (fn [c] (put! {:color c}) (close!)))
       (palette (:color row) (fn [c] (rf/dispatch [:ops/color ck c]) (close!))))
     [:div.plan-title "Project"]
     (if new?
       [:f> chatmenu/project-list {:server server :current (:folder settings) :none? (seq (:folder settings))
                                   :pick #(put! {:folder %}) :act act}]
       [chatmenu/chat-projects ck row act])]))

(defn- project-pick
  "The project's small logo (dot/project-face) and its name (its folder's when it is in no
  project); a click opens project-pop."
  [{:keys [server row folder ro] :as hc}]
  (let [[open toggle! close! box] (use-popup)
        label (pure/project-label (get-in row [:project :name]) folder)]
    (field :project "Project"
           [:div.proj-pick {:ref #(set! (.-current box) %)}
            [:button.proj-btn {:disabled ro :on-click toggle! :aria-haspopup "true" :aria-expanded open
                               :title (str "Project of this chat" (when (seq folder) (str " · " folder)))}
             [dot/project-face server folder]
             [:span.proj-name label]]
            (when open (project-pop hc close!))])))

(defn- row-2 [hc]
  [:div.head-row.r2
   (computer-pick hc)
   [:f> account-pick hc]
   [:f> project-pick hc]])

;; ---- row 3: model, effort, rings ----

(defn- plan-limits
  "The 5-hour and week Limits of the plan a chat runs on (on; none saved: the server's own plan)."
  [on plans]
  (let [tokens @(rf/subscribe [:tokens])
        live @(rf/subscribe [:live-limits])
        rows (usage-pure/token-rows (:tokens tokens) (get-in tokens [:inUse :name]) live)]
    (if on
      (some #(when (= (:key on) (:key %)) %) (usage-pure/plan-rows plans rows))
      {:five (usage-pure/limit-of (:limits live) "five_hour")
       :week (usage-pure/limit-of (:limits live) "seven_day")})))

(defn- limit-ring [k label l now]
  (when-let [p (usage-pure/ring-percent l now)]
    [usage/ring {:short (usage-pure/short-name k) :percent p :tip (usage-pure/limit-text label l now)}]))

(defn- row-3 [{:keys [new? oc server plans on]}]
  (let [now (hooks/use-now)
        {:keys [five week]} (plan-limits on plans)]
    [:div.head-row.r3
     [:span.head-picks [usage/pickers (if new? {:server server} oc)]]
     [:span.head-rings
      (when-not new? [usage/context-ring (assoc oc :pending? true)])
      (limit-ring "five_hour" "5-hour" five now)
      (limit-ring "seven_day" "Week" week now)]]))

;; ---- the header ----

(defn- use-tokens!
  "The hub's saved tokens with their limits, loaded now and every minute."
  []
  (h/use-effect (fn [] (rf/dispatch [:ops/load-tokens])
                  (let [t (js/setInterval #(rf/dispatch [:ops/load-tokens]) 60000)] #(js/clearInterval t)))
                []))

(defn- head-context
  "What the rows need, the same for a new chat (oc nil or not started) and a started one: where it
  runs, its settings and how a pick is put, its plans and the plan it runs on."
  [{:keys [ck state row pending?] :as oc} ro]
  (let [new? (not (and oc (or row (seq (:messages state)) pending?)))
        server (if new? @(rf/subscribe [:new-server]) (:server oc))
        settings (if new? @(rf/subscribe [:new-settings]) (:settings state))
        {:keys [plans planKey]} @(rf/subscribe [:meta server])]
    (assoc (if new? {} oc)
           :new? new? :oc oc :server server :settings settings :plans plans
           :on (pure/chat-plan plans (:plan settings) planKey)
           :folder (if new? (:folder settings) (or (:folder row) (:folder state)))
           :ro (or ro (and (not new?) (or pending? (not row))))
           :put! (if new? #(rf/dispatch [:new/settings %]) #(rf/dispatch [:chat/settings ck %])))))

(defn- head-view [oc ro]
  (let [hc (head-context oc ro)]
    (use-tokens!)
    [:header.chat-head
     [:div.head-panel {:style {"--chat" (words/colour-var (if (:new? hc) (get-in hc [:settings :color]) (get-in hc [:row :color])))}}
      (row-1 hc)
      (row-2 hc)
      [:f> row-3 hc]]]))

(defn header
  "The header of the open chat oc (nil: a new chat)."
  [oc]
  [:f> head-view oc @(rf/subscribe [:read-only?])])
