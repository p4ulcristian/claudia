(ns claudia.web.tabs
  "Other tabs of this browser profile: which chat each one shows, so the chat list can mark the
  chats open elsewhere. A BroadcastChannel carries what each tab shows (no server: the channel is
  this origin in this profile only); a Web Lock per tab, held until the tab is gone (closed, crashed,
  discarded), says which tabs still live, so a tab that died without a goodbye drops out too.

  Messages: here (this tab shows open, as view), bye (this tab is closing), who (a tab started or came
  back: everyone say here)."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rdb]))

(def ^:private channel-name "claudia-tabs")
(def ^:private lock-prefix "claudia-tab:")

(defonce ^:private tab (str (random-uuid)))
(defonce ^:private channel (atom nil))

;; ---- pure ----

(defn heard
  "Other tabs ({tab {:open :view}}) after one message from another tab: here sets what it shows,
  bye drops it, anything else leaves them as they were."
  [tabs {:keys [kind from open view]}]
  (case kind
    "here" (assoc tabs from {:open open :view view})
    "bye" (dissoc tabs from)
    tabs))

(defn alive
  "Other tabs, keeping only those whose lock is still held (lock names held, every tab's)."
  [tabs held]
  (let [live (set (keep #(when (and % (.startsWith % lock-prefix)) (subs % (count lock-prefix))) held))]
    (select-keys tabs live)))

(defn elsewhere
  "The chats ([server chat]) other tabs show, among tabs showing the same profile's chats (view)."
  [tabs view]
  (into #{} (comp (filter #(= view (:view %))) (keep :open)) (vals tabs)))

(defn message
  "A message as it goes on the channel (plain JSON-able JS)."
  [kind open view]
  (clj->js {:kind kind :from tab :open open :view view}))

(defn read-message
  "A channel message back as data; nil when it isn't one of ours."
  [^js data]
  (when (and data (string? (.-kind data)) (string? (.-from data)))
    {:kind (.-kind data) :from (.-from data) :view (.-view data)
     :open (some-> (.-open data) js->clj vec)}))

;; ---- re-frame ----

(rf/reg-event-db :tabs/heard (fn [db [_ m]] (update db :tabs heard m)))
(rf/reg-event-db :tabs/alive (fn [db [_ held]] (update db :tabs alive held)))

(rf/reg-sub :tabs (fn [db] (:tabs db)))

(rf/reg-sub
 :open-elsewhere
 ;; The chats open in another tab of this browser.
 ;; (:view is claudia.web.chatops's.)
 :<- [:tabs] :<- [:view]
 (fn [[tabs view]] (elsewhere tabs view)))

;; ---- edges ----

(defn- post! [kind]
  (when-let [^js ch @channel]
    (let [{:keys [open view]} @rdb/app-db]
      (.postMessage ch (message kind open view)))))

(defn- check-alive!
  "Asks the browser which tab locks are held and drops the tabs whose lock is gone."
  []
  (when-let [^js locks (.-locks js/navigator)]
    (-> (.query locks)
        (.then (fn [^js snap] (rf/dispatch [:tabs/alive (map #(.-name ^js %) (.-held snap))])))
        (.catch (fn [_] nil)))))

(defn- on-message [^js e]
  (when-let [{:keys [kind from] :as m} (read-message (.-data e))]
    (when (not= from tab)
      (when (= "who" kind) (post! "here"))
      (rf/dispatch [:tabs/heard m]))))

(defn- hold-lock!
  "Holds this tab's lock until the tab is gone; then says here and asks who else is there."
  []
  (let [hello! (fn [] (post! "here") (post! "who"))]
    (if-let [^js locks (.-locks js/navigator)]
      (.request locks (str lock-prefix tab) (fn [_] (hello!) (js/Promise. (fn [_]))))
      (hello!))))

(defn- watch-open!
  "Says here whenever the open chat (or whose chats are shown) changes."
  []
  (add-watch rdb/app-db ::open
             (fn [_ _ before after]
               (when (not= (select-keys before [:open :view]) (select-keys after [:open :view]))
                 (post! "here")))))

(defn- watch-page!
  "Bye when the page goes (or into the back-forward cache); hello again when it comes back."
  []
  (js/window.addEventListener "pagehide" #(post! "bye"))
  (js/window.addEventListener "pageshow" #(when (.-persisted ^js %) (post! "here") (post! "who") (check-alive!)))
  (js/document.addEventListener "visibilitychange" check-alive!)
  (js/setInterval check-alive! 10000))

(defn start!
  "Once, in a browser that has BroadcastChannel."
  []
  (when (and (nil? @channel) (exists? js/BroadcastChannel))
    (let [ch (js/BroadcastChannel. channel-name)]
      (set! (.-onmessage ch) on-message)
      (reset! channel ch)
      (watch-open!)
      (watch-page!)
      (hold-lock!))))
