(ns claudia.web.views.settings
  "The list's title menu (whose chats, which servers), the Settings screen behind the gear (archived
  chats, all ~/Work sessions, the instance chip, the plan, each server's Claude tokens and GitHub
  login) and the GitHub prompt for a profile without a login."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.web.chatops :as ops]
            [claudia.web.views.usage :as usage]))

(defn- cap [n] (str (str/upper-case (subs n 0 1)) (subs n 1)))

(defn- when-text [at]
  (when at (.toLocaleString (js/Date. at) js/undefined #js {:dateStyle "medium" :timeStyle "short"})))

;; ---- the title menu ----

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
          [:div.cs-title-box
           [:button.cs-title {:aria-haspopup "menu" :on-click #(swap! open not)}
            text
            [:svg.cs-caret {:viewBox "0 0 12 12"} [:path {:d "M3 4.5l3 3 3-3"}]]]
           (when @open
             [:<>
              [:div.menu-away {:on-click #(reset! open false)}]
              [:div.cs-menu {:role "menu"}
               (when (seq people)
                 [:<>
                  [:div.cs-menu-head "Whose chats"]
                  (for [[k label] (cons [nil "My chats"] (map (fn [p] [p (str (cap p) "'s chats")]) people))]
                    ^{:key (str k)}
                    [:button.cs-menu-item {:class (when (= k view) "on")
                                           :on-click #(do (reset! open false) (when (not= k view) (rf/dispatch [:ops/view k])))}
                     [:span.cs-tick (when (= k view) "✓")]
                     [:span.cs-menu-label label]
                     (when k [:span.cs-menu-note "read only"])])])
               (when (> (count servers) 1)
                 [:<>
                  [:div.cs-menu-head "Servers"]
                  (for [{:keys [name label]} servers
                        :let [inv (in-view name)
                              on (and inv (not (hidden name)))]]
                    ^{:key name}
                    [:button.cs-menu-item {:class (when on "on")
                                           :disabled (or (not inv) (and on (= 1 (count ticked))))
                                           :on-click #(rf/dispatch [:ops/tick-server name])}
                     [:span.cs-tick.box (when on "✓")]
                     [:span.server-mark (subs (str label) 0 1)]
                     [:span.cs-menu-label label]
                     (when-not inv [:span.cs-menu-note.warn (str "no " (cap view) " here")])])])]])])))))

(defn lock-icon []
  [:span.cs-ro {:title "Read only"}
   [:svg {:viewBox "0 0 16 16"} [:rect {:x 3.5 :y 7 :width 9 :height 6.5 :rx 1.5}] [:path {:d "M5.5 7V5.2a2.5 2.5 0 0 1 5 0V7"}]]])

(defn gear-button []
  [:button.cs-icon.cs-gear {:title "Settings" :on-click #(rf/dispatch [:ops/settings true])}
   [:svg {:viewBox "0 0 20 20"}
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
     [:p.muted.small "profile " [:b (:profile me)] (when (:server me) (str " on " (:server me)))]
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
     [:span.wordmark "Claudia"]
     (when me
       [:span.instance {:title (str "Claudia " (:version me) (when (:commit me) (str " (" (:commit me) ")"))
                                    (when (:server me) (str " on " (:server me)))
                                    (when token (str ", on " token "'s Claude subscription")))}
        (when (:server me) [:span.instance-name (:server me)])
        (when token [:span.instance-token token])
        [:span.instance-version (or (:commit me) (str "v" (:version me)))]])]))

(defn plan-bars
  "The plan's limits as bars with when each resets; until a server reports them, a line that says so."
  [server]
  (let [tokens @(rf/subscribe [:tokens server])
        token (get-in tokens [:inUse :name])]
    [:section.st-section.st-plan
     [:h3 (if token (str token "'s plan") "Plan")]
     [:f> usage/plan-meters server]]))

(defn- switch-row [on on-change label note]
  [:label.st-row.st-switch
   [:span.st-label label (when note [:span.st-note note])]
   [:input {:type "checkbox" :role "switch" :checked (boolean on) :on-change #(on-change (.. % -target -checked))}]
   [:span.st-knob]])

(defn settings-pane []
  (let [servers @(rf/subscribe [:server-list])
        server @(rf/subscribe [:settings-server])
        ro @(rf/subscribe [:read-only?])
        {:keys [all-sessions?]} @(rf/subscribe [:list-mode])]
    (r/with-let [_ (rf/dispatch [:ops/load-settings server])]
      [:div.st-pane {:role "dialog" :aria-label "Settings"}
       [:header.st-head
        [:button.st-back {:on-click #(rf/dispatch [:ops/settings false])}
         [:svg {:viewBox "0 0 20 20"} [:path {:d "M12.5 4L6.5 10l6 6"}]] "Chats"]
        [:h2 "Settings"]]
       [:div.st-scroll
        [brand server]
        [plan-bars server]
        [:section.st-group
         [:button.st-row {:on-click #(rf/dispatch [:ops/archived true])}
          [:span.st-label "Archived chats"]
          [:svg.st-chev {:viewBox "0 0 20 20"} [:path {:d "M7.5 4l6 6-6 6"}]]]
         [switch-row all-sessions? #(rf/dispatch [:ops/all-sessions %]) "Show all ~/Work sessions"
          "Claude Code sessions that are not chats here; one opens as a chat"]]
        (when (:admin @(rf/subscribe [:me]))
          [:section.st-group.st-hub {:aria-label "Hub"}
           [:button.st-row {:on-click #(rf/dispatch [:admin/open :servers])}
            [:span.st-label "Servers" [:span.st-note "the servers this hub reaches"]]]
           [:button.st-row {:on-click #(rf/dispatch [:admin/open :access])}
            [:span.st-label "Access" [:span.st-note "who may use which server"]]]])
        (when-not ro
          [:div.st-server
           (when (> (count servers) 1)
             [:div.st-pick
              (for [{:keys [name label]} servers]
                ^{:key name}
                [:button {:class (when (= name server) "on") :on-click #(rf/dispatch [:ops/load-settings name])} label])])
           ^{:key server} [server-settings server]])]])))

;; ---- the GitHub prompt ----

(defn github-prompt []
  (let [value (r/atom "") busy (r/atom false) error (r/atom nil) done (r/atom nil)]
    (fn []
      (when-let [{:keys [server profile]} @(rf/subscribe [:github-prompt])]
        (let [close #(rf/dispatch [:ops/github-prompt nil])]
          [:div.settings-backdrop
           [:div.github-prompt {:role "dialog" :aria-label "Connect GitHub"}
            [:h2 "Connect GitHub"]
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
