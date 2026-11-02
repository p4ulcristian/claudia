(ns claudia.web.views.chat
  "The chat column: header, messages (Markdown, one-line tool steps, streamed text), asks, composer."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.md :as md]
            [claudia.web.views.asks :as asks]))

(defn- icon [d]
  [:svg.ic {:viewBox "0 0 24 24" :aria-hidden true} [:path {:d d}]])

(def ^:private back-icon "M15 5l-7 7 7 7")
(def ^:private picture-icon "M4 5h16v14H4z M4 15l5-5 4 4 3-3 4 4 M15.5 9.5h.01")

(defn server-mark [server]
  (let [label @(rf/subscribe [:server-label server])
        status @(rf/subscribe [:server-status server])]
    [:span.server-mark {:class (when (not= :open status) "off") :title label}
     (subs (str label) 0 1)]))

;; ---- header ----

(defn- header [{:keys [ck server chat pending? row state status]}]
  (let [menu? (r/atom false)
        editing (r/atom nil)]
    (fn [{:keys [ck server chat pending? row state status]}]
      (let [title (or (:title row) (:title state) (when pending? "New chat") "Chat")
            multi? @(rf/subscribe [:multi-server?])
            waiting @(rf/subscribe [:waiting-count])
            commit! (fn []
                      (when-let [t @editing]
                        (when (and chat (not= t title)) (rf/dispatch [:chat/rename ck t])))
                      (reset! editing nil))]
        [:header.chat-head
         [:button.back-btn.phone-only {:on-click #(rf/dispatch [:nav/list])}
          (icon back-icon) "Chats"
          (when (pos? waiting) [:span.count.waiting waiting])]
         (if (some? @editing)
           [:input.title-edit {:value @editing :auto-focus true
                               :on-change #(reset! editing (.. % -target -value))
                               :on-blur commit!
                               :on-key-down #(case (.-key %)
                                               "Enter" (commit!)
                                               "Escape" (reset! editing nil)
                                               nil)}]
           [:h1 {:title title} title])
         (when (and multi? server) [server-mark server])
         (when (and server (not= :open status)) [:span.offline "offline"])
         (when (and chat (not pending?))
           [:div.menu-box
            [:button.icon {:title "Chat menu" :on-click #(swap! menu? not)} "⋯"]
            (when @menu?
              [:<>
               [:div.menu-away {:on-click #(reset! menu? false)}]
               [:div.menu
                [:button {:on-click #(do (reset! menu? false) (reset! editing title))} "Rename"]
                [:button {:on-click #(do (reset! menu? false) (rf/dispatch [:chat/archive ck true]))} "Archive"]]])])
         [:button.icon.pic-open.phone-only {:title "Picture" :on-click #(rf/dispatch [:nav/screen :picture])}
          (icon picture-icon)]]))))

;; ---- messages ----

(defn- message [{:keys [role text tool summary error streaming]}]
  (case role
    "user" [:div.msg.user [:div.bubble text]]
    "tool" [:div.step {:class (when error "err")}
            [:span.step-dot]
            [:b (or tool "tool")]
            [:span.step-title (or summary "")]]
    "note" [:div.msg.note text]
    [:div.msg.claude {:class (when streaming "streaming")}
     [:div.md {:dangerouslySetInnerHTML (r/unsafe-html (md/html text))}]]))

(defn- messages-view [{:keys [ck state outbox pending?]}]
  (let [box (h/use-ref nil)
        stuck (h/use-ref true)
        msgs (:messages state)
        running (:running state)
        last-len (count (:text (peek msgs)))]
    ;; Follow the bottom while the person is there; leave them be once they scrolled up.
    (h/use-layout-effect
     (fn []
       (when-let [el (.-current box)]
         (when (.-current stuck) (set! (.-scrollTop el) (.-scrollHeight el))))
       nil)
     [ck (count msgs) last-len (count outbox) running (count (:asks state))])
    (h/use-effect (fn [] (set! (.-current stuck) true) nil) [ck])
    [:div.messages {:ref #(set! (.-current box) %)
                    :on-scroll (fn [e]
                                 (let [el (.-target e)]
                                   (set! (.-current stuck)
                                         (< (- (.-scrollHeight el) (.-scrollTop el) (.-clientHeight el)) 40))))}
     (when (and (not pending?) (not (:loaded state)))
       [:div.loading.muted "Loading…"])
     (for [m msgs] ^{:key (:id m)} [message m])
     (for [[i t] (map-indexed vector outbox)]
       ^{:key (str "out" i)} [:div.msg.user.queued [:div.bubble t] [:span.queued-tag "sending"]])
     (when (or running pending?)
       [:div.working [:span.pulse] (if pending? "Starting…" "Working…")])
     (when-let [err (:error state)]
       [:div.error-line {:on-click #(rf/dispatch [:error/dismiss ck])} err])
     [asks/asks-area ck (:asks state)]]))

;; ---- composer ----

(defn- composer [{:keys [draft-key running can-stop placeholder]}]
  (let [ref (h/use-ref nil)
        text @(rf/subscribe [:draft draft-key])
        send! #(rf/dispatch [:composer/send text])]
    (h/use-layout-effect
     (fn []
       (when-let [el (.-current ref)]
         (set! (.. el -style -height) "auto")
         (set! (.. el -style -height) (str (min 260 (.-scrollHeight el)) "px")))
       nil)
     [text])
    [:div.composer
     [:div.input-row
      [:textarea {:ref #(set! (.-current ref) %)
                  :rows 1
                  :value text
                  :placeholder placeholder
                  :on-change #(rf/dispatch-sync [:draft/set draft-key (.. % -target -value)])
                  :on-key-down (fn [e]
                                 (when (and (= "Enter" (.-key e)) (not (.-shiftKey e))
                                            (not (.. e -nativeEvent -isComposing)))
                                   (.preventDefault e)
                                   (send!)))}]
      [:div.toolbar
       [:span.grow]
       (when (and running can-stop)
         [:button.stop {:on-click #(rf/dispatch [:chat/stop])} "Stop"])
       [:button.primary {:disabled (str/blank? text) :on-click send!} "Send"]]]]))

;; ---- the new chat ----

(defn- new-chat []
  (let [servers @(rf/subscribe [:server-list])
        current @(rf/subscribe [:new-server])]
    [:div.messages.hello-box
     [:div.hello
      [:h2 "New chat"]
      [:p.muted "Say what you're after; Claude answers short and draws the rest."]
      (when (> (count servers) 1)
        [:div.new-on
         [:span.muted "on"]
         (for [{:keys [name label]} servers]
           ^{:key name}
           [:button {:class (when (= name current) "on") :on-click #(rf/dispatch [:new/server name])}
            [server-mark name] (or label name)])])]]))

(defn chat-pane []
  (let [oc @(rf/subscribe [:open-chat])
        me @(rf/subscribe [:me])
        me-error @(rf/subscribe [:me-error])
        error @(rf/subscribe [:error])]
    [:section.left
     (if oc
       ^{:key (str (:ck oc))} [header oc]
       [:header.chat-head
        [:button.back-btn.phone-only {:on-click #(rf/dispatch [:nav/list])} (icon back-icon) "Chats"]
        [:h1 "New chat"]])
     (when error [:div.error-line.global {:on-click #(rf/dispatch [:error/dismiss nil])} error])
     (cond
       me-error [:div.messages [:div.hello [:h2 "Can't reach Claudia"] [:p.muted me-error]]]
       (nil? me) [:div.messages [:div.hello.muted "Loading…"]]
       (empty? (:servers me)) [:div.messages [:div.hello [:h2 "No servers"] [:p.muted "You have no server to chat on yet."]]]
       oc [:f> messages-view oc]
       :else [new-chat])
     (when (and me (seq (:servers me)))
       [:f> composer {:draft-key (if oc (:ck oc) :new)
                      :running (get-in oc [:state :running])
                      :can-stop (and oc (not (:pending? oc)))
                      :placeholder (if oc "Reply…" "Start a chat…")}])]))
