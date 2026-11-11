(ns claudia.web.world.view
  "The world's canvas (the lazily loaded :world module): starts the engine in its box, hands it the
  servers and every chat's row whenever they change, every server's numbers (the Systems popup's
  calls, every 2 s while the world is open) and the robot to follow while its chat is open."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.hooks :as hooks]
            [claudia.web.world.engine :as engine]
            [claudia.web.world.link :as link]))

(defn- use-engine!
  "Starts the engine in host; [world error] as state. Stopped on unmount, even mid-start. A lost WebGPU
  device starts it again on WebGL 2."
  [^js host-ref]
  (let [[w set-w] (h/use-state nil)
        [error set-error] (h/use-state nil)
        [webgl? set-webgl] (h/use-state false)]
    (h/use-effect
     (fn []
       (let [gone? (atom false)
             started (-> (engine/start! (.-current host-ref)
                                        {:on-pick #(rf/dispatch [:world/pick %])
                                         :force-webgl? webgl?
                                         :on-lost #(do (set-w nil) (set-webgl true))})
                         (.then (fn [x] (if @gone? (do (engine/stop! x) nil) (do (set-w x) x))))
                         (.catch (fn [e] (set-error (str (or (.-message e) e))) nil)))]
         (fn []
           (reset! gone? true)
           (.then started #(some-> % engine/stop!)))))
     [webgl?])
    [w error]))

(defn- panel-width
  "How much of the window's right side the panel covers (its width and its margin), 0 without it."
  []
  (if-let [^js el (js/document.querySelector ".world-panel")]
    ;; offsetLeft, not the client rect: the panel may be mid-flight (transformed).
    (- js/window.innerWidth (.-offsetLeft el))
    0))

(defn- stats-poller
  "Asks one machine's numbers every 2 s (url: /hub/servers/<name>/system, or /hub/gate/system) and
  hands them, or the failure, to the world under id."
  [w id url]
  (let [error (hooks/use-poll {:on? true :url-of (constantly url) :ms 2000
                               :on-answer #(engine/stats! w id {:sample %})}
                              [w url])]
    (h/use-effect (fn [] (when error (engine/stats! w id {:error error})) nil) [w error])
    nil))

(defn- pollers
  "A stats-poller per server, and the gate's for admins."
  [w]
  (let [servers @(rf/subscribe [:server-list])
        admin? (:admin @(rf/subscribe [:me]))]
    [:<>
     (for [{n :name} servers]
       ^{:key n} [:f> stats-poller w n (str "/hub/servers/" (js/encodeURIComponent n) "/system")])
     (when admin? [:f> stats-poller w "gate" "/hub/gate/system"])]))

(defn world-view []
  (let [host (h/use-ref nil)
        [w error] (use-engine! host)
        servers @(rf/subscribe [:server-list])
        rows @(rf/subscribe [:all-rows])
        focus @(rf/subscribe [:world/focus])
        panel @(rf/subscribe [:world/panel])]
    (h/use-effect (fn [] (when w (engine/update! w servers rows)) nil) [w servers rows])
    (h/use-effect (fn [] (when w (engine/panel! w {:follow focus :inset (when panel (panel-width))})) nil)
                  [w focus panel])
    (h/use-effect (fn []
                    (when w (reset! link/api {:screen-rect #(engine/screen-rect w %) :board-rect #(engine/board-rect w %)
                                              :screen-image #(engine/screen-image w %) :board-image #(engine/board-image w %)}))
                    #(reset! link/api nil))
                  [w])
    [:div.world-canvas {:ref #(set! (.-current host) %)}
     (when w [pollers w])
     (when error [:p.world-error (str "The 3D world did not start: " error)])]))
