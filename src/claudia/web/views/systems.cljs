(ns claudia.web.views.systems
  "System (README decision 96; Machines before it, decisions 12, 75, 91), a sheet on the right
  half's Drawing board, opened by the header's System button, the edge's and the menu's entries:
  one tab per server the person may see (the gate for admins), each with its dot; under it the
  tiles, \"Needs a look\" and the tabs. It asks while open: the shown server's Sample every 2 s (the
  others' every 15 s, for their dots), every server's Details every 15 s, the shown server's 24
  hours on opening and every minute (its week while History shows), the labels on opening, the
  chats' proposals from every server's runner every 15 s. It never changes anything on a server."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.api :as api]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.pure.system-feed :as feed]
            [claudia.web.views.pure.system-look :as look]
            [claudia.web.views.system-look :as look-view]
            [claudia.web.views.system-tabs :as tabs-view]
            [claudia.web.views.system-tiles :as tiles-view]))

(def icon-parts
  "System's icon, a pulse, drawn in a 20×20 box (the sheet's title, the edge, the menu)."
  [[:path {:d "M2 10.5h3.6l2.1-5 3.6 9.5 2.2-4.5H18"}]])

(defn system-icon []
  (into [:svg {:viewBox "0 0 20 20" :aria-hidden true :fill "none" :stroke "currentColor"
               :stroke-width 1.6 :stroke-linecap "round" :stroke-linejoin "round"}]
        icon-parts))

;; ---- what it asks, each a part that draws nothing ----

(defn- poll!
  "Asks url every ms while mounted (again at once when deps change); answers to on-answer, the
  error (nil after an answer) to on-error."
  [url ms on-answer on-error deps]
  (let [error (hooks/use-poll {:on? (some? url) :url-of (constantly url) :ms ms :on-answer on-answer} deps)]
    (h/use-effect (fn [] (on-error error) js/undefined) [error])
    nil))

(defn- server-feed
  "One tab's Sample and Details (and, shown, its 24 hours and, on History, its week) into merge!."
  [{:keys [tab shown? week? merge!]}]
  (let [k (:name tab)
        put (fn [m] (merge! k m))]
    (poll! (feed/base-url tab) (if shown? 2000 15000) #(put {:sample % :got-at (js/Date.now)}) #(put {:error %}) [k shown?])
    (poll! (feed/details-url tab) 15000 #(put {:details %}) #(put {:details-error %}) [k])
    (poll! (when shown? (feed/history-url tab 24)) 60000 #(put {:day %}) identity [k shown?])
    (poll! (when week? (feed/history-url tab 168)) 60000 #(put {:week %}) identity [k week?])
    nil))

(def ^:private rare-ms "Asked once on opening (and on a refresh): the deps restart it." 3600000)

(defn- shared-feed
  "The labels (on opening and each refresh) into put!."
  [{:keys [stamp put!]}]
  (poll! "/hub/system/labels" rare-ms #(put! :labels (:labels %)) identity [stamp])
  nil)

(defn- proposals-feed
  "A runner's proposals every 15 s (again at once on a refresh) into put!."
  [{:keys [server stamp put!]}]
  (poll! (api/url server "system/proposals" nil) 15000 #(put! [:proposals server] (:proposals %)) identity [server stamp])
  nil)

;; ---- the server tabs ----

(defn- server-tab [{:keys [label]} h on? pick!]
  [:button.sy-server {:type "button" :role "tab" :aria-selected on? :class (when on? "on") :on-click pick!}
   [:i.sy-health {:class h :data-tip (look/health-tip h) :aria-label (look/health-tip h)}]
   label])

(defn- server-tabs [tabs shown dot-of pick!]
  [:div.sy-servers {:role "tablist" :aria-label "Servers"}
   (for [t tabs] ^{:key (:name t)} [server-tab t (dot-of t) (= shown (:name t)) #(pick! (:name t))])])

;; ---- the sheet ----

(defn- use-store
  "What the sheet heard, in one map: [store merge! (into one server's map) put! (at a key or path)]."
  []
  (let [[store set-store] (h/use-state {})]
    [store
     (fn [k m] (set-store update-in [:servers k] merge m))
     (fn [path v] (set-store assoc-in (if (vector? path) path [path]) v))]))

(defn- feeds
  "The parts that ask: every tab's own, the labels, every runner's proposals."
  [{:keys [tabs shown view stamp merge! put!]}]
  [:<>
   (for [t tabs]
     ^{:key (:name t)}
     [:f> server-feed {:tab t :shown? (= shown (:name t)) :week? (and (= shown (:name t)) (= view "history"))
                       :merge! merge!}])
   [:f> shared-feed {:stamp stamp :put! put!}]
   (for [t tabs :when (not (:gate? t))]
     ^{:key (:name t)} [:f> proposals-feed {:server (:name t) :stamp stamp :put! put!}])])

(defn- server-context
  "What the parts of one server's System need: its feed, the labels, its rows needing a look."
  [{:keys [store tab now] :as base}]
  (let [fd (get-in store [:servers (:name tab)])
        labels (:labels store)
        proposals (feed/proposals-for (:proposals store) (:name tab))
        sc (merge base fd {:labels labels :server (:name tab)})]
    (assoc sc :rows (look/attention (assoc fd :labels labels :server (:name tab) :now now) proposals))))

(defn- unreached [{:keys [tab error sample]}]
  (when-not sample
    [:div.sy-unreached
     (if error (str (:label tab) " does not answer: " error) (str "Asking " (:label tab) "…"))]))

(defn- server-view [{:keys [sample rows] :as sc}]
  [:<>
   [unreached sc]
   (when sample [tiles-view/tiles sc])
   (when sample [look-view/needs-a-look sc rows])
   [tabs-view/tabs sc]])

(defn- health-of
  "A tab's dot from what the sheet heard."
  [store now]
  (fn [{server :name}]
    (look/health (assoc (get-in store [:servers server]) :labels (:labels store) :server server :now now))))

(defn- sheet []
  (let [me @(rf/subscribe [:me])
        tabs (feed/tabs me)
        here (or (:server @(rf/subscribe [:open-chat])) @(rf/subscribe [:new-server]))
        [picked set-picked] (h/use-state nil)
        [view set-view] (h/use-state "ports")
        [stamp set-stamp] (h/use-state 0)
        [store merge! put!] (use-store)
        now (hooks/use-now)
        shown (or (some #{picked} (map :name tabs)) (feed/opening-tab tabs here))
        tab (some #(when (= shown (:name %)) %) tabs)]
    [:div.system-pane.rv-pane {:role "region" :aria-label "System"}
     [feeds {:tabs tabs :shown shown :view view :stamp stamp :merge! merge! :put! put!}]
     (if-not tab
       [:div.sy-empty "No servers to show."]
       [:<>
        [server-tabs tabs shown (health-of store now) set-picked]
        [server-view (server-context {:store store :tab tab :now now :view view :set-view set-view
                                      :admin? (boolean (:admin me)) :ro? @(rf/subscribe [:read-only?])
                                      :refresh! #(set-stamp inc)})]])]))

(defn pane
  "System in the right half: its sheet's body."
  []
  [:f> sheet])
