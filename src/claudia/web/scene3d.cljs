(ns claudia.web.scene3d
  "The scene3d form in the picture: three.js and the view load on first use (the :scene3d module),
  so pictures without a 3D scene cost nothing. Explain on a part asks Claude in the open chat."
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [shadow.lazy :as lazy]))

(def ^:private lazy-view (lazy/loadable claudia.web.scene3d.view/scene3d))

(defonce ^:private loaded (r/atom nil))

(defn scene3d-form
  "data: the form's data; block: the block's id; ctx: {:server :chat :title} of the picture's chat
  (the scene reads real code from there; else the open chat's) and the block's name (a recording's
  file is named after it)."
  [data block & [ctx]]
  (let [oc @(rf/subscribe [:open-chat])
        ro @(rf/subscribe [:read-only?])
        ok @loaded]
    (cond
      (= ok :ready) [@lazy-view {:data data :block block :title (:title ctx) :chat (or (:chat ctx) (:chat oc))
                                 :server (or (:server ctx) (:server oc))
                            :live? (boolean (and (:chat oc) (not (:pending? oc)) (not ro)))}]
      (string? ok) [:p.f-note (str "The 3D view did not load: " ok)]
      :else (do (when-not ok
                  (reset! loaded :loading)
                  (-> (lazy/load lazy-view)
                      (.then #(reset! loaded :ready))
                      (.catch #(reset! loaded (str (or (.-message %) %))))))
                [:p.f-note "Loading the 3D view…"]))))

(rf/reg-event-fx
 :scene3d/explain
 ;; A message into the open chat, as if typed (the composer's draft stays as it is).
 (fn [{:keys [db]} [_ text]]
   (let [[server chat :as k] (:open db)]
     (when (and chat (not (contains? (:pending-new db) chat)))
       {:db (update-in db [:outbox k] (fnil conj []) text)
        :ws/send [server {:type "send" :chat chat :text text}]}))))
