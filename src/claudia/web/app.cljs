(ns claudia.web.app
  (:require [re-frame.core :as rf]
            [claudia.web.admin]
            [claudia.web.native :as native]
            [claudia.web.presence :as presence]
            [reagent.dom.client :as rdc]
            [claudia.web.chalk :as chalk]
            [claudia.web.events]
            [claudia.web.fx]
            [claudia.web.md :as md]
            [claudia.web.subs]
            [claudia.web.viewport :as viewport]
            [claudia.web.views.shell :as shell]
            [claudia.web.ws :as ws]))

(defonce ^:private root (atom nil))

(defn- mount! []
  (rf/clear-subscription-cache!)
  (when-not @root
    (reset! root (rdc/create-root (js/document.getElementById "root"))))
  (rdc/render @root [:f> shell/app]))

(defn ^:dev/after-load reload! [] (mount!))

(defn init []
  (chalk/bake-all!)
  (viewport/install!)
  (md/install-copy!)
  (js/window.addEventListener "popstate" #(rf/dispatch [:nav/popstate]))
  (js/document.addEventListener
   "visibilitychange"
   #(when (= "visible" js/document.visibilityState)
      (ws/wake!)
      (rf/dispatch [:chat/check-seen])))
  (rf/dispatch-sync [:init])
  (presence/start!)
  (native/start!)
  (mount!))
