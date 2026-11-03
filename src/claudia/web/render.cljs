(ns claudia.web.render
  "The picture on its own, for the runner's screenshots (claudia.slave.render): a headless browser
  opens the render page (window.claudiaRender), hands the scene to window.__draw and shoots it. No
  chat, socket or sidebar; the same block views as the page."
  (:require [reagent.dom.client :as rdc]
            [claudia.shared.protocol :as protocol]
            [claudia.web.chalk :as chalk]
            [claudia.web.views.picture :as picture]
            [claudia.web.views.sidebar :as sidebar]))

(defn- stage [scene]
  ;; As the user sees it, without the title (the board has none).
  [:section.render-stage
   [:div.stage {:style {:padding-bottom 18}}
    [:f> picture/picture-view {:scene scene :k :render :live? false :cols 0}]]])

(defn start! []
  (chalk/bake-all!)
  (let [root (rdc/create-root (js/document.getElementById "root"))]
    ;; scene: the Scene as JSON text; color: the chat's colour (it tints the board) or nil.
    (set! (.-__draw js/window)
          (fn [scene-json color]
            (set! (.-__drawn js/window) false)
            (let [el js/document.documentElement]
              (when-let [v (sidebar/chat-color-var color)]
                (.setAttribute el "data-chat" color)
                (.setProperty (.-style el) "--hue" v)))
            (rdc/render root [stage (protocol/read-json scene-json)])
            ;; Two frames: React has committed and the layout effects (graph layout) have started.
            (js/requestAnimationFrame
             #(js/requestAnimationFrame (fn [] (set! (.-__drawn js/window) true))))))))
