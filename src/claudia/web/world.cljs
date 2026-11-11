(ns claudia.web.world
  "The 3D world (whole-window mode): every server an island round the gate's lighthouse, every chat a
  robot in its colour at its project's building. The rail's globe or Alt+W opens and closes it. A
  click on a robot's screen opens its chat in a panel beside the world (the robot and its screen
  stay and are highlighted), a click on an island's board its server's Systems card. Escape, × or a
  click elsewhere closes it; Escape without a panel leaves the world. three.js and the engine load
  on first use (the :world module)."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rdb]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [shadow.lazy :as lazy]
            [claudia.web.views.chat :as chat]
            [claudia.web.views.systems :as systems]
            [claudia.web.world.state :as state]))

(def ^:private lazy-view (lazy/loadable claudia.web.world.view/world-view))

(defonce ^:private loaded (r/atom nil))

;; ---- state ----

(rf/reg-event-db :world/toggle (fn [db [_ open?]] (state/toggled db open?)))
(rf/reg-event-db :world/close (fn [db _] (dissoc db :world-panel)))
(rf/reg-event-fx :world/pick (fn [{:keys [db]} [_ p]] (state/pick-fx db p)))
(rf/reg-fx :world/close-panel (fn [_] (rf/dispatch [:world/close])))
(rf/reg-sub :world? (fn [db] (boolean (:world? db))))
(rf/reg-sub :world/panel (fn [db] (:world-panel db)))
(rf/reg-sub :world/focus (fn [db] (state/focus-id db)))

;; ---- keys ----

(defn- on-key [^js e]
  (let [db @rdb/app-db
        action (state/key-action {:alt? (.-altKey e) :code (.-code e)} (:world? db))]
    (when action
      (.preventDefault e)
      (cond (= action :toggle) (rf/dispatch [:world/toggle])
            (:world-panel db) (rf/dispatch [:world/close])
            :else (rf/dispatch [:world/toggle false])))))

(defn- keys! []
  (h/use-effect
   (fn []
     ;; Capture, so Escape is taken before the shell's (which would stop the open chat's turn).
     (js/window.addEventListener "keydown" on-key true)
     #(js/window.removeEventListener "keydown" on-key true))
   []))

;; ---- views ----

(defn- canvas []
  (let [ok @loaded]
    (cond
      (= ok :ready) [:f> @lazy-view]
      (string? ok) [:p.world-error (str "The 3D world did not load: " ok)]
      :else (do (when-not ok
                  (reset! loaded :loading)
                  (-> (lazy/load lazy-view)
                      (.then #(reset! loaded :ready))
                      (.catch #(reset! loaded (str (or (.-message %) %))))))
                [:p.world-error "Loading the world…"]))))

(defn- stats-content [{:keys [target]}]
  (let [label @(rf/subscribe [:server-label target])]
    [:div.world-stats
     [:f> systems/machine-card {:title (if (= target "gate") "gate" label) :url (state/stats-url target)}]]))

(defn- panel
  "The panel beside the world: the open chat or a server's Systems card."
  []
  (let [p @(rf/subscribe [:world/panel])]
    [:div.world-panel {:role "dialog" :aria-label (if (= :stats (:kind p)) "Systems" "Chat") :class (name (:kind p))}
     [:button.world-panel-close {:aria-label "Close (Escape)" :data-tip "Close (Escape)"
                                 :on-click #(rf/dispatch [:world/close])} "×"]
     (if (= :stats (:kind p)) [stats-content p] [chat/chat-pane])]))

(defn- legend []
  [:div.world-legend
   [:span [:i.working] "working"] [:span [:i.waiting] "waiting on you"]
   [:span [:i.done] "done, not seen"] [:span [:i.idle] "idle"]
   [:span.hint "Click a screen for its chat · a board for its server · Escape closes · an island: new chat"]])

(defn- world-screen []
  [:div.world {:role "application" :aria-label "World"}
   [canvas]
   [:div.world-bar
    [:button.world-back {:on-click #(rf/dispatch [:world/toggle false]) :data-tip "Back to the chats (Alt+W)"} "← Chats"]
    [:span.world-title "World"]]
   [legend]
   (when @(rf/subscribe [:world/panel]) [panel])])

(defn overlay
  "The world over the whole window while it is open; Alt+W and Escape always listened for."
  []
  (keys!)
  (when @(rf/subscribe [:world?]) [world-screen]))

(defn- globe-icon []
  [:svg {:viewBox "0 0 20 20" :aria-hidden true}
   [:circle {:cx 10 :cy 10 :r 7}]
   [:path {:d "M3 10h14M10 3c2.2 2 2.2 12 0 14M10 3c-2.2 2-2.2 12 0 14"}]])

(defn world-button
  "The rail's globe: opens the world."
  []
  [:button.rail-forms.rail-world {:data-tip "World: your chats as robots (Alt+W)" :aria-label "World"
                                  :on-click #(rf/dispatch [:world/toggle true])}
   [globe-icon]])
