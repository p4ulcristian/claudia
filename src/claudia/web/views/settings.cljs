(ns claudia.web.views.settings
  "The list's title menu (whose chats, which servers) and Settings, a popup (README decision 58)
  opened from the settings menu (claudia.web.views.menu: its plan card and More settings) and the
  thumb edge's cog: centred, a sheet from the bottom on a phone. It holds only the hub's Claude
  tokens (save, remove; no default, README decision 57), the person's GitHub and, with a key broker,
  their passkeys (README decision 66), plus a one-line Admin link for admins (People and Servers have no other way in)."
  (:require [clojure.string :as str]
            [claudia.web.format :as format]
            [claudia.web.views.passkeys :as passkeys]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
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

(defn- title-menu-box [open text {:keys [servers] :as m}]
  [:f> title-box open
   [:button.cs-title {:aria-haspopup "menu" :aria-expanded @open :on-click #(swap! open not)}
    text
    [:svg.cs-caret {:viewBox "0 0 12 12" :aria-hidden true} [:path {:d "M3 4.5l3 3 3-3"}]]]
   (when @open
     [:<>
      [:div.cs-menu {:role "menu"}
       (when (> (count servers) 1) (servers-menu m))]])])

(defn title-menu []
  (let [open (r/atom false)]
    (fn []
      (let [view @(rf/subscribe [:view])
            ;; Another's chats: also the servers you have no profile on.
            servers @(rf/subscribe [(if view :all-server-list :server-list)])
            hidden @(rf/subscribe [:hidden-servers])
            mes @(rf/subscribe [:server-mes])
            {:keys [archived?]} @(rf/subscribe [:list-mode])
            {:keys [usable ticked]} (pure/servers-in-view servers view mes hidden)
            hint (pure/servers-hint usable ticked)
            text [:<> [:span.cs-title-t (pure/list-title archived? view)] (when hint [:span.cs-title-hint (str "· " hint)])]]
        (if-not (> (count servers) 1)
          [:h2.cs-title text]
          (title-menu-box open text {:view view :servers servers :usable usable :ticked ticked}))))))

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

(defn- app-connection [{:keys [user]}]
  (if user
    [:p.small "Connected as " [:b (str "@" (:login user))] ": pushes and pull requests come from you. "
     [:button.linkish {:on-click #(rf/dispatch [:ops/github-disconnect])} "Disconnect"]]
    [:p.small "Not connected: Claude can't push or open pull requests for you. "
     [:a.st-cta {:href "/hub/github/connect"} "Connect GitHub"]]))

(defn- app-installs [{:keys [user installations app]}]
  (when user
    [:div.small.gh-installs
     (for [{:keys [id account selection configureUrl] kind :type} installations]
       ^{:key id}
       [:p (str (if (= kind "Organization") "Org " "") account ": ")
        (if (= selection "all") "all repositories" "chosen repositories") " · "
        [:a {:href configureUrl :target "_blank" :rel "noreferrer"} "Choose repos \u2197"]])
     [:p [:a {:href (:installUrl app) :target "_blank" :rel "noreferrer"} "Install on another account or org \u2197"]]]))

(defn- app-servers [{:keys [user servers]}]
  (when (and user (seq servers))
    [:p.small.token-servers
     (for [{server-name :name :keys [label ok]} servers]
       ^{:key server-name}
       [:span {:class (if ok "on" "off") :title (str label (if ok ": has your current GitHub token" ": not given it yet"))}
        label " " (if ok "\u2713" "\u2026")])]))

(defn- app-section
  "GitHub through the hub's GitHub App (README decision 37): Connect once; the hub keeps the
  long-lived token and gives every server a short one."
  [ga]
  [:section.st-section
   [:h3 "GitHub " [:span.muted.small "yours, on every server"]]
   [app-connection ga]
   [app-installs ga]
   [app-servers ga]])

(declare token-github-section)

(defn- github-section
  "The person's GitHub: through the hub's GitHub App when the hub has one, else the login kept by
  the hub and handed to their profile on every server (README decision 23)."
  []
  (if-let [ga (when (:app @(rf/subscribe [:github-app])) @(rf/subscribe [:github-app]))]
    [app-section ga]
    [token-github-section]))

(defn- token-github-section
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

(rf/reg-event-fx :ops/put-now (fn [_ [_ m]] {:ops/put m}))

(defn- remove-token! [token-name]
  (when (js/confirm (str "Remove " token-name "'s token from the hub?"))
    (rf/dispatch [:ops/put-now {:url (str "/hub/claude-tokens/" (js/encodeURIComponent token-name))
                                :method "DELETE"
                                :on-done (fn [ok _] (when ok (rf/dispatch [:ops/tokens ok])))}])))

(defn- token-row [t]
  [:div.st-token
   [:span.st-token-name (:name t)]
   [:span.muted.small (str "saved by " (:by t) ", " (format/when-text (:at t)))]
   [:button.token-remove {:type "button" :title (str "Remove " (:name t) "'s token from the hub")
                          :on-click #(remove-token! (:name t))}
    "Remove"]])

(defn- save-token-form []
  [token-form {:named? true :placeholder "Claude token (sk-ant-…)"
               :save (fn [token nm done]
                       (rf/dispatch [:ops/put-now {:url "/hub/claude-token" :method "PUT" :body {:name nm :token token}
                                                   :on-done (fn [ok err] (when ok (rf/dispatch [:ops/tokens ok])) (done err))}]))}])

(defn- hub-tokens [tokens]
  [:<>
   [:p.small (if (seq (:tokens tokens)) "Saved on the hub, on every server." "None saved yet.")
    " Each chat's is picked in its header; a new chat runs on the one you picked last."]
   (when (seq (:tokens tokens))
     [:div.st-tokens
      (for [t (:tokens tokens)] ^{:key (:name t)} [token-row t])])
   [:p.small "Add one, or replace one by its name: make it with " [:code "claude setup-token"] "."]
   [save-token-form]])

(defn- claude-subscription
  "The hub's Claude tokens (README decision 57): the saved ones, add and remove; no default."
  []
  (let [tokens @(rf/subscribe [:tokens])]
    [:section.st-section.st-claude
     [:h3 "Claude subscriptions " [:span.muted.small "kept by the hub"]]
     (cond
       (nil? tokens) [:p.small "Checking…"]
       (not (:on tokens)) [:p.small "This hub keeps no tokens (no key set): each server keeps the token it has."]
       :else (hub-tokens tokens))]))

(defn use-escape-back!
  "Escape calls go-back! while shown, before the page's own keys see it (Escape there stops a chat);
  the chat column's archived chats go back with it."
  [shown? go-back!]
  (h/use-effect
   (fn []
     (when shown?
       (let [esc (fn [^js e]
                   (when (and (= "Escape" (.-key e)) (not (some-> (.-target e) (.closest "input, textarea"))))
                     (.preventDefault e)
                     (.stopPropagation e)
                     (go-back!)))]
         (js/window.addEventListener "keydown" esc true)
         #(js/window.removeEventListener "keydown" esc true))))
   [shown? go-back!]))

(defn- close! [] (rf/dispatch [:ops/settings false]))

(defn- use-escape-close!
  "Escape closes Settings, from its fields too, before the page's own keys see it."
  []
  (h/use-effect
   (fn []
     (let [esc (fn [^js e]
                 (when (= "Escape" (.-key e))
                   (.preventDefault e)
                   (.stopPropagation e)
                   (close!)))]
       (js/window.addEventListener "keydown" esc true)
       #(js/window.removeEventListener "keydown" esc true)))
   []))

(defn- admin-link
  "The way into People and Servers, for admins only."
  []
  (when (:admin @(rf/subscribe [:me]))
    [:button.st-admin {:type "button" :on-click #(do (close!) (rf/dispatch [:admin/open :people]))}
     "Admin: people and servers"]))

(defn- settings-body []
  (if @(rf/subscribe [:viewing-other?])
    [:p.small "Settings are your own: switch to My chats to change them."]
    [:<> [claude-subscription] [github-section] [passkeys/passkeys-section]]))

(defn- settings-card
  "Settings as a popup (README decision 58): centred, a sheet from the bottom on a phone; Escape, a
  click outside or the close button closes it."
  []
  (use-escape-close!)
  [:div.st-backdrop {:on-pointer-down #(when (= (.-target %) (.-currentTarget %)) (close!))}
   [:div.st-pop {:role "dialog" :aria-modal true :aria-label "Settings"}
    [:header.st-head
     [:h2 "Settings"]
     [:button.st-close {:type "button" :aria-label "Close" :on-click close!} "×"]]
    [:div.st-scroll
     [settings-body]
     [admin-link]]]])

(defn- settings-loaded []
  (let [server @(rf/subscribe [:settings-server])]
    (r/with-let [_ (rf/dispatch [:ops/load-settings server])]
      [:f> settings-card])))

(defn settings-popup
  "Settings (README decision 58): the Claude subscriptions, GitHub and passkeys, over the whole window while
  open (the menu's plan card and More settings, the thumb edge's cog)."
  []
  (when (:settings? @(rf/subscribe [:list-mode]))
    [settings-loaded]))
