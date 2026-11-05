(ns claudia.web.render
  "The picture on its own, for the runner's screenshots (claudia.slave.render): a headless browser
  opens the render page (window.claudiaRender), hands the scene to window.__draw and shoots it. No
  chat, socket or sidebar; the same block views as the page.

  The same page makes a 3D scene's video (claudia.slave.video): window.__video(data, clips) shows
  that scene alone, filling the window, on a stepped clock; once it is up, window.__maker begins
  it and hands out its frames (claudia.web.scene3d.maker)."
  (:require [reagent.dom.client :as rdc]
            [claudia.shared.narration :as narration]
            [claudia.shared.protocol :as protocol]
            [claudia.web.chalk :as chalk]
            [claudia.web.scene3d :as scene3d]
            [claudia.web.views.picture :as picture]
            [claudia.web.views.sidebar :as sidebar]))

(defn- stage [scene]
  ;; As the user sees it, without the title (the board has none).
  [:section.render-stage
   [:div.stage {:style {:padding-bottom 18}}
    [:f> picture/picture-view {:scene scene :k :render :live? false :cols 0}]]])

(defn- video-stage
  "One 3D scene filling the window, making a video: seconds, its clips' lengths; on-video gets the maker."
  [data seconds on-video]
  [:section.video-stage
   [scene3d/scene3d-form data "video" {:fill true :video {:seconds seconds} :on-video on-video}]])

(defn- tint!
  "The board in the chat's colour (none: the default)."
  [color]
  (let [el js/document.documentElement]
    (when-let [v (sidebar/chat-color-var color)]
      (.setAttribute el "data-chat" color)
      (.setProperty (.-style el) "--hue" v))))

(defn- video! [root data-json clips-json color]
  (tint! color)
  (js/Promise.
   (fn [done]
     (rdc/render root [video-stage (protocol/read-json data-json)
                       (narration/seconds-by-line (protocol/read-json clips-json))
                       (fn [m] (set! (.-__maker js/window) m) (done true))]))))

(defn start! []
  (chalk/bake-all!)
  (let [root (rdc/create-root (js/document.getElementById "root"))]
    ;; scene: the Scene as JSON text; color: the chat's colour (it tints the board) or nil.
    (set! (.-__draw js/window)
          (fn [scene-json color]
            (set! (.-__drawn js/window) false)
            (tint! color)
            (rdc/render root [stage (protocol/read-json scene-json)])
            ;; Two frames: React has committed and the layout effects (graph layout) have started.
            (js/requestAnimationFrame
             #(js/requestAnimationFrame (fn [] (set! (.-__drawn js/window) true))))))
    ;; data: the scene3d form's data as JSON text; clips: its narrator's clips' lengths as JSON text.
    (set! (.-__video js/window) (fn [data-json clips-json color] (video! root data-json clips-json color)))))
