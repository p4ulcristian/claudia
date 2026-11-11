(ns claudia.web.world.view
  "The world's canvas (the lazily loaded :world module): starts the engine in its box, hands it the
  servers and every chat's row whenever they change, and the robot to follow while its chat is open."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
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
  "How much of the window's right side the chat panel covers (its width and its margin), 0 without it."
  []
  (if-let [^js el (js/document.querySelector ".world-chat")]
    ;; offsetLeft, not the client rect: the panel may be mid-flight (transformed).
    (- js/window.innerWidth (.-offsetLeft el))
    0))

(defn world-view []
  (let [host (h/use-ref nil)
        [w error] (use-engine! host)
        servers @(rf/subscribe [:server-list])
        rows @(rf/subscribe [:all-rows])
        focus @(rf/subscribe [:world/focus])]
    (h/use-effect (fn [] (when w (engine/update! w servers rows)) nil) [w servers rows])
    (h/use-effect (fn [] (when w (engine/focus! w focus (panel-width))) nil) [w focus])
    (h/use-effect (fn []
                    (when w (reset! link/api {:screen-rect #(engine/screen-rect w %)}))
                    #(reset! link/api nil))
                  [w])
    [:div.world-canvas {:ref #(set! (.-current host) %)}
     (when error [:p.world-error (str "The 3D world did not start: " error)])]))
