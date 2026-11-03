(ns claudia.web.views.settings
  "The list's title menu (whose chats, which servers), the Settings screen behind the gear (archived
  chats, all ~/Work sessions, sounds and desktop popups, the instance chip, the plan, each server's Claude tokens and GitHub
  login) and the GitHub prompt for a profile without a login."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.alerts :as alerts]
            [claudia.web.chatops :as ops]
            [claudia.web.logo :as logo]
            [claudia.web.views.usage :as usage]))

(defn- cap [n] (str (str/upper-case (subs n 0 1)) (subs n 1)))

(defn- when-text [at]
  (when at (.toLocaleString (js/Date. at) js/undefined #js {:dateStyle "medium" :timeStyle "short"})))

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

(defn title-menu []
  (let [open (r/atom false)]
    (fn []
      (let [view @(rf/subscribe [:view])
            people @(rf/subscribe [:people])
            servers @(rf/subscribe [:server-list])
            hidden @(rf/subscribe [:hidden-servers])
            mes @(rf/subscribe [:server-mes])
            {:keys [archived?]} @(rf/subscribe [:list-mode])
            in-view (fn [s] (or (nil? view) (some #{view} (get-in mes [s :profiles]))))
            usable (filter #(in-view (:name %)) servers)
            ticked (remove #(hidden (:name %)) usable)
            filtered? (and (seq ticked) (< (count ticked) (count usable)))
            title (cond archived? "Archived chats" view (str (cap view) "'s chats") :else "My chats")
            hint (when filtered? (if (= 1 (count ticked)) (:label (first ticked)) (str (count ticked) " servers")))
            has-menu? (or (seq people) (> (count servers) 1))
            text [:<> [:span.cs-title-t title] (when hint [:span.cs-title-hint (str "· " hint)])]]
        (if-not has-menu?
          [:h2.cs-title text]
          [:f> title-box open
           [:button.cs-title {:aria-haspopup "menu" :aria-expanded @open :on-click #(swap! open not)}
            text
            [:svg.cs-caret {:viewBox "0 0 12 12" :aria-hidden true} [:path {:d "M3 4.5l3 3 3-3"}]]]
           (when @open
             [:<>
              [:div.cs-menu {:role "menu"}
               (when (seq people)
                 [:<>
                  [:div.cs-menu-head "Whose chats"]
                  (for [[k label] (cons [nil "My chats"] (map (fn [p] [p (str (cap p) "'s chats")]) people))]
                    ^{:key (str k)}
                    [:button.cs-menu-item {:class (when (= k view) "on") :role "menuitemradio" :aria-checked (= k view)
                                           :on-click #(do (reset! open false) (when (not= k view) (rf/dispatch [:ops/view k])))}
                     [:span.cs-tick {:aria-hidden true} (when (= k view) "✓")]
                     [:span.cs-menu-label label]
                     (when k [:span.cs-menu-note "read only"])])])
               (when (> (count servers) 1)
                 [:<>
                  [:div.cs-menu-head "Servers"]
                  (for [{:keys [name label]} servers
                        :let [inv (in-view name)
                              on (and inv (not (hidden name)))]]
                    ^{:key name}
                    [:button.cs-menu-item {:class (when on "on") :role "menuitemcheckbox" :aria-checked (boolean on)
                                           :disabled (or (not inv) (and on (= 1 (count ticked))))
                                           :on-click #(rf/dispatch [:ops/tick-server name])}
                     [:span.cs-tick.box {:aria-hidden true} (when on "✓")]
                     [:span.server-mark {:aria-hidden true} (str/upper-case (subs (str label) 0 1))]
                     [:span.cs-menu-label label]
                     (when-not inv [:span.cs-menu-note.warn (str "no " (cap view) " here")])])])]])])))))

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

(defn- token-form [{:keys [placeholder named? save]}]
  (let [value (r/atom "") nm (r/atom "") busy (r/atom false) error (r/atom nil)]
    (fn [{:keys [placeholder named? save]}]
      (let [ready (and (seq (str/trim @value)) (or (not named?) (seq (str/trim @nm))))]
        [:form.token-form {:class (when named? "named")
                           :on-submit (fn [e]
                                        (.preventDefault e)
                                        (when ready
                                          (reset! busy true)
                                          (save @value (str/trim @nm)
                                                (fn [err]
                                                  (reset! busy false)
                                                  (reset! error err)
                                                  (when-not err (reset! value "") (reset! nm ""))))))}
         (when named?
           [:input.token-name {:auto-complete "off" :spell-check false :placeholder "Whose (Paul)"
                               :value @nm :on-change #(reset! nm (.. % -target -value))}])
         [:input {:type "password" :auto-complete "off" :spell-check false :placeholder placeholder
                  :value @value :on-change #(reset! value (.. % -target -value))}]
         [:button {:disabled (or @busy (not ready))} (if @busy "Saving…" "Save")]
         (when @error [:p.token-error @error])]))))

(def github-token-url "https://github.com/settings/tokens/new?scopes=repo,read:org,workflow&description=Claudia")

(defn- server-settings [server]
  (let [tokens @(rf/subscribe [:tokens server])
        github @(rf/subscribe [:github server])
        me @(rf/subscribe [:server-me server])
        in-use (get-in tokens [:inUse :name])
        put! (fn [method p body on-ok done]
               (rf/dispatch [:ops/put-now {:url (ops/api-url server p) :method method :body body
                                           :on-done (fn [ok err] (when ok (on-ok ok)) (when done (done err)))}]))]
    [:<>
     [:p.muted.small (when-let [e (:email @(rf/subscribe [:me]))] (str "Signed in as " e " · ")) "profile " [:b (:profile me)]]
     [:section.st-section
      [:h3 "Claude subscription " [:span.muted.small "shared by every profile here"]]
      [:p.small
       (cond (nil? tokens) "Checking…"
             (nil? (:inUse tokens)) "None in use from Settings yet."
             (not in-use) (str "In use: a token without a name, set by " (get-in tokens [:inUse :by]) ".")
             :else [:<> "Switched to " [:b in-use] " by " (get-in tokens [:inUse :by]) ", " (when-text (get-in tokens [:inUse :at])) "."])
       " A switch applies to every chat on its next message."]
      (when (seq (:tokens tokens))
        [:div.choice
         (for [t (:tokens tokens)]
           ^{:key (:name t)}
           [:label
            [:input {:type "radio" :name (str "claudeToken-" server) :checked (= (:name t) in-use)
                     :on-change #(put! "POST" "claude-token/use" {:name (:name t)}
                                       (fn [ok] (rf/dispatch [:ops/tokens server ok])) nil)}]
            (:name t) " " [:span.muted.small (str "saved by " (:by t) ", " (when-text (:at t)))]])])
      [:p.small "Add one, or replace one by its name: make it with " [:code "claude setup-token"] ". Saving switches to it."]
      [token-form {:named? true :placeholder "Claude token (sk-ant-…)"
                   :save (fn [token nm done]
                           (put! "PUT" "claude-token" {:name nm :token token}
                                 (fn [ok] (rf/dispatch [:ops/tokens server ok])) done))}]]
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
                                 (fn [ok] (rf/dispatch [:ops/github-user server ok])) done))}]]]))

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
        (for [{:keys [name label]} servers
              :let [up? (= :open (get-in all [name :status]))]]
          ^{:key name}
          [:button {:role "tab" :aria-selected (= name server) :class (when (= name server) "on") :disabled (not up?)
                    :title (if up? (str label "'s settings") (str label " is off"))
                    :on-click #(rf/dispatch [:ops/load-settings name])}
           label
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

(defn- settings-view [shown?]
  (let [servers @(rf/subscribe [:server-list])
        server @(rf/subscribe [:settings-server])
        ro @(rf/subscribe [:read-only?])
        {:keys [all-sessions?]} @(rf/subscribe [:list-mode])]
    ;; Escape goes back while shown, before the page's own keys see it (Escape there stops a chat).
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
     [shown?])
    [:div.st-pane {:role "dialog" :aria-label "Settings" :aria-hidden (not shown?) :inert (when-not shown? true)}
     [:header.st-head
      [:button.st-back {:on-click back!}
       [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M12.5 4L6.5 10l6 6"}]] "Chats"]
      [:h2 "Settings"]]
     [:div.st-scroll
      [brand server]
      [:f> usage/plan-meters server]
      [:section.st-group {:aria-label "Chats"}
       [:button.st-row {:on-click #(rf/dispatch [:ops/archived true])}
        [archive-icon]
        [:span.st-label "Archived chats"]
        chev]
       [switch-row all-sessions? #(rf/dispatch [:ops/all-sessions %]) "Show all ~/Work sessions"]]
      [:section.st-group {:aria-label "Alerts"}
       [alerts/toggles]]
      [:section.st-group {:aria-label "Drawing"}
       [:button.st-row {:on-click #(rf/dispatch [:gallery/open true])}
        [:svg.st-icon {:viewBox "0 0 20 20" :aria-hidden true}
         [:rect {:x 3 :y 3 :width 6 :height 6 :rx 1.5}] [:circle {:cx 14 :cy 6 :r 3}] [:path {:d "M3 15h6M11 12.5l3 3 3-3"}]]
        [:span.st-label "Forms " [:span.st-note "everything Claude can draw, and your templates"]]
        chev]]
      (when (:admin @(rf/subscribe [:me]))
        [:section.st-group {:aria-label "Hub"}
         [:button.st-row {:on-click #(rf/dispatch [:admin/open :servers])}
          [:span.st-label "Servers " [:span.st-note "the servers this hub reaches"]]]
         [:button.st-row {:on-click #(rf/dispatch [:admin/open :access])}
          [:span.st-label "Access " [:span.st-note "who may use which server"]]]])
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

(defn github-prompt []
  (let [value (r/atom "") busy (r/atom false) error (r/atom nil) done (r/atom nil)]
    (fn []
      (when-let [{:keys [server profile]} @(rf/subscribe [:github-prompt])]
        (let [close #(rf/dispatch [:ops/github-prompt nil])]
          [:div.settings-backdrop
           [:div.settings.github-prompt {:role "dialog" :aria-label "Connect GitHub"}
            [:header [:h2 "Connect GitHub"]]
            (if @done
              [:<>
               [:p "Done: Claude now pushes and opens pull requests as " [:b (str "@" @done)] "."]
               [:div.prompt-actions [:button.primary {:on-click close} "Start"]]]
              [:<>
               [:p "So Claude can push your branches and open pull requests as you, it needs a GitHub token of yours. It stays in your profile only."]
               [:ol.prompt-steps
                [:li [:a {:href github-token-url :target "_blank" :rel "noreferrer"} "Create a token on GitHub"]
                 ": the boxes are already ticked; pick an expiry, then " [:b "Generate token"] "."]
                [:li "Copy it and paste it here."]]
               [:form.token-form
                {:on-submit (fn [e]
                              (.preventDefault e)
                              (reset! busy true)
                              (rf/dispatch [:ops/put-now {:url (ops/api-url server "github") :method "PUT" :body {:token @value}
                                                          :on-done (fn [ok err]
                                                                     (reset! busy false)
                                                                     (reset! error err)
                                                                     (when ok
                                                                       (rf/dispatch [:ops/github-user server ok])
                                                                       (reset! done (get-in ok [:user :login]))))}]))}
                [:input {:type "password" :auto-complete "off" :spell-check false :placeholder "ghp_…" :auto-focus true
                         :value @value :on-change #(reset! value (.. % -target -value))}]
                [:button {:disabled (or @busy (str/blank? @value))} (if @busy "Saving…" "Save")]
                (when @error [:p.token-error @error])]
               [:div.prompt-actions
                [:button {:on-click #(do (js/localStorage.setItem (ops/github-later-key server profile) "1") (close))} "Later"]
                [:span.muted.small "You can add it any time in Settings."]]])]])))))
