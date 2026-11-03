(ns claudia.web.views.settings
  "The list's title menu (whose chats, which servers), the Settings screen behind the gear (archived
  chats, all ~/Work sessions, sounds and desktop popups, the instance chip, the plan, each server's Claude tokens and GitHub
  login) and the GitHub prompt for a profile without a login."
  (:require [clojure.string :as str]
            [claudia.web.format :as format]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.alerts :as alerts]
            [claudia.web.chatops :as ops]
            [claudia.web.logo :as logo]
            [claudia.web.views.pure.settings :as pure]
            [claudia.web.views.pure.words :as words]
            [claudia.web.views.usage :as usage]))

;; ---- the title menu ----

(defn- title-box
  "The title's menu box: a click outside or Escape closes it."
  [open & children]
  (let [box (h/use-ref nil)]
    (h/use-effect
     (fn []
       (when @open
         (let [away (fn [e] (when-not (some-> (.-current box) (.contains (.-target e))) (reset! open false)))
               esc (fn [e] (when (= "Escape" (.-key e)) (.stopPropagation e) (reset! open false)))]
           (js/window.addEventListener "pointerdown" away true)
           (js/window.addEventListener "keydown" esc true)
           #(do (js/window.removeEventListener "pointerdown" away true)
                (js/window.removeEventListener "keydown" esc true)))))
     [@open])
    (into [:div.cs-title-box {:ref #(set! (.-current box) %)}] children)))

(defn- whose-menu [open view people]
  [:<>
   [:div.cs-menu-head "Whose chats"]
   (for [[k label] (pure/whose-items people)]
     ^{:key (str k)}
     [:button.cs-menu-item {:class (when (= k view) "on") :role "menuitemradio" :aria-checked (= k view)
                            :on-click #(do (reset! open false) (when (not= k view) (rf/dispatch [:ops/view k])))}
      [:span.cs-tick {:aria-hidden true} (when (= k view) "✓")]
      [:span.cs-menu-label label]
      (when k [:span.cs-menu-note "read only"])])])

(defn- servers-menu [{:keys [view servers usable ticked]}]
  [:<>
   [:div.cs-menu-head "Servers"]
   (for [{server-name :name :keys [label]} servers
         :let [inv (some #(= server-name (:name %)) usable)
               on (and inv (some #(= server-name (:name %)) ticked))]]
     ^{:key server-name}
     [:button.cs-menu-item {:class (when on "on") :role "menuitemcheckbox" :aria-checked (boolean on)
                            :disabled (or (not inv) (and on (= 1 (count ticked))))
                            :on-click #(rf/dispatch [:ops/tick-server server-name])}
      [:span.cs-tick.box {:aria-hidden true} (when on "✓")]
      [:span.server-mark {:aria-hidden true} (words/initial label)]
      [:span.cs-menu-label label]
      (when-not inv [:span.cs-menu-note.warn (str "no " (words/capital view) " here")])])])

(defn- title-menu-box [open text {:keys [view people servers] :as m}]
  [:f> title-box open
   [:button.cs-title {:aria-haspopup "menu" :aria-expanded @open :on-click #(swap! open not)}
    text
    [:svg.cs-caret {:viewBox "0 0 12 12" :aria-hidden true} [:path {:d "M3 4.5l3 3 3-3"}]]]
   (when @open
     [:<>
      [:div.cs-menu {:role "menu"}
       (when (seq people) (whose-menu open view people))
       (when (> (count servers) 1) (servers-menu m))]])])

(defn title-menu []
  (let [open (r/atom false)]
    (fn []
      (let [view @(rf/subscribe [:view])
            people @(rf/subscribe [:people])
            servers @(rf/subscribe [:server-list])
            hidden @(rf/subscribe [:hidden-servers])
            mes @(rf/subscribe [:server-mes])
            {:keys [archived?]} @(rf/subscribe [:list-mode])
            {:keys [usable ticked]} (pure/servers-in-view servers view mes hidden)
            hint (pure/servers-hint usable ticked)
            text [:<> [:span.cs-title-t (pure/list-title archived? view)] (when hint [:span.cs-title-hint (str "· " hint)])]]
        (if-not (or (seq people) (> (count servers) 1))
          [:h2.cs-title text]
          (title-menu-box open text {:view view :people people :servers servers :usable usable :ticked ticked}))))))

(defn lock-icon []
  [:span.cs-ro {:title "Read only" :aria-label "read only"}
   [:svg {:viewBox "0 0 16 16" :aria-hidden true} [:rect {:x 3.5 :y 7 :width 9 :height 6.5 :rx 1.5}] [:path {:d "M5.5 7V5.2a2.5 2.5 0 0 1 5 0V7"}]]])

(defn gear-button []
  [:button.cs-icon.cs-gear {:aria-label "Settings" :data-tip "Settings" :on-click #(rf/dispatch [:ops/settings true])}
   [:svg {:viewBox "0 0 20 20" :aria-hidden true}
    [:circle {:cx 10 :cy 10 :r 2.6}]
    [:path {:d "M10 2.5v2.2M10 15.3v2.2M2.5 10h2.2M15.3 10h2.2M4.7 4.7l1.6 1.6M13.7 13.7l1.6 1.6M4.7 15.3l1.6-1.6M13.7 6.3l1.6-1.6"}]
    [:circle {:cx 10 :cy 10 :r 5.4}]]])

;; ---- token forms ----

(defn- submit-token
  "The token form's submit: save, then clear it unless it failed."
  [{:keys [value nm busy error]} ready save]
  (fn [e]
    (.preventDefault e)
    (when ready
      (reset! busy true)
      (save @value (str/trim @nm)
            (fn [err]
              (reset! busy false)
              (reset! error err)
              (when-not err (reset! value "") (reset! nm "")))))))

(defn- token-form [_props]
  (let [value (r/atom "") nm (r/atom "") busy (r/atom false) error (r/atom nil)]
    (fn [{:keys [placeholder named? save]}]
      (let [ready (and (seq (str/trim @value)) (or (not named?) (seq (str/trim @nm))))]
        [:form.token-form {:class (when named? "named")
                           :on-submit (submit-token {:value value :nm nm :busy busy :error error} ready save)}
         (when named?
           [:input.token-name {:auto-complete "off" :spell-check false :placeholder "Whose (Paul)"
                               :value @nm :on-change #(reset! nm (.. % -target -value))}])
         [:input {:type "password" :auto-complete "off" :spell-check false :placeholder placeholder
                  :value @value :on-change #(reset! value (.. % -target -value))}]
         [:button {:disabled (or @busy (not ready))} (if @busy "Saving…" "Save")]
         (when @error [:p.token-error @error])]))))

(def github-token-url "https://github.com/settings/tokens/new?scopes=repo,read:org,workflow&description=Claudia")

(defn- chat-colours
  "Whether every new chat gets a colour of its own on this server (the profile's chatColors pref)."
  [server]
  (let [[prefs set-prefs] (h/use-state nil)
        u (ops/api-url server "prefs")
        save! (fn [v] (-> (js/fetch u #js {:method "PATCH" :headers #js {"content-type" "application/json"}
                                           :body (js/JSON.stringify #js {:chatColors v})})
                          (.then #(when (.-ok %) (.json %)))
                          (.then #(when % (set-prefs (js->clj % :keywordize-keys true))))))]
    (h/use-effect
     (fn []
       (-> (js/fetch u) (.then #(when (.-ok %) (.json %)))
           (.then #(when % (set-prefs (js->clj % :keywordize-keys true)))))
       nil)
     [u])
    [:section.st-section
     [:h3 "Chat colours"]
     [:div.choice
      [:label [:input {:type "radio" :name (str "chatColors-" server) :checked (= "each" (:chatColors prefs))
                       :on-change #(save! "each")}]
       "A colour of its own for every new chat"]
      [:label [:input {:type "radio" :name (str "chatColors-" server) :checked (= "none" (:chatColors prefs))
                       :on-change #(save! "none")}]
       "Default look; colour a chat by hand from its header dot"]]]))

(defn- put-fn
  "A write to server's API now: on-ok gets the answer, done the error (nil when it went)."
  [server]
  (fn [method p body on-ok done]
    (rf/dispatch [:ops/put-now {:url (ops/api-url server p) :method method :body body
                                :on-done (fn [ok err] (when ok (on-ok ok)) (when done (done err)))}])))

(defn- token-status [tokens in-use]
  [:p.small
   (cond (nil? tokens) "Checking…"
         (nil? (:inUse tokens)) "None in use from Settings yet."
         (not in-use) (str "In use: a token without a name, set by " (get-in tokens [:inUse :by]) ".")
         :else [:<> "Switched to " [:b in-use] " by " (get-in tokens [:inUse :by]) ", " (format/when-text (get-in tokens [:inUse :at])) "."])
   " A switch applies to every chat on its next message."])

(defn- token-choice [server tokens in-use put!]
  [:div.choice
   (for [t (:tokens tokens)]
     ^{:key (:name t)}
     [:label
      [:input {:type "radio" :name (str "claudeToken-" server) :checked (= (:name t) in-use)
               :on-change #(put! "POST" "claude-token/use" {:name (:name t)}
                                 (fn [ok] (rf/dispatch [:ops/tokens server ok])) nil)}]
      (:name t) " " [:span.muted.small (str "saved by " (:by t) ", " (format/when-text (:at t)))]])])

(defn- subscription-section [server put!]
  (let [tokens @(rf/subscribe [:tokens server])
        in-use (get-in tokens [:inUse :name])]
    [:section.st-section
     [:h3 "Claude subscription " [:span.muted.small "shared by every profile here"]]
     (token-status tokens in-use)
     (when (seq (:tokens tokens)) (token-choice server tokens in-use put!))
     [:p.small "Add one, or replace one by its name: make it with " [:code "claude setup-token"] ". Saving switches to it."]
     [token-form {:named? true :placeholder "Claude token (sk-ant-…)"
                  :save (fn [token nm done]
                          (put! "PUT" "claude-token" {:name nm :token token}
                                (fn [ok] (rf/dispatch [:ops/tokens server ok])) done))}]]))

(defn- github-section [server put!]
  (let [github @(rf/subscribe [:github server])]
    [:section.st-section
     [:h3 "GitHub " [:span.muted.small "yours only"]]
     [:p.small
      (cond (nil? github) "Checking…"
            github [:<> "Pushes and pull requests come from " [:b (str "@" (:login github))] "."]
            :else "Not connected: Claude can't push or open pull requests for you.")
      " " [:a {:href github-token-url :target "_blank" :rel "noreferrer"} "Create a token on GitHub"]
      " (the boxes come ticked) and paste it here."]
     [token-form {:placeholder (if github "Replace with a new token" "GitHub token (ghp_…)")
                  :save (fn [token _ done]
                          (put! "PUT" "github" {:token token}
                                (fn [ok] (rf/dispatch [:ops/github-user server ok])) done))}]]))

(defn- server-settings [server]
  (let [me @(rf/subscribe [:server-me server])
        put! (put-fn server)]
    [:<>
     [:p.muted.small (when-let [e (:email @(rf/subscribe [:me]))] (str "Signed in as " e " · ")) "profile " [:b (:profile me)]]
     (when-not (:readOnly me) [:f> chat-colours server])
     (subscription-section server put!)
     (github-section server put!)]))

(rf/reg-event-fx :ops/put-now (fn [_ [_ m]] {:ops/put m}))

(defn- brand [server]
  (let [me @(rf/subscribe [:server-me server])
        tokens @(rf/subscribe [:tokens server])
        token (get-in tokens [:inUse :name])]
    [:div.brand
     [logo/mark]
     [:span.wordmark "Claudia"]
     (when me [:span.brand-break])
     (when me
       [:span.instance {:title (str "Claudia " (:version me) (when (:commit me) (str " (" (:commit me) ")"))
                                    (when (:server me) (str " on " (:server me)))
                                    (when token (str ", on " token "'s Claude subscription")))}
        (when (:server me) [:span.instance-name (:server me)])
        (when token [:span.instance-token token])
        [:span.instance-version (or (:commit me) (str "v" (:version me)))]])]))

(def ^:private chev [:svg.st-chev {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M7.5 4l6 6-6 6"}]])

(defn- archive-icon []
  [:svg {:width 16 :height 16 :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width 1.5
         :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden true}
   [:rect {:x 1.5 :y 2.5 :width 13 :height 3.5 :rx 0.8}]
   [:path {:d "M2.8 6v6.5a1 1 0 0 0 1 1h8.4a1 1 0 0 0 1-1V6"}]
   [:path {:d "M6.3 9h3.4"}]])

(defn- server-pick
  "Which server's settings (a tab each, with two or more), and its name, version and commit."
  [servers server]
  (let [all @(rf/subscribe [:servers])
        me @(rf/subscribe [:server-me server])
        label @(rf/subscribe [:server-label server])]
    [:div.settings-servers
     (when (> (count servers) 1)
       [:div.profile-tabs {:role "tablist" :aria-label "Which server's settings"}
        (for [{server-name :name tab-label :label} servers
              :let [up? (= :open (get-in all [server-name :status]))]]
          ^{:key server-name}
          [:button {:role "tab" :aria-selected (= server-name server) :class (when (= server-name server) "on") :disabled (not up?)
                    :title (if up? (str tab-label "'s settings") (str tab-label " is off"))
                    :on-click #(rf/dispatch [:ops/load-settings server-name])}
           tab-label
           (when-not up? [:span.sf-note "off"])])])
     (when me
       [:p.muted.small [:b label] (when (:name me) (str " (" (:name me) ")")) " · Claudia " (:version me) " "
        [:span.mono (:commit me)]])]))

(defn- switch-row [on on-change label note]
  [:label.st-row.st-switch
   [:span.st-label label (when note [:span.st-note note])]
   [:input {:type "checkbox" :role "switch" :checked (boolean on) :on-change #(on-change (.. % -target -checked))}]
   [:span.st-knob]])

(defn- back! [] (rf/dispatch [:ops/settings false]))

(defn- use-escape-back!
  "Escape goes back while shown, before the page's own keys see it (Escape there stops a chat)."
  [shown?]
  (h/use-effect
   (fn []
     (when shown?
       (let [esc (fn [^js e]
                   (when (and (= "Escape" (.-key e)) (not (some-> (.-target e) (.closest "input, textarea"))))
                     (.preventDefault e)
                     (.stopPropagation e)
                     (back!)))]
         (js/window.addEventListener "keydown" esc true)
         #(js/window.removeEventListener "keydown" esc true))))
   [shown?]))

(defn- chats-group [all-sessions?]
  [:section.st-group {:aria-label "Chats"}
   [:button.st-row {:on-click #(rf/dispatch [:ops/archived true])}
    [archive-icon]
    [:span.st-label "Archived chats"]
    chev]
   [switch-row all-sessions? #(rf/dispatch [:ops/all-sessions %]) "Show all ~/Work sessions"]])

(defn- drawing-group []
  [:section.st-group {:aria-label "Drawing"}
   [:button.st-row {:on-click #(rf/dispatch [:gallery/open true])}
    [:svg.st-icon {:viewBox "0 0 20 20" :aria-hidden true}
     [:rect {:x 3 :y 3 :width 6 :height 6 :rx 1.5}] [:circle {:cx 14 :cy 6 :r 3}] [:path {:d "M3 15h6M11 12.5l3 3 3-3"}]]
    [:span.st-label "Forms " [:span.st-note "everything Claude can draw, and your templates"]]
    chev]])

(defn- hub-group []
  [:section.st-group {:aria-label "Hub"}
   [:button.st-row {:on-click #(rf/dispatch [:admin/open :servers])}
    [:span.st-label "Servers " [:span.st-note "the servers this hub reaches"]]]
   [:button.st-row {:on-click #(rf/dispatch [:admin/open :access])}
    [:span.st-label "Access " [:span.st-note "who may use which server"]]]])

(defn- settings-view [shown?]
  (let [servers @(rf/subscribe [:server-list])
        server @(rf/subscribe [:settings-server])
        ro @(rf/subscribe [:read-only?])
        {:keys [all-sessions?]} @(rf/subscribe [:list-mode])]
    (use-escape-back! shown?)
    [:div.st-pane {:role "dialog" :aria-label "Settings" :aria-hidden (not shown?) :inert (when-not shown? true)}
     [:header.st-head
      [:button.st-back {:on-click back!}
       [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M12.5 4L6.5 10l6 6"}]] "Chats"]
      [:h2 "Settings"]]
     [:div.st-scroll
      [brand server]
      [:f> usage/plan-meters server]
      (chats-group all-sessions?)
      [:section.st-group {:aria-label "Alerts"}
       [alerts/toggles]]
      (drawing-group)
      (when (:admin @(rf/subscribe [:me])) (hub-group))
      (when (and (not ro) @(rf/subscribe [:server-me server]))
        [:div.st-server
         [server-pick servers server]
         ^{:key server} [server-settings server]])]]))

(defn settings-pane
  "The Settings screen over the list; mounted while shown and for its slide out."
  [shown?]
  (let [server @(rf/subscribe [:settings-server])]
    (r/with-let [_ (rf/dispatch [:ops/load-settings server])]
      [:f> settings-view shown?])))

;; ---- the GitHub prompt ----

(defn- github-done [login close]
  [:<>
   [:p "Done: Claude now pushes and opens pull requests as " [:b (str "@" login)] "."]
   [:div.prompt-actions [:button.primary {:on-click close} "Start"]]])

(defn- submit-github
  "The prompt's submit: the token to the server; done holds the login once it went."
  [server {:keys [value busy error done]}]
  (fn [e]
    (.preventDefault e)
    (reset! busy true)
    (rf/dispatch [:ops/put-now {:url (ops/api-url server "github") :method "PUT" :body {:token @value}
                                :on-done (fn [ok err]
                                           (reset! busy false)
                                           (reset! error err)
                                           (when ok
                                             (rf/dispatch [:ops/github-user server ok])
                                             (reset! done (get-in ok [:user :login]))))}])))

(defn- github-ask [server profile {:keys [value busy error] :as form} close]
  [:<>
   [:p "So Claude can push your branches and open pull requests as you, it needs a GitHub token of yours. It stays in your profile only."]
   [:ol.prompt-steps
    [:li [:a {:href github-token-url :target "_blank" :rel "noreferrer"} "Create a token on GitHub"]
     ": the boxes are already ticked; pick an expiry, then " [:b "Generate token"] "."]
    [:li "Copy it and paste it here."]]
   [:form.token-form
    {:on-submit (submit-github server form)}
    [:input {:type "password" :auto-complete "off" :spell-check false :placeholder "ghp_…" :auto-focus true
             :value @value :on-change #(reset! value (.. % -target -value))}]
    [:button {:disabled (or @busy (str/blank? @value))} (if @busy "Saving…" "Save")]
    (when @error [:p.token-error @error])]
   [:div.prompt-actions
    [:button {:on-click #(do (js/localStorage.setItem (ops/github-later-key server profile) "1") (close))} "Later"]
    [:span.muted.small "You can add it any time in Settings."]]])

(defn github-prompt []
  (let [form {:value (r/atom "") :busy (r/atom false) :error (r/atom nil) :done (r/atom nil)}]
    (fn []
      (when-let [{:keys [server profile]} @(rf/subscribe [:github-prompt])]
        (let [close #(rf/dispatch [:ops/github-prompt nil])]
          [:div.settings-backdrop
           [:div.settings.github-prompt {:role "dialog" :aria-label "Connect GitHub"}
            [:header [:h2 "Connect GitHub"]]
            (if @(:done form)
              (github-done @(:done form) close)
              (github-ask server profile form close))]])))))
