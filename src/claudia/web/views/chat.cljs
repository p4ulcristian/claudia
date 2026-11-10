(ns claudia.web.views.chat
  "The chat column (web/app.tsx and web/chat.tsx of the TypeScript Claudia): the header (colour dot,
  the title that renames itself, server and folder, the changes pill), the messages (Markdown, tool
  steps folded into groups, streamed text, the working line) and the composer."
  (:require [clojure.string :as str]
            [claudia.web.format :as format]
            [claudia.web.hooks :as hooks]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.shared.files :as files]
            [claudia.web.md :as md]
            [claudia.web.views.agents :as agents]
            [claudia.web.views.attach :as attach]
            [claudia.web.views.circle :as circle]
            [claudia.web.views.changes :as changes]
            [claudia.web.views.codesend :as codesend]
            [claudia.web.views.pure.chat :as pure]
            [claudia.web.views.pure.words :as words]
            [claudia.web.views.steps :as steps]
            [claudia.web.views.usage :as usage]))

(def ^:private colours words/colours-with-ash)

(defn server-mark [server]
  (let [label @(rf/subscribe [:server-label server])
        status @(rf/subscribe [:server-status server])]
    [:span.server-mark {:class (when (= :closed status) "off") :title (if (= :closed status) (str label " is off") label)
                        :aria-label label}
     (words/initial label)]))

;; ---- the picture of a turn, from its user message ----

(rf/reg-sub :viewing-msg (fn [db [_ k]] (get-in db [:viewing-msg k])))

(rf/reg-event-fx
 :chat/view-turn
 ;; The picture (and changes) of the turn a user message started, or back to live (nil).
 (fn [{:keys [db]} [_ k msg-id]]
   (let [idx (when msg-id (pure/turn-picture (get-in db [:pictures k]) (get-in db [:chats k :messages]) msg-id))]
     (if (nil? idx)
       {:db (-> db (update :viewing dissoc k) (update :viewing-msg dissoc k))}
       {:db (assoc-in db [:viewing-msg k] msg-id)
        :fx [[:dispatch [:picture/pick idx]]]}))))

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

(defn- colour-picker
  "A chat's colour: a dot in the header opens the palette."
  [ck color]
  (let [[open set-open] (h/use-state false)
        box (h/use-ref nil)
        pick (fn [c] (rf/dispatch [:ops/color ck c]) (set-open false))]
    (use-closes-away! box open #(set-open false))
    [:div.color-pick {:ref #(set! (.-current box) %)}
     [:button.color-dot {:class (when-not color "none")
                         :style {:background (when (some #{color} colours) (str "var(--c-" color ")"))}
                         :on-click #(set-open not) :title "Chat colour" :aria-haspopup "true" :aria-expanded open}]
     (when open (palette color pick))]))

(def ^:private back-icon [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M12.5 4L6.5 10l6 6"}]])

(defn- back-to-list []
  (let [rows @(rf/subscribe [:rows])
        open @(rf/subscribe [:open])
        ro @(rf/subscribe [:read-only?])
        {:keys [waiting done]} (pure/others-count rows open ro)]
    [:button.back-to-list {:on-click #(rf/dispatch [:nav/list]) :aria-label "Chats"}
     back-icon "Chats"
     (when (pos? (+ waiting done))
       [:span.count {:class (when (pos? waiting) "waiting")} (+ waiting done)])]))

(rf/reg-sub :pictures-of (fn [db [_ k]] (get-in db [:pictures k] [])))

;; ---- the header ----

(defn- title-edit [editing title rename!]
  [:input.title-edit {:default-value title :auto-focus true
                      :on-blur #(rename! (.. % -target -value))
                      :on-key-down #(case (.-key %)
                                      "Enter" (rename! (.. % -currentTarget -value))
                                      "Escape" (reset! editing false)
                                      nil)}])

(defn- title-line [editing title renamable?]
  [:h1 {:class (when renamable? "renamable")
        :title (when renamable? "Click to rename")
        :on-click #(when renamable? (reset! editing true))}
   [:span.t title]
   (when renamable? [:span.pencil {:aria-hidden true} "✎"])])

(defn- head-chips
  "After the title: the server, the working folder, the changes pill, whether the server is there."
  [{:keys [ck server chat pending? row state status]} label folder]
  [:<>
   (when (and @(rf/subscribe [:multi-server?]) server (or row (seq (:messages state))))
     [:span.folder-chip.server-chip {:title (str "On " label)} label])
   (when (seq folder)
     [:span.folder-chip {:title (str "Working folder: " folder)} (words/clip (format/tilde folder) 28)])
   (when (and chat (not pending?)) [changes/pill ck state folder])
   (cond (= :closed status) [:span.offline (str label " is off")]
         (not= :open status) [:span.offline "reconnecting…"])])

(defn- header [_oc]
  (let [editing (r/atom false)]
    (fn [{:keys [ck server chat row state] :as oc}]
      (let [title (or (:title row) (:title state) "New chat")
            ro @(rf/subscribe [:read-only?])
            label @(rf/subscribe [:server-label server])
            color (:color row)
            renamable? (and row (not ro))
            folder (or (:folder row) (:folder state))
            rename! (fn [typed]
                      (reset! editing false)
                      (when-let [t (and chat (pure/new-title typed title))] (rf/dispatch [:chat/rename ck t])))]
        [:header.chat-head {:class (when color "colored")}
         [back-to-list]
         (when-not ro [:f> colour-picker ck color])
         (if @editing (title-edit editing title rename!) (title-line editing title renamable?))
         (head-chips oc label folder)]))))

;; ---- messages ----

(defn- elapsed [since]
  (let [[_ tick] (h/use-state 0)]
    (h/use-effect (fn [] (let [t (js/setInterval #(tick inc) 1000)] #(js/clearInterval t))) [])
    (pure/elapsed-text since (js/Date.now))))

(defn- user-text
  "The user's message: code sent from an editor block as chips, the rest as Markdown; attached files
  as chips after it."
  [server chat text]
  (let [{t :text sent :files} (files/split-files text)
        sends (codesend/code-sends t)]
    [:<>
     (cond
       sends [codesend/bubble t]
       (seq t) [:div.md {:dangerouslySetInnerHTML (r/unsafe-html (md/html t))}])
     (when (seq sent) [attach/file-chips server chat sent])]))

(defn- msg-links [ck id {:keys [viewing? picture? files forkable?]}]
  [:div.msg-links
   (when forkable?
     [:button.pic-link.fork-link {:title "A new chat with this one up to the end of this turn, in the same folder"
                                  :on-click #(rf/dispatch [:ops/fork ck id])}
      "fork from here"])
   (when (or picture? (pos? files))
     [:button.pic-link {:title "Show the picture from this turn"
                        :on-click #(rf/dispatch [:chat/view-turn ck (when-not viewing? id)])}
      (if viewing? "back to live" (pure/turn-links picture? files))])])

(defn- user-msg [ck {:keys [id text images]} {:keys [viewing? picture? files forkable? queued?] :as marks}]
  (let [[server chat] ck]
    [:div.msg.user {:data-at id :class [(when viewing? "viewing") (when queued? "queued")]
                    :title (when queued? "Claude reads this at its next step")}
     [:div.bubble
      (when (and images (pos? images)) [attach/sent-images server chat id images])
      [user-text server chat text]]
     (when queued? [:span.queued-tag "queued"])
     (when (and (not queued?) (or picture? (pos? files) forkable?))
       (msg-links ck id marks))]))

(rf/reg-event-db :nav/jump-done (fn [db _] (dissoc db :jump-to)))

(rf/reg-event-fx
 :nav/jump
 ;; A search hit: its chat opens and scrolls to the message once it is on the page.
 (fn [{:keys [db]} [_ k msg-id]]
   {:db (assoc db :jump-to msg-id)
    :fx [[:dispatch [:nav/open k]]]}))

(defn- flash!
  "The found message comes into view and flashes (the class again restarts its animation)."
  [^js el]
  (.scrollIntoView el #js {:block "center" :behavior "smooth"})
  (.remove (.-classList el) "found-flash")
  (.-offsetWidth el)
  (.add (.-classList el) "found-flash"))

(defn- look-for!
  "One try at the message to jump to: older pages load while it is not on the page; found or out of
  tries, it stops (stop!) and flashes what it found."
  [msg-id ck more? tries stop!]
  (let [el (js/document.querySelector (str "[data-at=\"" (js/CSS.escape msg-id) "\"]"))]
    (when (and (not el) more?) (rf/dispatch [:ops/older ck]))
    (when (or el (> (swap! tries inc) (if more? 400 40)))
      (stop!)
      (rf/dispatch [:nav/jump-done])
      (when el (flash! el)))))

(defn- jump! [ck more?]
  (let [msg-id @(rf/subscribe [:jump-to])]
    (h/use-effect
     (fn []
       (when msg-id
         (let [tries (atom 0)
               iv (atom nil)]
           (reset! iv (js/setInterval #(look-for! msg-id ck more? tries (fn [] (js/clearInterval @iv))) 120))
           #(js/clearInterval @iv))))
     [msg-id (str ck)])))

(rf/reg-sub :jump-to (fn [db] (:jump-to db)))

(defn- to-bottom! [^js el] (set! (.-scrollTop el) (.-scrollHeight el)))

(defn- use-keep-place!
  "Older messages came in above: keep what was on screen where it was."
  [{:keys [box stuck anchor]} ck first-id]
  (h/use-layout-effect
   (fn []
     (when-let [el (.-current box)]
       (when-let [top (pure/kept-top (.-current anchor) ck first-id (.-current stuck) (.-scrollTop el) (.-scrollHeight el))]
         (set! (.-scrollTop el) top))
       (set! (.-current anchor) [ck first-id (.-scrollHeight el)]))
     nil)))

(defn- use-follow-bottom!
  "Follow the bottom while the person is there; leave them be once they scrolled up."
  [{:keys [box stuck]} deps]
  (h/use-layout-effect
   (fn []
     (when-let [el (.-current box)]
       (when (.-current stuck) (to-bottom! el)))
     nil)
   deps))

(defn- use-follow-on-resize!
  "A shorter column (a phone's keyboard, the message box growing) keeps its end in view."
  [{:keys [box stuck]}]
  (hooks/use-resize box #(when (.-current stuck) (to-bottom! %)) []))

(defn- use-load-older!
  "Near the top (or a first page shorter than the column): the next older page."
  [{:keys [box]} ck state msg-count]
  (h/use-effect
   (fn []
     (when-let [el (.-current box)]
       (when (pure/wants-older? state (.-scrollTop el))
         (rf/dispatch [:ops/older ck])))
     nil)
   [msg-count (:more state) (:loading-older state)]))

(defn- on-scroll
  "The column scrolled: where it was, maybe an older page, whether it is at the bottom."
  [{:keys [anchor stuck]} ck first-id state]
  (fn [e]
    (let [el (.-target e)]
      (set! (.-current anchor) [ck first-id (.-scrollHeight el)])
      (when (pure/wants-older? state (.-scrollTop el))
        (rf/dispatch [:ops/older ck]))
      (set! (.-current stuck) (pure/at-bottom? (.-scrollHeight el) (.-scrollTop el) (.-clientHeight el))))))

(def ^:private hello
  [:div.hello [:h2 "What are we working on?"] [:p.muted "Claude answers short here and explains on the right."]])

(defn- msg-row
  "One message of the feed: the user's, a note, or Claude's."
  [ck i {:keys [id role text streaming] :as m} marks]
  (case role
    "user" ^{:key id} [user-msg ck m (pure/user-marks m marks)]
    "note" ^{:key (str "note-" id "-" i)} [:pre.note-line text]
    ^{:key id} [:div.msg.claude {:data-at (first (str/split (str id) #":")) :class (when streaming "streaming")}
                [:div.md {:dangerouslySetInnerHTML (r/unsafe-html (md/html text))}]]))

(defn- feed-row
  "One block of the feed: a run of steps, a picture a step showed, or a message."
  [ck i b {:keys [running last-i] :as marks}]
  (case (:k b)
    :steps ^{:key (str "s" (:id b))} [steps/steps ck (:tools b) (and running (= i last-i))]
    :shown ^{:key (str "shown-" (:id (:tool b)))} [:div.msg.claude.shown
                                                   [(if (:video (:tool b)) attach/shown-video attach/shown-image)
                                                    (first ck) (second ck) (:id (:tool b))]]
    (msg-row ck i (:m b) marks)))

(defn- working-line [state since]
  [:div.working [:span.pulse] " "
   (pure/working-label state)
   " · " [:f> elapsed (or since (js/Date.now))]])

(defn- feed-tail
  "Below the feed: what is sent and not taken in yet, Stopped, the error."
  [ck state outbox queued running]
  [:<>
   (for [[i t] (map-indexed vector outbox)]
     ^{:key (str "out" i)} [user-msg ck {:id (str "out" i) :text t} {:queued? true}])
   (for [m queued] ^{:key (:id m)} [user-msg ck m {:queued? true}])
   (when (:stopped state) (when-not running [:div.muted.small "Stopped."]))
   (when-let [err (:error state)]
     [:div.error-line err])])

(defn- messages-column
  [{:keys [scroll ck state outbox pending? feed since marks first-id]}]
  (let [{:keys [msgs queued blocks]} feed
        running (:running state)]
    [:div.messages {:ref #(set! (.-current (:box scroll)) %)
                    :on-scroll (on-scroll scroll ck first-id state)}
     ;; Circling in the chat with the right button.
     [circle/layer "chat" ck]
     (when (:more state)
       [:div.muted.small.older-line (if (:loading-older state) "Loading earlier messages…" "")])
     (when (and (empty? msgs) (empty? outbox) (not running) (or pending? (:loaded state)))
       hello)
     (for [[i b] (map-indexed vector blocks)]
       (feed-row ck i b marks))
     (when running (working-line state since))
     (feed-tail ck state outbox queued running)]))

(defn- messages-view [{:keys [ck state] :as oc}]
  (let [box (h/use-ref nil)
        stuck (h/use-ref true)
        anchor (h/use-ref nil)
        scroll {:box box :stuck stuck :anchor anchor}
        {:keys [msgs blocks last-user] :as feed} (pure/feed state)
        running (:running state)
        first-id (:id (first msgs))
        since @(rf/subscribe [:turn-since ck])
        pictures @(rf/subscribe [:pictures-of ck])
        marks {:picture-turns (set (map :turn pictures))
               :change-turns (frequencies (map :turn (:changes state)))
               :viewing @(rf/subscribe [:viewing-msg ck])
               :read-only? @(rf/subscribe [:read-only?])
               :last-user last-user :running running :last-i (dec (count blocks))}]
    (jump! ck (:more state))
    (use-keep-place! scroll ck first-id)
    (use-follow-bottom! scroll [ck (count msgs) (count (:text (peek msgs))) (count (:outbox oc)) running])
    (use-follow-on-resize! scroll)
    (h/use-effect (fn [] (set! (.-current stuck) true) nil) [ck])
    (use-load-older! scroll ck state (count msgs))
    (messages-column (assoc oc :scroll scroll :feed feed :since since :marks marks :first-id first-id))))

;; ---- composer ----

(defn- new-chat-on
  "Where a new chat starts: by the composer of an empty chat. Only servers with a profile can be picked."
  []
  (let [servers @(rf/subscribe [:server-list])
        current @(rf/subscribe [:new-server])
        all @(rf/subscribe [:servers])]
    (when (> (count servers) 1)
      [:div.new-on {:role "radiogroup" :aria-label "Start this chat on"}
       [:span.muted "New chat on"]
       (for [{server-name :name :keys [label]} servers
             :let [off? (= :closed (get-in all [server-name :status]))]]
         ^{:key server-name}
         [:button {:role "radio" :aria-checked (= server-name current)
                   :class [(when (= server-name current) "on") (if off? "off" "up")]
                   :data-tip (if off? (str label " is off") (str "Start it on " label))
                   :on-click #(rf/dispatch [:new/server server-name])}
          [:span.server-mark {:aria-hidden true} (words/initial label)]
          label
          (when off? [:span.sf-note "off"])])])))

(def ^:private clip-path "M14.5 9.5l-5.3 5.3a3.2 3.2 0 0 1-4.5-4.5l6-6a2.1 2.1 0 0 1 3 3l-5.8 5.8a1 1 0 0 1-1.4-1.4l5.2-5.2")

(defn- send-fn
  "The composer's send: a typed message while Claude asks answers the question; else it goes."
  [draft-key text oc attached]
  (fn []
    (let [ask (first (get-in oc [:state :asks]))]
      (if-let [answers (pure/typed-answer ask (str/trim (str text)) attached)]
        (do (rf/dispatch [:ask/answer (:ck oc) (:id ask) answers])
            (rf/dispatch [:draft/set draft-key ""]))
        (rf/dispatch [:composer/send text])))))

(defn- use-grow!
  "The message box grows with its text, up to 260 px."
  [ref text]
  (h/use-layout-effect
   (fn []
     (when-let [el (.-current ref)]
       (set! (.. el -style -height) "auto")
       (set! (.. el -style -height) (str (min 260 (.-scrollHeight el)) "px")))
     nil)
   [text]))

(defn- message-box [{:keys [ref draft-key text running asking items pick send!]}]
  [:textarea {:ref #(set! (.-current ref) %)
              :on-paste #(attach/on-paste draft-key %)
              :rows 1
              :value text
              :placeholder (pure/placeholder asking running)
              :on-change #(rf/dispatch-sync [:draft/set draft-key (.. % -target -value)])
              :on-key-down (fn [e]
                             (when (and (not (usage/slash-key! e draft-key items pick))
                                        (= "Enter" (.-key e)) (not (.-shiftKey e))
                                        (not (.. e -nativeEvent -isComposing)))
                               (.preventDefault e)
                               (send!)))}])

(defn- archive-button [ck row]
  (if (:archived row)
    [:button.icon.archive {:title "Unarchive" :on-click #(rf/dispatch [:chat/archive ck false])} [archive-icon true]]
    [:button.icon.archive {:title "Archive" :on-click #(rf/dispatch [:chat/archive ck true])} [archive-icon false]]))

(defn- toolbar [{:keys [picker draft-key oc running text attached send!]}]
  (let [busy? (some :busy attached)]
    [:div.toolbar
     [:div.tools [usage/pickers oc]]
     [:input {:type "file" :multiple true :hidden true :ref #(set! (.-current picker) %)
              :on-change (fn [e] (attach/add-files! draft-key (.. e -target -files)) (set! (.. e -target -value) ""))}]
     [:button.icon.attach {:title "Add files, photos or videos" :aria-label "Add files, photos or videos"
                           :on-click #(some-> (.-current picker) .click)}
      [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d clip-path}]]]
     (when (and (:row oc) (not @(rf/subscribe [:read-only?])))
       (archive-button (:ck oc) (:row oc)))
     (when running
       [:button.stop {:title "Stop (Esc)" :on-click #(rf/dispatch [:chat/stop])} "Stop"])
     [:button.send.primary {:disabled (or (and (str/blank? text) (empty? attached)) busy?)
                            :title (if busy? "Wait until the files are sent" "Send (Enter)")
                            :on-click send!}
      "Send"]]))

(defn- composer [{:keys [draft-key oc] :as props}]
  (let [ref (h/use-ref nil)
        picker (h/use-ref nil)
        text @(rf/subscribe [:draft draft-key])
        server-meta @(rf/subscribe [:meta (or (:server oc) @(rf/subscribe [:new-server]))])
        items (usage/matches text oc server-meta)
        pick (min @(rf/subscribe [:slash-pick]) (max 0 (dec (count items))))
        attached @(rf/subscribe [:attach draft-key])
        parts (assoc props :ref ref :picker picker :text text :items items :pick pick :attached attached
                     :send! (send-fn draft-key text oc attached))]
    (use-grow! ref text)
    (h/use-effect (fn [] (some-> (.-current ref) .focus) nil) [(str draft-key)])
    [:div.composer
     [attach/chips draft-key]
     [usage/slash-menu draft-key items pick]
     [:div.input-row
      (message-box parts)
      (toolbar parts)]]))

(defn- new-chat-head [ro]
  [:header.chat-head
   [back-to-list]
   ;; A new chat's colour comes with it once it is made (the runner picks one).
   (when-not ro [:div.color-pick [:span.color-dot.none {:title "Chat colour: once the chat has started"}]])
   [:h1 [:span.t "New chat"]]])

(defn- pane-body [oc me me-error]
  (cond
    me-error [:div.messages [:div.hello [:h2 "Can't reach Claudia"] [:p.muted me-error]]]
    (nil? me) [:div.messages]
    (empty? (:servers me)) [:div.messages [:div.hello [:h2 "No servers"] [:p.muted "You have no server to chat on yet."]]]
    oc [:f> messages-view oc]
    :else [:div.messages hello]))

(defn- pane-foot [oc me ro]
  (let [started? (and oc (or (:row oc) (seq (get-in oc [:state :messages])) (:pending? oc)))]
    (if ro
      [:div.read-only-note (pure/read-only-note @(rf/subscribe [:view]))]
      (when (and me (seq (:servers me)))
        [:<>
         (when-not started? [new-chat-on])
         [:f> composer {:draft-key (if oc (:ck oc) :new)
                        :running (get-in oc [:state :running])
                        :asking (seq (get-in oc [:state :asks]))
                        :oc oc}]]))))

(defn chat-pane []
  (let [oc @(rf/subscribe [:open-chat])
        me @(rf/subscribe [:me])
        me-error @(rf/subscribe [:me-error])
        error @(rf/subscribe [:error])
        ro @(rf/subscribe [:read-only?])]
    [:section.left
     (if oc
       ^{:key (str (:ck oc))} [header oc]
       (new-chat-head ro))
     (when oc [:f> agents/chips oc])
     (when error [:div.error-line.global {:on-click #(rf/dispatch [:error/dismiss nil])} error])
     (pane-body oc me me-error)
     (pane-foot oc me ro)
     (when-let [aid (and oc @(rf/subscribe [:agent-view (:ck oc)]))]
       [agents/screen oc aid])]))
