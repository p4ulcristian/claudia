(ns claudia.web.world
  "The 3D world (whole-window mode): every server an island round the gate's lighthouse, every chat a
  robot in its colour at its project's building. The rail's globe or Alt+W opens and closes it; a
  click on a robot opens its chat in a panel over the world, a click on a server's island starts a
  new chat there; Escape closes the panel, then the world. three.js and the engine load on first use
  (the :world module)."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rdb]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [shadow.lazy :as lazy]
            [claudia.web.views.chat :as chat]
            [claudia.web.world.link :as link]
            [claudia.web.world.state :as state]))

(def ^:private lazy-view (lazy/loadable claudia.web.world.view/world-view))

(defonce ^:private loaded (r/atom nil))

;; ---- state ----

(rf/reg-event-db :world/toggle (fn [db [_ open?]] (state/toggled db open?)))
(rf/reg-event-db :world/chat (fn [db [_ open?]] (assoc db :world-chat? (boolean open?))))
(rf/reg-event-fx :world/pick (fn [{:keys [db]} [_ p]] (state/pick-fx db p)))
(rf/reg-sub :world? (fn [db] (boolean (:world? db))))
(rf/reg-sub :world-chat? (fn [db] (boolean (:world-chat? db))))
(rf/reg-sub :world/focus (fn [db] (state/focus-id db)))
(rf/reg-sub :world/from (fn [db] (:world-from db)))

;; ---- the chat's flight out of its screen and back ----

(defn- box [^js el]
  (let [r (.getBoundingClientRect el)] {:left (.-left r) :top (.-top r) :width (.-width r) :height (.-height r)}))

(defn- fly!
  "Animates the panel el between screen rect and its own place: :out of the screen, or back :in."
  [^js el rect way]
  (let [at-screen #js {:transform (state/fly-transform rect (box el)) :opacity 0.5 :borderRadius "28px"}
        at-home #js {:transform "none" :opacity 1}]
    (.animate el (if (= way :out) #js [at-screen at-home] #js [at-home at-screen])
              #js {:duration (if (= way :out) 460 340) :easing "cubic-bezier(.2,.8,.2,1)" :fill "forwards"})))

(defn- close-chat!
  "The panel flies back into its robot's screen, then closes (at once when there is no screen)."
  []
  (let [id (state/focus-id @rdb/app-db)
        el (js/document.querySelector ".world-chat")
        rect (when-let [f (:screen-rect @link/api)] (f id))]
    (if (and el rect)
      (.then (.-finished (fly! el rect :in)) #(rf/dispatch [:world/chat false]))
      (rf/dispatch [:world/chat false]))))

;; ---- keys ----

(defn- on-key [^js e]
  (let [db @rdb/app-db
        action (state/key-action {:alt? (.-altKey e) :code (.-code e)} (:world? db))]
    (when action
      (.preventDefault e)
      (cond (= action :toggle) (rf/dispatch [:world/toggle])
            (:world-chat? db) (close-chat!)
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

(defn- chat-panel
  "The open chat over the world; it flies out of its robot's screen when it opens."
  []
  (let [el (h/use-ref nil)
        from @(rf/subscribe [:world/from])]
    (h/use-layout-effect (fn [] (when (and from (.-current el)) (fly! (.-current el) from :out)) nil) [])
    [:div.world-chat {:role "dialog" :aria-label "Chat" :ref #(set! (.-current el) %)}
     [:button.world-chat-close {:aria-label "Close the chat (Escape)" :data-tip "Close (Escape)"
                                :on-click close-chat!} "×"]
     [chat/chat-pane]]))

(defn- legend []
  [:div.world-legend
   [:span [:i.working] "working"] [:span [:i.waiting] "waiting on you"]
   [:span [:i.done] "done, not seen"] [:span [:i.idle] "idle"]
   [:span.hint "Click a screen to open its chat · Escape puts it back · click an island for a new chat there"]])

(defn- world-screen []
  [:div.world {:role "application" :aria-label "World"}
   [canvas]
   [:div.world-bar
    [:button.world-back {:on-click #(rf/dispatch [:world/toggle false]) :data-tip "Back to the chats (Alt+W)"} "← Chats"]
    [:span.world-title "World"]]
   [legend]
   (when @(rf/subscribe [:world-chat?])
     ^{:key (str @(rf/subscribe [:world/focus]))} [:f> chat-panel])])

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
