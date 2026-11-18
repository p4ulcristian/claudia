(ns claudia.web.views.head
  "The chat's header (README decision 22): one panel lightly tinted with the chat's colour, three
  rows, the same for a new chat and a started one. Row 1: the title (a click renames it) and the
  server's state when it is off or unreached. Row 2: five pills of one look: the computer (its
  popup: where it works), the folder (opens Folders, README decisions 61 to 65), whose Claude
  subscription, model · effort, the prompt profile. Row 3: the rings (context, 5-hour, week) on the
  left; on the right the round buttons: colour, bell, speaker, root, archive. A new chat picks all
  of them here before its first message (they go with `new`); once it has started the computer is
  fixed and every other pick changes the chat."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.alerts :as alerts]
            [claudia.web.api :as api]
            [claudia.web.format :as format]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.prompts :as prompts]
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

(def ^:private pill-icons
  "Row 2's icons in place of words: the computer, the folder, Claude's spark (whose Claude plan), a
  sparkle (model · effort), a page (the prompt profile)."
  {:computer "M3.5 5h17v11h-17zM9 20h6M12 16v4"
   :folder "M3 7.5V18a1.5 1.5 0 0 0 1.5 1.5h15A1.5 1.5 0 0 0 21 18V9a1.5 1.5 0 0 0-1.5-1.5h-7.7L9.5 5H4.5A1.5 1.5 0 0 0 3 6.5z"
   :account "M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6 5.6 18.4"
   :model "M12 3l2.2 5.8L20 11l-5.8 2.2L12 19l-2.2-5.8L4 11l5.8-2.2z"
   :prompt "M6 3.5h9l3.5 3.5v13.5H6zM15 3.5V7h3.5M9 11h6M9 14.5h6M9 18h4"})

(defn- pill
  "One setting of row 2, every one the same: its icon (pill-icons, named by k), the value and a ▾
  when it can be changed. fixed? (a started chat's computer): a dashed edge, no ▾. label names it
  for the tip and screen readers."
  [{:keys [k label value fixed? open on-click ro class]}]
  [:button.head-pill {:type "button" :class [class (when fixed? "fixed")] :disabled ro :data-tip label
                      :aria-label label :aria-haspopup "true" :aria-expanded (boolean open) :on-click on-click}
   [:svg.head-ico {:viewBox "0 0 24 24" :aria-hidden true :fill "none" :stroke "currentColor"
                   :stroke-width 1.7 :stroke-linecap "round" :stroke-linejoin "round"}
    [:path {:d (pill-icons k)}]]
   [:span.pill-val value]
   (when-not (or fixed? ro) [:span.pill-caret "▾"])])

(defn- use-inside-panel!
  "While open, moves the popup in box (.head-pop) left so it ends inside the header panel (a pill
  near the right edge would push it out of the chat column)."
  [box open]
  (h/use-layout-effect
   (fn []
     (when-let [^js el (and open (some-> (.-current box) (.querySelector ".head-pop")))]
       (when-let [^js panel (.closest el ".head-panel")]
         (let [over (- (.-right (.getBoundingClientRect el)) (.-right (.getBoundingClientRect panel)))]
           (when (pos? over) (set! (.. el -style -transform) (str "translateX(" (- over) "px)"))))))
     js/undefined)
   [open]))

(defn- popped
  "A pill with its popup under it (opened by a click, closed by a pointer down outside or Escape),
  kept inside the header panel. opts: as pill; popup-of: (fn [close!]) the popup's markup."
  [opts popup-of]
  (let [[open toggle! close! box] (use-popup)]
    (use-inside-panel! box open)
    [:div.head-field {:ref #(set! (.-current box) %)}
     [pill (assoc opts :open open :on-click toggle!)]
     (when open (popup-of close!))]))

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

(defn- archive-button [ck row]
  (let [out? (boolean (:archived row))]
    [:button.icon.archive {:title (if out? "Unarchive" "Archive") :aria-label (if out? "Unarchive" "Archive")
                           :on-click #(rf/dispatch (if out? [:chat/archive ck false] [:archive/ask ck]))}
     [archive-icon out?]]))

(defn- root-button
  "The chat's root switch (README decision 52), only for its owner: red while on; turning it on asks
  first."
  [ck]
  (let [on? @(rf/subscribe [:root? ck])]
    [:button.icon.root-toggle {:type "button" :class (when on? "on") :aria-pressed on? :aria-label "Root"
                               :data-tip (if on? "Root on: sudo runs without asking. Click to turn it off"
                                             "Root off: each sudo command asks you first")
                               :on-click #(rf/dispatch (if on? [:chat/root ck false] [:root/ask ck]))}
     [:svg {:viewBox "0 0 24 24" :aria-hidden true :fill "none" :stroke "currentColor" :stroke-width 1.8
            :stroke-linecap "round" :stroke-linejoin "round"}
      [:path {:d "M12 3l7 3v5c0 4.5-3 8.3-7 10-4-1.7-7-5.5-7-10V6z"}]
      [:path {:d "M10 9.5h3a1.5 1.5 0 0 1 0 3h-3v-3M10 12.5V15M12.6 12.5 14 15"}]]]))

(defn- row-1 [hc]
  [:div.head-row.r1 [:f> head-names hc]])

;; ---- row 2: computer, folder, subscription, model · effort, prompt ----

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

(defn- account-pick
  "Whose Claude subscription the chat runs on (README decision 16): a click opens the saved plans;
  a pick applies at once, and becomes the person's own last pick (README decision 57). None yet:
  it asks for one."
  [{:keys [plans on put! ro]}]
  (when (seq plans)
    [:f> popped {:k :account :label "Claude subscription" :value (or (:name on) "Pick one") :ro ro}
     (fn [close!]
       [:div.head-pop
        [:f> plan-list plans on (fn [k] (put! {:plan k}) (rf/dispatch [:ops/token-picked k]) (close!))]])]))

(defn- palette [color pick]
  [:div.palette {:role "menu"}
   (for [c colours]
     ^{:key c}
     [:button.swatch {:class [(when (= c color) "on") (when (= c "ash") "passive")]
                      :style {:background (str "var(--c-" c ")")}
                      :title (if (= c "ash") "Passive: sinks to the bottom of the rail" c) :aria-label c
                      :on-click #(pick c)}])])

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

(defn- shown-folder
  "The folder a chat works in: its own, else (not for a read-only view, whose runner is another
  profile's) its server's work folder once known; nil while unknown."
  [{:keys [server folder ro]}]
  (or (not-empty folder)
      (when-not ro
        (or @(rf/subscribe [:work-folder server])
            (do (rf/dispatch [:folders/load-work server]) nil)))))

(defn- where-card
  "Where the chat works: the folder's full path, then its computer: name, system, kernel, processor,
  memory and uptime."
  [{:keys [server] :as hc}]
  (let [sample (use-machine server)
        label @(rf/subscribe [:server-label server])
        shown (shown-folder hc)]
    [:div.where
     [:div.where-path {:title shown} (or shown "The computer's work folder")]
     [:div.where-machine
      [:b label]
      (cond (nil? sample) [:span.muted "…"]
            (:error sample) [:span.muted "not reached"]
            :else (for [line (systems-pure/machine-lines sample)] ^{:key line} [:span line]))]]))

(defn- server-list
  "A new chat's computers to pick from (more than one); a pick closes the popup."
  [server close!]
  (let [servers @(rf/subscribe [:server-list])
        all @(rf/subscribe [:servers])]
    [:div.server-list
     [:div.plan-title "Run on"]
     (for [{n :name l :label} servers]
       ^{:key n}
       [:button.plan-item {:role "menuitemradio" :aria-checked (= n server) :class (when (= n server) "on")
                           :on-click (fn [] (rf/dispatch [:new/server n]) (close!))}
        [:span.plan-name (str l (when (= :closed (get-in all [n :status])) " (off)"))]])]))

(defn- computer-pick
  "The computer the chat runs on: a click shows where it works (where-card) and, for a new chat with
  more than one computer, the others to pick; a started chat's computer is fixed (dashed, no ▾)."
  [{:keys [new? server ro] :as hc}]
  (let [pick? (and new? (not ro) (> (count @(rf/subscribe [:server-list])) 1))]
    [:f> popped {:k :computer :label "Computer" :value @(rf/subscribe [:server-label server]) :fixed? (not pick?)}
     (fn [close!]
       [:div.head-pop
        [:f> where-card hc]
        (when pick? [server-list server close!])])]))

(defn- folder-value
  "The folder pill's value: the working path (~ for the home, cut at its start so the end shows);
  a new chat with none picked: \"Pick a folder\" (README decision 65)."
  [{:keys [new? folder] :as hc}]
  (if (and new? (empty? folder))
    [:span.pill-empty "Pick a folder"]
    (let [shown (shown-folder hc)]
      [:span.folder-path [:bdi (if shown (format/tilde shown) "work folder")]])))

(defn- folder-pick
  "The folder the chat works in; a click opens the Folders modal (README decisions 61 to 64) on this
  chat's server, the folder selected."
  [{:keys [new? ck server ro folder] :as hc}]
  (let [shown (when-not (and new? (empty? folder)) (shown-folder hc))]
    [:div.head-field
     [pill {:k :folder :label (str "Folder" (when shown (str ": " shown))) :value (folder-value hc) :ro ro :class "folder-pill"
            :on-click #(rf/dispatch [:folders/open {:server server :folder shown :chat (when-not new? ck)}])}]]))

(defn- model-pick
  "Model · effort in one pill; its popup lists both (claudia.web.views.usage)."
  [{:keys [new? oc server ro]}]
  (let [ms (usage/model-state (if new? {:server server} oc))]
    [:f> popped {:k :model :label "Model · effort" :value (:text ms) :ro ro}
     (fn [close!] (usage/model-effort-pop ms close!))]))

(defn- prompt-pick
  "The prompt profile on top of Claudia's built-in part (README decision 60)."
  [{:keys [server settings put! ro]}]
  (let [picked (prompts/use-profiles server settings)]
    [:f> popped {:k :prompt :label "Prompt profile" :value (or picked "built-in") :ro ro}
     (fn [close!] [prompts/pick-pop server settings put! close!])]))

(defn- row-2 [hc]
  [:div.head-row.r2
   [:f> computer-pick hc]
   [folder-pick hc]
   [:f> account-pick hc]
   [:f> model-pick hc]
   [:f> prompt-pick hc]])

;; ---- row 3: rings, actions ----

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

(defn- limit-ring [label l now]
  (when-let [p (usage-pure/ring-percent l now)]
    [usage/mini-ring {:percent p :tip (usage-pure/limit-text label l now)}]))

(defn- colour-button
  "The chat's colour as a filled dot in a round button; a click opens the palette (a new chat's goes
  with `new`)."
  [{:keys [new? ck row settings put!]}]
  (let [[open toggle! close! box] (use-popup)
        color (or (if new? (:color settings) (:color row)) "ash")]
    [:div.head-act {:ref #(set! (.-current box) %)}
     [:button.icon.colour-btn {:type "button" :aria-haspopup "true" :aria-expanded open :aria-label "Colour"
                               :data-tip "This chat's colour" :on-click toggle!}
      [:span.colour-dot {:class (when (= color "ash") "passive") :style {:background (str "var(--c-" color ")")}}]]
     (when open
       [:div.head-pop.colour-pop
        (palette color (fn [c] (if new? (put! {:color c}) (rf/dispatch [:ops/color ck c])) (close!)))])]))

(defn- actions
  "Row 3's buttons on the right, all round: colour, bell, speaker, root (its owner, a started chat),
  archive (a started chat). A chat only looked at has none."
  [{:keys [ck row ro new? settings] :as hc}]
  (let [k (when-not new? ck)]
    (when-not ro
      [:span.head-acts
       [:f> colour-button hc]
       [alerts/bell-button k (if new? (:color settings) (:color row))]
       [talk/toggle k]
       (when (and row @(rf/subscribe [:owner?])) [root-button ck])
       (when row (archive-button ck row))])))

(defn- row-3
  "The rings on the left (context for a started chat, 5-hour, week: each a small ring and its %),
  the buttons on the right."
  [{:keys [new? oc plans on] :as hc}]
  (let [now (hooks/use-now)
        {:keys [five week]} (plan-limits on plans)]
    [:div.head-row.r3
     [:span.head-rings
      (when-not new? [usage/context-ring (assoc oc :pending? true)])
      (limit-ring "5-hour" five now)
      (limit-ring "Week" week now)]
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
