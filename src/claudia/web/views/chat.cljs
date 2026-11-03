(ns claudia.web.views.chat
  "The chat column (web/app.tsx and web/chat.tsx of the TypeScript Claudia): the header (colour dot,
  the title that renames itself, server and folder, the changes pill), the messages (Markdown, tool
  steps folded into groups, streamed text, the working line) and the composer."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.shared.files :as files]
            [claudia.web.md :as md]
            [claudia.web.views.agents :as agents]
            [claudia.web.views.attach :as attach]
            [claudia.web.views.changes :as changes]
            [claudia.web.views.codesend :as codesend]
            [claudia.web.views.steps :as steps]
            [claudia.web.views.usage :as usage]))

(def ^:private colours
  ["burgundy" "red" "orange" "amber" "yellow" "lime" "green" "teal" "cyan"
   "sky" "blue" "indigo" "violet" "purple" "magenta" "pink" "brown" "ash"])

(defn server-mark [server]
  (let [label @(rf/subscribe [:server-label server])
        status @(rf/subscribe [:server-status server])]
    [:span.server-mark {:class (when (= :closed status) "off") :title (if (= :closed status) (str label " is off") label)
                        :aria-label label}
     (str/upper-case (subs (str label) 0 1))]))

;; ---- the picture of a turn, from its user message ----

(rf/reg-sub :viewing-msg (fn [db [_ k]] (get-in db [:viewing-msg k])))

(rf/reg-event-fx
 :chat/view-turn
 ;; The picture (and changes) of the turn a user message started, or back to live (nil).
 (fn [{:keys [db]} [_ [s c :as k] uuid]]
   (let [pictures (get-in db [:pictures k])
         msgs (get-in db [:chats k :messages])
         idx (first (keep-indexed (fn [i p] (when (= uuid (:turn p)) i)) pictures))
         ;; A turn without a picture of its own shows the last picture before its next message.
         next-at (some #(when (and (= "user" (:role %)) (> (:at % 0) (:at (some (fn [m] (when (= uuid (:id m)) m)) msgs) 0))) (:at %))
                       msgs)
         idx (or idx (last (keep-indexed (fn [i p] (when (or (nil? next-at) (< (:at p 0) next-at)) i)) pictures)))]
     (if (or (nil? uuid) (nil? idx))
       {:db (-> db (update :viewing dissoc k) (update :viewing-msg dissoc k))}
       {:db (assoc-in db [:viewing-msg k] uuid)
        :fx [[:dispatch [:picture/pick idx]]]}))))

;; ---- small parts ----

(defn- tilde [p] (str/replace (str p) #"^/home/[^/]+" "~"))

(defn clip
  "Long paths keep their end, where the file and project names are."
  [p max]
  (if (<= (count p) max)
    p
    (loop [parts (vec (str/split p #"/")) out nil]
      (let [out (or out (peek parts))
            parts (if (= out (peek parts)) (pop parts) parts)]
        (if (and (seq parts) (<= (+ (count out) (count (peek parts)) 1) (- max 2)))
          (recur (pop parts) (str (peek parts) "/" out))
          (str "…/" out))))))

(defn archive-icon
  "A box with a lid; the arrow points out of it for Unarchive."
  [out?]
  [:svg {:width 16 :height 16 :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width 1.5
         :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden true}
   [:rect {:x 1.5 :y 2.5 :width 13 :height 3.5 :rx 0.8}]
   [:path {:d "M2.8 6v6.5a1 1 0 0 0 1 1h8.4a1 1 0 0 0 1-1V6"}]
   (if out? [:path {:d "M8 12V8.2M6.3 9.8 8 8.1l1.7 1.7"}] [:path {:d "M6.3 9h3.4"}])])

(defn- colour-picker
  "A chat's colour: a dot in the header opens the palette."
  [ck color]
  (let [[open set-open] (h/use-state false)
        box (h/use-ref nil)
        pick (fn [c] (rf/dispatch [:ops/color ck c]) (set-open false))]
    (h/use-effect
     (fn []
       (when open
         (let [away (fn [e] (when-not (some-> (.-current box) (.contains (.-target e))) (set-open false)))
               esc (fn [e] (when (= "Escape" (.-key e)) (set-open false)))]
           (js/window.addEventListener "pointerdown" away)
           (js/window.addEventListener "keydown" esc)
           #(do (js/window.removeEventListener "pointerdown" away)
                (js/window.removeEventListener "keydown" esc)))))
     [open])
    [:div.color-pick {:ref #(set! (.-current box) %)}
     [:button.color-dot {:class (when-not color "none")
                         :style {:background (when (some #{color} colours) (str "var(--c-" color ")"))}
                         :on-click #(set-open not) :title "Chat colour" :aria-haspopup "true" :aria-expanded open}]
     (when open
       [:div.palette {:role "menu"}
        (for [c colours]
          ^{:key c}
          [:button.swatch {:class [(when (= c color) "on") (when (= c "ash") "passive")]
                           :style {:background (str "var(--c-" c ")")}
                           :title (if (= c "ash") "Passive: sinks to the bottom of the rail" c) :aria-label c
                           :on-click #(pick c)}])
        [:button.swatch.none {:class (when-not color "on") :title "No colour" :aria-label "No colour"
                              :on-click #(pick nil)}]])]))

(def ^:private back-icon [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M12.5 4L6.5 10l6 6"}]])
(def ^:private picture-icon
  [:svg {:viewBox "0 0 20 20" :aria-hidden true}
   [:rect {:x 3 :y 3.5 :width 14 :height 13 :rx 2.5}] [:circle {:cx 8 :cy 9 :r 2}] [:path {:d "M11 13.5l2-2 2.5 2.5"}]])

(defn- back-to-list []
  (let [rows @(rf/subscribe [:rows])
        open @(rf/subscribe [:open])
        others (remove #(or (= open [(:server %) (:id %)]) (:off %)) rows)
        waiting (count (filter :waiting others))
        done (count (filter #(and (not (:waiting %)) (:unseen %)) others))
        ro @(rf/subscribe [:read-only?])
        done (if ro 0 done)]
    [:button.back-to-list {:on-click #(rf/dispatch [:nav/list]) :aria-label "Chats"}
     back-icon "Chats"
     (when (pos? (+ waiting done))
       [:span.count {:class (when (pos? waiting) "waiting")} (+ waiting done)])]))

(defn- pic-open-button [k]
  (let [pictures @(rf/subscribe [:pictures-of k])
        closed-at @(rf/subscribe [:pic-closed-at])
        unseen? (> (:at (peek pictures) 0) closed-at)]
    [:button.icon.pic-open {:class (when unseen? "unseen") :aria-label (if unseen? "New picture · show" "Show picture")
                            :on-click #(rf/dispatch [:nav/screen :picture])}
     picture-icon]))

(rf/reg-sub :pictures-of (fn [db [_ k]] (get-in db [:pictures k] [])))
(defonce ^:private loaded-at (js/Date.now))
(rf/reg-sub :pic-closed-at (fn [db] (or (:pic-closed-at db) loaded-at)))

;; ---- the header ----

(defn- header [{:keys [ck server chat pending? row state status]}]
  (let [editing (r/atom false)]
    (fn [{:keys [ck server chat pending? row state status]}]
      (let [title (or (:title row) (:title state) "New chat")
            multi? @(rf/subscribe [:multi-server?])
            ro @(rf/subscribe [:read-only?])
            label @(rf/subscribe [:server-label server])
            color (:color row)
            renamable? (and row (not ro))
            folder (or (:folder row) (:folder state))
            rename! (fn [t]
                      (reset! editing false)
                      (let [t (str/trim t)]
                        (when (and chat (seq t) (not= t title)) (rf/dispatch [:chat/rename ck t]))))]
        [:header.chat-head {:class (when color "colored")}
         [back-to-list]
         (when-not ro [:f> colour-picker ck color])
         (if @editing
           [:input.title-edit {:default-value title :auto-focus true
                               :on-blur #(rename! (.. % -target -value))
                               :on-key-down #(case (.-key %)
                                               "Enter" (rename! (.. % -currentTarget -value))
                                               "Escape" (reset! editing false)
                                               nil)}]
           [:h1 {:class (when renamable? "renamable")
                 :title (when renamable? "Click to rename")
                 :on-click #(when renamable? (reset! editing true))}
            [:span.t title]
            (when renamable? [:span.pencil {:aria-hidden true} "✎"])])
         (when (and multi? server (or row (seq (:messages state))))
           [:span.folder-chip.server-chip {:title (str "On " label)} label])
         (when (seq folder)
           [:span.folder-chip {:title (str "Working folder: " folder)} (clip (tilde folder) 28)])
         (when (and chat (not pending?)) [changes/pill ck state folder])
         (cond (= :closed status) [:span.offline (str label " is off")]
               (not= :open status) [:span.offline "reconnecting…"])
         [pic-open-button ck]]))))

;; ---- messages ----

(defn- elapsed [since]
  (let [[_ tick] (h/use-state 0)]
    (h/use-effect (fn [] (let [t (js/setInterval #(tick inc) 1000)] #(js/clearInterval t))) [])
    (let [s (js/Math.floor (/ (- (js/Date.now) since) 1000))]
      (if (< s 60) (str s "s") (str (quot s 60) "m " (mod s 60) "s")))))

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

(defn- user-msg [ck {:keys [id text images]} {:keys [viewing? picture? files forkable? queued?]}]
  (let [[server chat] ck]
    [:div.msg.user {:data-at id :class [(when viewing? "viewing") (when queued? "queued")]
                    :title (when queued? "Claude reads this at its next step")}
     [:div.bubble
      (when (and images (pos? images)) [attach/sent-images server chat id images])
      [user-text server chat text]]
     (when queued? [:span.queued-tag "queued"])
     (when (and (not queued?) (or picture? (pos? files) forkable?))
       [:div.msg-links
        (when forkable?
          [:button.pic-link.fork-link {:title "A new chat with this one up to the end of this turn, in the same folder"
                                       :on-click #(rf/dispatch [:ops/fork ck id])}
           "fork from here"])
        (when (or picture? (pos? files))
          [:button.pic-link {:title "Show the picture from this turn"
                             :on-click #(rf/dispatch [:chat/view-turn ck (when-not viewing? id)])}
           (if viewing?
             "back to live"
             (str/join " · " (remove nil? [(when picture? "picture")
                                           (when (pos? files) (str files (if (= 1 files) " file" " files")))])))])])]))

(rf/reg-event-db :nav/jump-done (fn [db _] (dissoc db :jump-to)))

(rf/reg-event-fx
 :nav/jump
 ;; A search hit: its chat opens and scrolls to the message once it is on the page.
 (fn [{:keys [db]} [_ k uuid]]
   {:db (assoc db :jump-to uuid)
    :fx [[:dispatch [:nav/open k]]]}))

(defn- jump! [ck more?]
  (let [uuid @(rf/subscribe [:jump-to])]
    (h/use-effect
     (fn []
       (when uuid
         (let [tries (atom 0)
               iv (atom nil)]
           (reset! iv (js/setInterval
                       (fn []
                         (let [el (js/document.querySelector (str "[data-at=\"" (js/CSS.escape uuid) "\"]"))]
                           (when (and (not el) more?) (rf/dispatch [:ops/older ck]))
                           (when (or el (> (swap! tries inc) (if more? 400 40)))
                             (js/clearInterval @iv)
                             (rf/dispatch [:nav/jump-done])
                             (when el
                               (.scrollIntoView el #js {:block "center" :behavior "smooth"})
                               (.remove (.-classList el) "found-flash")
                               (.-offsetWidth el)
                               (.add (.-classList el) "found-flash")))))
                       120))
           #(js/clearInterval @iv))))
     [uuid (str ck)])))

(rf/reg-sub :jump-to (fn [db] (:jump-to db)))

(defn- messages-view [{:keys [ck state outbox pending?]}]
  (let [box (h/use-ref nil)
        stuck (h/use-ref true)
        msgs (vec (:messages state))
        running (:running state)
        last-len (count (:text (peek msgs)))
        first-id (:id (first msgs))
        anchor (h/use-ref nil)
        since @(rf/subscribe [:turn-since ck])
        pictures @(rf/subscribe [:pictures-of ck])
        picture-turns (set (map :turn pictures))
        change-turns (frequencies (map :turn (:changes state)))
        viewing @(rf/subscribe [:viewing-msg ck])
        ro @(rf/subscribe [:read-only?])
        ;; Sent while Claude worked, not taken in yet: below the chat until then (PROTOCOL.md, Msg queued).
        queued (filterv :queued msgs)
        bs (steps/blocks (if (seq queued) (filterv (complement :queued) msgs) msgs))
        last-user (when running (:id (last (filter #(and (= "user" (:role %)) (not (:queued %))) msgs))))]
    (jump! ck (:more state))
    ;; Older messages came in above: keep what was on screen where it was.
    (h/use-layout-effect
     (fn []
       (when-let [el (.-current box)]
         (let [[was-ck was-first was-height] (.-current anchor)]
           (when (and was-first (= was-ck ck) (not= was-first first-id) (not (.-current stuck)))
             (set! (.-scrollTop el) (+ (.-scrollTop el) (- (.-scrollHeight el) was-height)))))
         (set! (.-current anchor) [ck first-id (.-scrollHeight el)]))
       nil))
    ;; Follow the bottom while the person is there; leave them be once they scrolled up.
    (h/use-layout-effect
     (fn []
       (when-let [el (.-current box)]
         (when (.-current stuck) (set! (.-scrollTop el) (.-scrollHeight el))))
       nil)
     [ck (count msgs) last-len (count outbox) running])
    ;; A shorter column (a phone's keyboard, the message box growing) keeps its end in view.
    (h/use-layout-effect
     (fn []
       (when-let [el (.-current box)]
         (let [ro (js/ResizeObserver. #(when (.-current stuck) (set! (.-scrollTop el) (.-scrollHeight el))))]
           (.observe ro el)
           #(.disconnect ro))))
     [])
    (h/use-effect (fn [] (set! (.-current stuck) true) nil) [ck])
    ;; Near the top (or a first page shorter than the column): the next older page.
    (h/use-effect
     (fn []
       (when-let [el (.-current box)]
         (when (and (:more state) (not (:loading-older state)) (< (.-scrollTop el) 600))
           (rf/dispatch [:ops/older ck])))
       nil)
     [(count msgs) (:more state) (:loading-older state)])
    [:div.messages {:ref #(set! (.-current box) %)
                    :on-scroll (fn [e]
                                 (let [el (.-target e)]
                                   (set! (.-current anchor) [ck first-id (.-scrollHeight el)])
                                   (when (and (< (.-scrollTop el) 600) (:more state) (not (:loading-older state)))
                                     (rf/dispatch [:ops/older ck]))
                                   (set! (.-current stuck)
                                         (< (- (.-scrollHeight el) (.-scrollTop el) (.-clientHeight el)) 80))))}
     (when (:more state)
       [:div.muted.small.older-line (if (:loading-older state) "Loading earlier messages…" "")])
     (when (and (empty? msgs) (empty? outbox) (not running) (or pending? (:loaded state)))
       [:div.hello [:h2 "What are we working on?"] [:p.muted "Claude answers short here and explains on the right."]])
     (for [[i b] (map-indexed vector bs)]
       (case (:k b)
         :steps ^{:key (str "s" (:id b))} [steps/steps ck (:tools b) (and running (= i (dec (count bs))))]
         :shown ^{:key (str "shown-" (:id (:tool b)))} [:div.msg.claude.shown [attach/shown-image (first ck) (second ck) (:id (:tool b))]]
         (let [{:keys [id role text streaming] :as m} (:m b)]
           (case role
             "user" ^{:key id} [user-msg ck m {:viewing? (= viewing id)
                                               :picture? (contains? picture-turns id)
                                               :files (get change-turns id 0)
                                               :forkable? (and (not ro) (not (str/starts-with? (str id) "answer-")) (not= id last-user))}]
             "note" ^{:key (str "note-" id "-" i)} [:pre.note-line text]
             ^{:key id} [:div.msg.claude {:data-at (first (str/split (str id) #":")) :class (when streaming "streaming")}
                         [:div.md {:dangerouslySetInnerHTML (r/unsafe-html (md/html text))}]]))))
     (when running
       [:div.working [:span.pulse] " "
        (if (seq (:asks state)) "Waiting for your answer" "Working")
        " · " [:f> elapsed (or since (js/Date.now))]])
     (for [[i t] (map-indexed vector outbox)]
       ^{:key (str "out" i)} [user-msg ck {:id (str "out" i) :text t} {:queued? true}])
     (for [m queued] ^{:key (:id m)} [user-msg ck m {:queued? true}])
     (when (:stopped state) (when-not running [:div.muted.small "Stopped."]))
     (when-let [err (:error state)]
       [:div.error-line err])]))

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
       (for [{:keys [name label]} servers
             :let [off? (= :closed (get-in all [name :status]))]]
         ^{:key name}
         [:button {:role "radio" :aria-checked (= name current)
                   :class [(when (= name current) "on") (if off? "off" "up")]
                   :data-tip (if off? (str label " is off") (str "Start it on " label))
                   :on-click #(rf/dispatch [:new/server name])}
          [:span.server-mark {:aria-hidden true} (str/upper-case (subs (str label) 0 1))]
          label
          (when off? [:span.sf-note "off"])])])))

(def ^:private clip-path "M14.5 9.5l-5.3 5.3a3.2 3.2 0 0 1-4.5-4.5l6-6a2.1 2.1 0 0 1 3 3l-5.8 5.8a1 1 0 0 1-1.4-1.4l5.2-5.2")

(defn- composer [{:keys [draft-key running asking oc]}]
  (let [ref (h/use-ref nil)
        picker (h/use-ref nil)
        text @(rf/subscribe [:draft draft-key])
        meta @(rf/subscribe [:meta (or (:server oc) @(rf/subscribe [:new-server]))])
        items (usage/matches text oc meta)
        pick (min @(rf/subscribe [:slash-pick]) (max 0 (dec (count items))))
        attached @(rf/subscribe [:attach draft-key])
        busy? (some :busy attached)
        row (:row oc)
        ck (:ck oc)
        send! (fn []
                (let [t (str/trim (str text))
                      ask (first (get-in oc [:state :asks]))]
                  ;; A typed message while Claude asks answers the question.
                  (if (and ask (= "question" (:kind ask)) (seq t) (empty? attached))
                    (do (rf/dispatch [:ask/answer ck (:id ask) (into {} (map (fn [q] [(:question q) t]) (:questions ask)))])
                        (rf/dispatch [:draft/set draft-key ""]))
                    (rf/dispatch [:composer/send text]))))]
    (h/use-layout-effect
     (fn []
       (when-let [el (.-current ref)]
         (set! (.. el -style -height) "auto")
         (set! (.. el -style -height) (str (min 260 (.-scrollHeight el)) "px")))
       nil)
     [text])
    (h/use-effect (fn [] (some-> (.-current ref) .focus) nil) [(str draft-key)])
    [:div.composer
     [attach/chips draft-key]
     [usage/slash-menu draft-key items pick]
     [:div.input-row
      [:textarea {:ref #(set! (.-current ref) %)
                  :on-paste #(attach/on-paste draft-key %)
                  :rows 1
                  :value text
                  :placeholder (cond asking "Answer on the right, or type an answer here…"
                                     running "Add to this turn…"
                                     :else "Message Claude…")
                  :on-change #(rf/dispatch-sync [:draft/set draft-key (.. % -target -value)])
                  :on-key-down (fn [e]
                                 (when (and (not (usage/slash-key! e draft-key items pick))
                                            (= "Enter" (.-key e)) (not (.-shiftKey e))
                                            (not (.. e -nativeEvent -isComposing)))
                                   (.preventDefault e)
                                   (send!)))}]
      [:div.toolbar
       [:div.tools [usage/pickers oc]]
       [:input {:type "file" :multiple true :hidden true :ref #(set! (.-current picker) %)
                :on-change (fn [e] (attach/add-files! draft-key (.. e -target -files)) (set! (.. e -target -value) ""))}]
       [:button.icon.attach {:title "Add files, photos or videos" :aria-label "Add files, photos or videos"
                             :on-click #(some-> (.-current picker) .click)}
        [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d clip-path}]]]
       (when (and row (not @(rf/subscribe [:read-only?])))
         (if (:archived row)
           [:button.icon.archive {:title "Unarchive" :on-click #(rf/dispatch [:chat/archive ck false])} [archive-icon true]]
           [:button.icon.archive {:title "Archive" :on-click #(rf/dispatch [:chat/archive ck true])} [archive-icon false]]))
       (when running
         [:button.stop {:title "Stop (Esc)" :on-click #(rf/dispatch [:chat/stop])} "Stop"])
       [:button.send.primary {:disabled (or (and (str/blank? text) (empty? attached)) busy?)
                              :title (if busy? "Wait until the files are sent" "Send (Enter)")
                              :on-click send!}
        "Send"]]]]))

(defn chat-pane []
  (let [oc @(rf/subscribe [:open-chat])
        me @(rf/subscribe [:me])
        me-error @(rf/subscribe [:me-error])
        error @(rf/subscribe [:error])
        ro @(rf/subscribe [:read-only?])
        started? (and oc (or (:row oc) (seq (get-in oc [:state :messages])) (:pending? oc)))]
    [:section.left
     (if oc
       ^{:key (str (:ck oc))} [header oc]
       [:header.chat-head
        [back-to-list]
        ;; A new chat's colour comes with it once it is made (the runner picks one).
        (when-not ro [:div.color-pick [:span.color-dot.none {:title "Chat colour: once the chat has started"}]])
        [:h1 [:span.t "New chat"]]
        [:button.icon.pic-open {:aria-label "Show picture" :on-click #(rf/dispatch [:nav/screen :picture])} picture-icon]])
     (when oc [:f> agents/chips oc])
     (when error [:div.error-line.global {:on-click #(rf/dispatch [:error/dismiss nil])} error])
     (cond
       me-error [:div.messages [:div.hello [:h2 "Can't reach Claudia"] [:p.muted me-error]]]
       (nil? me) [:div.messages]
       (empty? (:servers me)) [:div.messages [:div.hello [:h2 "No servers"] [:p.muted "You have no server to chat on yet."]]]
       oc [:f> messages-view oc]
       :else [:div.messages
              [:div.hello [:h2 "What are we working on?"] [:p.muted "Claude answers short here and explains on the right."]]])
     (if ro
       (let [v (str @(rf/subscribe [:view]))]
         [:div.read-only-note (str (if (seq v) (str (str/upper-case (subs v 0 1)) (subs v 1) "'s chat") "Someone else's chat") " · read only")])
       (when (and me (seq (:servers me)))
         [:<>
          (when-not started? [new-chat-on])
          [:f> composer {:draft-key (if oc (:ck oc) :new)
                         :running (get-in oc [:state :running])
                         :asking (seq (get-in oc [:state :asks]))
                         :oc oc}]]))
     (when-let [aid (and oc @(rf/subscribe [:agent-view (:ck oc)]))]
       [agents/screen oc aid])]))
