(ns claudia.web.app
  (:require [re-frame.core :as rf]
            [claudia.web.admin]
            [claudia.web.alerts :as alerts]
            [claudia.web.back :as back]
            [claudia.web.swipe :as swipe]
            [claudia.web.tip :as tip]
            [claudia.web.copy-pill :as copy-pill]
            [claudia.web.selection :as selection]
            [claudia.web.native :as native]
            [claudia.web.order :as order]
            [claudia.web.bookmarks :as bookmarks]
            [claudia.web.right :as right]
            [claudia.web.folders]
            [claudia.web.presence :as presence]
            [claudia.web.tabs :as tabs]
            [reagent.dom.client :as rdc]
            [claudia.web.chalk :as chalk]
            [claudia.web.chatops]
            [claudia.web.events]
            [claudia.web.claudia-chat]
            [claudia.web.extras]
            [claudia.web.fx]
            [claudia.web.md :as md]
            [claudia.web.pictures :as pictures]
            [claudia.web.render :as render]
            [claudia.web.speech :as speech]
            [claudia.web.subs]
            [claudia.web.viewport :as viewport]
            [claudia.web.voice-back :as voice-back]
            [claudia.web.words]
            [claudia.web.views.shell :as shell]
            [claudia.web.ws :as ws]
            [shadow.loader :as loader]))

(defonce ^:private root (atom nil))

(defn- mount! []
  (rf/clear-subscription-cache!)
  (when-not @root
    (reset! root (rdc/create-root (js/document.getElementById "root"))))
  (rdc/render @root [:f> shell/app]))

(defn ^:dev/after-load reload! [] (mount!))

(defn- start-app! []
  (chalk/bake-all!)
  (viewport/install!)
  (swipe/install!)
  (tip/install!)
  (copy-pill/install!)
  (selection/install!)
  (alerts/install!)
  (md/install-copy!)
  (pictures/install-keys!)
  (speech/start!)
  (js/window.addEventListener "popstate" #(when-not (back/took-pop? %) (rf/dispatch [:nav/popstate])))
  (ws/install! #(rf/dispatch [:chat/check-seen]))
  (rf/dispatch-sync [:init])
  (presence/start!)
  (order/start!)
  (bookmarks/start!)
  (right/start!)
  (tabs/start!)
  (voice-back/start!)
  (native/start!)
  (mount!))

(defn- script-tag-modules!
  "The lazy modules (scene3d, shiki, chart) load as script tags: the module loader's default fetches
  them and evals the text, which the page's policy forbids (claudia.shared.csp)."
  []
  (doto (loader/getModuleLoader)
    (.setSourceUrlInjection false)
    (.setUseScriptTags true)))

(defn init []
  (script-tag-modules!)
  ;; The runner's render page (screenshots of a picture) is this build in another mode.
  (if (.-claudiaRender js/window)
    (render/start!)
    (start-app!)))
