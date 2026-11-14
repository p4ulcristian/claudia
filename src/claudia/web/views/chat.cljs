(ns claudia.web.views.chat
  "The chat column (web/app.tsx and web/chat.tsx of the TypeScript Claudia): the header
  (claudia.web.views.head), the messages (Markdown, tool steps folded into groups, streamed text,
  the working line) and the composer (the mic, Attach, the message and one Send)."
  (:require [clojure.string :as str]
            [claudia.web.hooks :as hooks]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.shared.files :as files]
            [claudia.web.md :as md]
            [claudia.web.views.agents :as agents]
            [claudia.web.views.attach :as attach]
            [claudia.web.views.codesend :as codesend]
            [claudia.web.views.head :as head]
            [claudia.web.views.mic :as mic]
            [claudia.web.views.talk :as talk]
            [claudia.web.views.pure.chat :as pure]
            [claudia.web.views.pure.words :as words]
            [claudia.web.views.steps :as steps]
            [claudia.web.views.usage :as usage]))

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

(rf/reg-sub :pictures-of (fn [db [_ k]] (get-in db [:pictures k] [])))

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

(defn- use-bottom-on-open!
  "Another chat opened: it shows its end. A layout effect declared before the others, so the
  following below already knows (a plain effect ran after them, too late)."
  [{:keys [box stuck]} ck]
  (h/use-layout-effect
   (fn []
     (set! (.-current stuck) true)
     (some-> (.-current box) to-bottom!)
     nil)
   [ck]))

(defn- use-follow-growth!
  "Things that grow after they are drawn (an image, a picture's frame, the fonts) keep the end in
  view while the person is at the bottom."
  [{:keys [box stuck]}]
  (h/use-effect
   (fn []
     (when-let [^js el (.-current box)]
       (let [follow #(when (.-current stuck) (to-bottom! el))]
         (.addEventListener el "load" follow true)
         (some-> js/document.fonts .-ready (.then follow))
         #(.removeEventListener el "load" follow true))))
   []))

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
                [:div.md {:dangerouslySetInnerHTML (r/unsafe-html (md/html text))}]
                (when-not streaming [talk/say-button ck text])]))

(defn- feed-row
  "One block of the feed: a run of steps, a picture a step showed, or a message."
  [ck i b {:keys [running last-i] :as marks}]
  (case (:k b)
    :steps ^{:key (str "s" (:id b))} [steps/steps ck (:tools b) (and running (= i last-i))]
    :shown ^{:key (str "shown-" (:id (:tool b)))} [:div.msg.claude.shown
                                                   [(if (:video (:tool b)) attach/shown-video attach/shown-image)
                                                    (first ck) (second ck) (:id (:tool b))]]
    (msg-row ck i (:m b) marks)))

(def ^:private error-ms
  "How long an error shows before it goes by itself (a click takes it away sooner)."
  5000)

(defn- error-line-view [k text]
  (h/use-effect (fn [] (let [t (js/setTimeout #(rf/dispatch [:error/clear-if k text]) error-ms)]
                         #(js/clearTimeout t)))
                [k text])
  [:div.error-line {:class (when-not k "global") :on-click #(rf/dispatch [:error/dismiss k])} text])

(defn- error-line
  "An error, the chat k's (nil: the top one): it goes after error-ms, or on a click."
  [k text]
  [:f> error-line-view k text])

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
     [error-line ck err])])

(defn- messages-column
  [{:keys [scroll ck state outbox pending? feed since marks first-id]}]
  (let [{:keys [msgs queued blocks]} feed
        running (:running state)]
    [:div.messages {:ref #(set! (.-current (:box scroll)) %)
                    :on-scroll (on-scroll scroll ck first-id state)}
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
    (use-bottom-on-open! scroll ck)
    (use-keep-place! scroll ck first-id)
    (use-follow-bottom! scroll [ck (count msgs) (count (:text (peek msgs))) (count (:outbox oc)) running])
    (use-follow-on-resize! scroll)
    (use-follow-growth! scroll)
    (use-load-older! scroll ck state (count msgs))
    (messages-column (assoc oc :scroll scroll :feed feed :since since :marks marks :first-id first-id))))

;; ---- composer ----

(def ^:private clip-path "M14.5 9.5l-5.3 5.3a3.2 3.2 0 0 1-4.5-4.5l6-6a2.1 2.1 0 0 1 3 3l-5.8 5.8a1 1 0 0 1-1.4-1.4l5.2-5.2")

(defn- send-fn
  "The composer's send of text: a message typed while Claude asks answers the question; else it goes."
  [draft-key oc attached]
  (fn [text]
    (let [ask (first (get-in oc [:state :asks]))]
      (if-let [answers (pure/typed-answer ask (str/trim (str text)) attached)]
        (do (rf/dispatch [:ask/answer (:ck oc) (:id ask) answers])
            (rf/dispatch [:draft/set draft-key ""]))
        (rf/dispatch [:composer/send text])))))

(defn- heard-fn
  "What the mic's heard text does, in the composer it was held in (the mic keeps the one from the
  press, so a chat opened while it is heard gets nothing): events/heard-fx."
  [draft-key]
  (fn [heard dir] (rf/dispatch [:mic/heard draft-key heard dir])))

(defn- fit-height!
  "The message box as tall as its text, up to 260 px; empty, one row (the placeholder never sizes
  it: measured while the pane was still narrow, its wrapped lines once held the box at 260 px)."
  [^js el]
  (if (= "" (.-value el))
    (set! (.. el -style -height) "")
    (do (set! (.. el -style -height) "auto")
        (set! (.. el -style -height) (str (min 260 (.-scrollHeight el)) "px")))))

(defn- use-grow!
  "The message box grows with its text, and fits again whenever its width changes."
  [ref text]
  (h/use-layout-effect (fn [] (some-> (.-current ref) fit-height!) nil) [text])
  (hooks/use-resize ref fit-height! []))

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

(defn- attach-button [{:keys [picker draft-key]}]
  [:<>
   [:input {:type "file" :multiple true :hidden true :ref #(set! (.-current picker) %)
            :on-change (fn [e] (attach/add-files! draft-key (.. e -target -files)) (set! (.. e -target -value) ""))}]
   [:button.icon.attach {:title "Add files, photos or videos" :aria-label "Add files, photos or videos"
                         :on-click #(some-> (.-current picker) .click)}
    [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d clip-path}]]]])

(defn- send-button
  "The one button at the row's end: Stop while Claude works and nothing is typed; else Send."
  [{:keys [running text attached send!]}]
  (if (and running (not (pure/sendable? text attached)) (empty? attached))
    [:button.stop {:title "Stop (Esc)" :on-click #(rf/dispatch [:chat/stop])} "Stop"]
    [:button.send.primary {:disabled (not (pure/sendable? text attached))
                           :title (if (some :busy attached) "Wait until the files are sent" "Send (Enter)")
                           :on-click send!}
     "Send"]))

(defn- composer [{:keys [draft-key oc] :as props}]
  (let [ref (h/use-ref nil)
        picker (h/use-ref nil)
        text @(rf/subscribe [:draft draft-key])
        server-meta @(rf/subscribe [:meta (or (:server oc) @(rf/subscribe [:new-server]))])
        items (usage/matches text oc server-meta)
        pick (min @(rf/subscribe [:slash-pick]) (max 0 (dec (count items))))
        attached @(rf/subscribe [:attach draft-key])
        send-text! (send-fn draft-key oc attached)
        parts (assoc props :ref ref :picker picker :text text :items items :pick pick :attached attached
                     :send! #(send-text! text))]
    (use-grow! ref text)
    (h/use-effect (fn [] (some-> (.-current ref) .focus) nil) [(str draft-key)])
    [:div.composer
     [attach/chips draft-key]
     [usage/slash-menu draft-key items pick]
     [:div.talk-row
      [:f> mic/button (heard-fn draft-key)]
      [:div.input-row
       (attach-button parts)
       (message-box parts)
       (send-button parts)]]]))

(defn- pane-body [oc me me-error]
  (cond
    me-error [:div.messages [:div.hello [:h2 "Can't reach Claudia"] [:p.muted me-error]]]
    (nil? me) [:div.messages]
    (empty? (:servers me)) [:div.messages [:div.hello [:h2 "No servers"] [:p.muted "You have no server to chat on yet."]]]
    oc [:f> messages-view oc]
    :else [:div.messages hello]))

(defn- pane-foot [oc me ro]
  (if ro
    [:div.read-only-note (pure/read-only-note @(rf/subscribe [:view]))]
    (when (and me (seq (:servers me)))
      [:f> composer {:draft-key (if oc (:ck oc) :new)
                     :running (get-in oc [:state :running])
                     :asking (seq (get-in oc [:state :asks]))
                     :oc oc}])))

(defn chat-pane []
  (let [oc @(rf/subscribe [:open-chat])
        me @(rf/subscribe [:me])
        me-error @(rf/subscribe [:me-error])
        error @(rf/subscribe [:error])
        ro @(rf/subscribe [:read-only?])]
    [:section.left
     ^{:key (str (:ck oc))} [head/header oc]
     [talk/guard (str (:ck oc))]
     (when error [error-line nil error])
     (pane-body oc me me-error)
     ;; Background work (agents, long commands) sits right above the composer.
     (when oc [:f> agents/chips oc])
     (pane-foot oc me ro)
     (when-let [aid (and oc @(rf/subscribe [:agent-view (:ck oc)]))]
       [agents/screen oc aid])]))
