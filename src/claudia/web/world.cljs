(ns claudia.web.world
  "The 3D world (whole-window mode): every server an island round the gate's lighthouse, every chat a
  robot in its colour at its project's building. The rail's globe or Alt+W opens and closes it. A
  click on a robot's screen opens its chat in a panel over the world, a click on an island's board
  its server's Systems card: a picture of the screen or board flies to the panel's place first, the
  real content is drawn once it has landed. Escape, × or a click elsewhere flies it back; Escape
  without a panel leaves the world. three.js and the engine load on first use (the :world module)."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rdb]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [shadow.lazy :as lazy]
            [claudia.web.views.chat :as chat]
            [claudia.web.views.systems :as systems]
            [claudia.web.world.link :as link]
            [claudia.web.world.state :as state]))

(def ^:private lazy-view (lazy/loadable claudia.web.world.view/world-view))

(defonce ^:private loaded (r/atom nil))

;; ---- state ----

(rf/reg-event-db :world/toggle (fn [db [_ open?]] (state/toggled db open?)))
(rf/reg-event-db :world/close (fn [db _] (dissoc db :world-panel :world-from)))
(rf/reg-event-fx :world/pick (fn [{:keys [db]} [_ p]] (state/pick-fx db p)))
(declare close-panel!)
(rf/reg-fx :world/close-panel (fn [_] (close-panel!)))
(rf/reg-sub :world? (fn [db] (boolean (:world? db))))
(rf/reg-sub :world/panel (fn [db] (:world-panel db)))
(rf/reg-sub :world/focus (fn [db] (state/focus-id db)))
(rf/reg-sub :world/from (fn [db] (:world-from db)))

;; ---- a panel's flight out of its screen or board and back ----

(defn- box [^js el]
  (let [r (.getBoundingClientRect el)] {:left (.-left r) :top (.-top r) :width (.-width r) :height (.-height r)}))

(defn- fly!
  "Animates the panel el between rect (a screen's or a board's) and its own place: :out of it, or
  back :in. Only the transform and opacity change, so the compositor alone draws the flight."
  [^js el rect way]
  (let [at-rect #js {:transform (state/fly-transform rect (box el)) :opacity 0.6 :borderRadius "28px"}
        at-home #js {:transform "none" :opacity 1}]
    (.animate el (if (= way :out) #js [at-rect at-home] #js [at-home at-rect])
              #js {:duration (if (= way :out) 420 320) :easing "cubic-bezier(.2,.8,.2,1)" :fill "forwards"})))

(defn- target-rect
  "Where the open panel's screen or board is now, or nil."
  [{:keys [kind target]}]
  (when-let [f (get @link/api (if (= kind :stats) :board-rect :screen-rect))]
    (f target)))

(defn- close-panel!
  "The panel flies back into its screen or board (its picture over it again), then closes; at once
  when there is nothing to fly into."
  []
  (let [panel (:world-panel @rdb/app-db)
        ^js el (js/document.querySelector ".world-panel")
        rect (target-rect panel)]
    (if (and el rect)
      (do (.add (.-classList el) "closing")
          (.then (.-finished (fly! el rect :in)) #(rf/dispatch [:world/close])))
      (rf/dispatch [:world/close]))))

;; ---- keys ----

(defn- on-key [^js e]
  (let [db @rdb/app-db
        action (state/key-action {:alt? (.-altKey e) :code (.-code e)} (:world? db))]
    (when action
      (.preventDefault e)
      (cond (= action :toggle) (rf/dispatch [:world/toggle])
            (:world-panel db) (close-panel!)
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

(defn- picture-of
  "The flying picture of the panel's screen or board (a canvas), or nil."
  [{:keys [kind target]}]
  (when-let [f (get @link/api (if (= kind :stats) :board-image :screen-image))]
    (f target)))

(defn- use-flight!
  "Flies the panel el out of from (a rect, or nil: no flight) with the screen's or board's picture in
  ghost; true once it has landed (then the real content is drawn)."
  [el ghost from panel]
  (let [[landed? set-landed] (h/use-state (nil? from))]
    (h/use-layout-effect
     (fn []
       (when (and from (.-current el))
         (when-let [pic (picture-of panel)] (.appendChild ^js (.-current ghost) pic))
         (.then (.-finished (fly! (.-current el) from :out)) #(set-landed true)))
       nil)
     [])
    landed?))

(defn- stats-content [{:keys [target]}]
  (let [label @(rf/subscribe [:server-label target])]
    [:div.world-stats
     [:f> systems/machine-card {:title (if (= target "gate") "gate" label) :url (state/stats-url target)}]]))

(defn- panel
  "The panel over the world: the open chat or a server's Systems card."
  []
  (let [el (h/use-ref nil)
        ghost (h/use-ref nil)
        p @(rf/subscribe [:world/panel])
        landed? (use-flight! el ghost @(rf/subscribe [:world/from]) p)]
    [:div.world-panel {:role "dialog" :aria-label (if (= :stats (:kind p)) "Systems" "Chat")
                       :class [(name (:kind p)) (when landed? "landed")] :ref #(set! (.-current el) %)}
     [:div.world-ghost {:ref #(set! (.-current ghost) %)}]
     [:button.world-panel-close {:aria-label "Close (Escape)" :data-tip "Close (Escape)" :on-click close-panel!} "×"]
     (when landed? (if (= :stats (:kind p)) [stats-content p] [chat/chat-pane]))]))

(defn- legend []
  [:div.world-legend
   [:span [:i.working] "working"] [:span [:i.waiting] "waiting on you"]
   [:span [:i.done] "done, not seen"] [:span [:i.idle] "idle"]
   [:span.hint "Click a screen for its chat · a board for its server · Escape puts it back · an island: new chat"]])

(defn- world-screen []
  [:div.world {:role "application" :aria-label "World"}
   [canvas]
   [:div.world-bar
    [:button.world-back {:on-click #(rf/dispatch [:world/toggle false]) :data-tip "Back to the chats (Alt+W)"} "← Chats"]
    [:span.world-title "World"]]
   [legend]
   (when-let [p @(rf/subscribe [:world/panel])]
     ^{:key (str (:kind p) "/" (:target p))} [:f> panel])])

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
