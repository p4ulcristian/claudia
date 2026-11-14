(ns claudia.web.views.settings
  "The list's title menu (whose chats, which servers) and the Settings screen, opened from the
  settings menu (claudia.web.views.menu: its plan card and More settings): the instance chip, the
  hub's Claude tokens, the person's GitHub login (kept by the hub, on every server), all ~/Work
  sessions, Servers and Access for admins, and each server's version and profile."
  (:require [clojure.string :as str]
            [claudia.web.format :as format]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.logo :as logo]
            [claudia.web.views.pure.settings :as pure]
            [claudia.web.views.pure.words :as words]))

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

(defn- github-servers
  "Each of the person's servers: logged in as the kept login, as another, or not reached."
  [{:keys [login servers]}]
  (when (seq servers)
    [:p.small.token-servers
     (for [{server-name :name :keys [label ok] :as s} servers]
       ^{:key server-name}
       [:span {:class (cond (not ok) "off" (= login (:login s)) "on" :else "other")
               :title (cond (not ok) (str label ": " (or (:error s) "not reached"))
                            :else (str label ": gh logged in as @" (:login s)))}
        label " " (cond (not ok) "off" (= login (:login s)) "✓" :else "…")])]))

(defn- save-github! [token _ done]
  (rf/dispatch [:ops/put-now {:url "/hub/github-login" :method "PUT" :body {:token token}
                              :on-done (fn [ok err] (when ok (rf/dispatch [:ops/github-login ok])) (done err))}]))

(defn- github-section
  "The person's GitHub login, kept by the hub and handed to their profile on every server
  (README decision 23)."
  []
  (let [{:keys [on login] :as g} @(rf/subscribe [:github-login])]
    [:section.st-section
     [:h3 "GitHub " [:span.muted.small "yours, on every server"]]
     (cond
       (nil? g) [:p.small "Checking…"]
       (not on) [:p.small "This hub keeps no tokens (no key set)."]
       :else [:<>
              [:p.small (if login
                          [:<> "Pushes and pull requests come from " [:b (str "@" login)] "."]
                          "Not connected: Claude can't push or open pull requests for you.")
               " " [:a {:href github-token-url :target "_blank" :rel "noreferrer"} "Create a token on GitHub"]
               " (the boxes come ticked) and paste it here."]
              [github-servers g]
              [token-form {:placeholder (if login "Replace with a new token" "GitHub token (ghp_…)") :save save-github!}]])]))

(defn- server-settings [server]
  (let [me @(rf/subscribe [:server-me server])]
    [:p.muted.small (when-let [e (:email @(rf/subscribe [:me]))] (str "Signed in as " e " · ")) "profile " [:b (:profile me)]]))

(rf/reg-event-fx :ops/put-now (fn [_ [_ m]] {:ops/put m}))

(defn switch-token!
  "The saved token called n becomes the default on every server (the hub writes it into each one's
  token file): what a new chat starts on and a chat without a pick runs on."
  [n]
  (rf/dispatch [:ops/put-now {:url "/hub/claude-token/use" :method "POST" :body {:name n}
                              :on-done (fn [ok _] (when ok (rf/dispatch [:ops/tokens ok])))}]))

(defn- server-sync
  "Each of the person's servers: on the token in use, on another, or not reached."
  [{:keys [inUse servers]}]
  (when (seq servers)
    [:p.small.token-servers
     (for [s servers
           :let [state (pure/sync-state s (:name inUse))]]
       ^{:key (:name s)}
       [:span {:class (name state) :title (pure/sync-title s state)}
        (:label s) " " (case state :off "off" :on "✓" "…")])]))

(defn- remove-token! [token-name]
  (when (js/confirm (str "Remove " token-name "'s token from the hub?"))
    (rf/dispatch [:ops/put-now {:url (str "/hub/claude-tokens/" (js/encodeURIComponent token-name))
                                :method "DELETE"
                                :on-done (fn [ok _] (when ok (rf/dispatch [:ops/tokens ok])))}])))

(defn- token-row [t in-use]
  [:label
   [:input {:type "radio" :name "claudeToken" :checked (= (:name t) in-use)
            :on-change #(switch-token! (:name t))}]
   (:name t) " " [:span.muted.small (str "saved by " (:by t) ", " (format/when-text (:at t)))]
   (when-not (= (:name t) in-use)
     [:button.token-remove {:type "button" :title (str "Remove " (:name t) "'s token from the hub")
                            :on-click (fn [e] (.preventDefault e) (remove-token! (:name t)))}
      "Remove"])])

(defn- in-use-note [tokens in-use]
  [:p.small
   (if in-use
     [:<> "Default: " [:b in-use] ", set by " (get-in tokens [:inUse :by]) ", " (format/when-text (get-in tokens [:inUse :at])) "."]
     "None saved yet.")
   " A new chat starts on the default and stays on it; each chat's own is picked in its header."])

(defn- save-token-form []
  [token-form {:named? true :placeholder "Claude token (sk-ant-…)"
               :save (fn [token nm done]
                       (rf/dispatch [:ops/put-now {:url "/hub/claude-token" :method "PUT" :body {:name nm :token token}
                                                   :on-done (fn [ok err] (when ok (rf/dispatch [:ops/tokens ok])) (done err))}]))}])

(defn- hub-tokens [tokens]
  (let [in-use (get-in tokens [:inUse :name])]
    [:<>
     (in-use-note tokens in-use)
     [server-sync tokens]
     (when (seq (:tokens tokens))
       [:div.choice
        (for [t (:tokens tokens)] ^{:key (:name t)} [token-row t in-use])])
     [:p.small "Add one, or replace one by its name: make it with " [:code "claude setup-token"] ". Saving makes it the default."]
     [save-token-form]]))

(defn- claude-subscription
  "The hub's Claude tokens: one in use on every server; save, switch, remove."
  []
  (let [tokens @(rf/subscribe [:tokens])]
    [:section.st-section.st-claude
     [:h3 "Claude subscription " [:span.muted.small "kept by the hub, the same on every server"]]
     (cond
       (nil? tokens) [:p.small "Checking…"]
       (not (:on tokens)) [:p.small "This hub keeps no tokens (no key set): each server keeps the token it has."]
       :else (hub-tokens tokens))]))

(defn- brand [server]
  (let [me @(rf/subscribe [:server-me server])
        token (get-in @(rf/subscribe [:tokens]) [:inUse :name])]
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
   [switch-row all-sessions? #(rf/dispatch [:ops/all-sessions %]) "Show all ~/Work sessions"]])

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
      (when-not ro [claude-subscription])
      (when-not ro [github-section])
      (chats-group all-sessions?)
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
