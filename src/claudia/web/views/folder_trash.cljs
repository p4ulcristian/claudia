(ns claudia.web.views.folder-trash
  "The Trash of one server (README decision 63): each trashed folder with where it was, when, its
  size and when it goes for good, Restore and Delete now; Empty Trash with the total size. Deleting
  for good asks first."
  (:require [reagent.hooks :as h]
            [claudia.web.folders :as folders]
            [claudia.web.format :as format]
            [claudia.web.views.confirm :as confirm]))

(defn- use-trash
  "The Trash as the runner has it, {:items :total} (nil until asked), its last error, and reload!."
  [server]
  (let [[trash set-trash] (h/use-state nil)
        [error set-error] (h/use-state nil)
        reload! (fn [] (-> (folders/trashed! server) (.then (fn [t] (set-error nil) (set-trash t))) (.catch #(set-error (.-message %)))))]
    (h/use-effect (fn [] (reload!) js/undefined) [server])
    [trash error reload! set-error]))

(defn- item-row [{:keys [id path at size expires]} {:keys [restore! set-ask]}]
  [:div.fd-trash-item
   [:div.fd-trash-text
    [:span.mono (format/tilde path)]
    [:span.muted (str "Trashed " (format/when-text at) " · " (if (number? size) (format/bytes-text size) "size unknown")
                      " · goes " (format/when-text expires))]]
   [:button.fd-btn {:on-click #(restore! id)} "Restore"]
   [:button.fd-btn.danger {:on-click #(set-ask {:id id :path path})} "Delete now…"]])

(defn- purge-ask [{:keys [ask total purge! set-ask]}]
  [confirm/card {:title (if (:id ask) "Delete this folder for good?" "Empty the Trash?")
                 :yes (if (:id ask) "Delete for good" "Empty Trash")
                 :on-yes (fn [] (set-ask nil) (purge! (:id ask)))
                 :on-no #(set-ask nil)}
   [:p (if (:id ask)
         [:<> [:span.mono (:path ask)] " goes for good; it cannot be brought back."]
         (str "Every folder in the Trash (" (format/bytes-text total) ") goes for good."))]])

(defn view
  "The Trash of server; on-restored gets the path a folder went back to."
  [{:keys [server on-restored]}]
  (let [[trash error reload! set-error] (use-trash server)
        [ask set-ask] (h/use-state nil)
        after #(-> % (.then reload!) (.catch (fn [e] (set-error (.-message e)))))
        acts {:restore! #(after (.then (folders/act! server "restore" {:id %}) (fn [r] (on-restored (:path r)))))
              :set-ask set-ask}
        {:keys [items total]} trash]
    [:div.fd-detail.fd-trash
     [:h2.fd-title "Trash"]
     [:p.muted "Folders stay here 7 days, then go for good."]
     (when error [:p.fd-warn error])
     (cond (nil? trash) [:p.muted "…"]
           (empty? items) [:p.muted "The Trash is empty."]
           :else [:<> (for [it items] ^{:key (:id it)} [item-row it acts])
                  [:button.fd-btn.danger {:on-click #(set-ask {})} (str "Empty Trash (" (format/bytes-text total) ")")]])
     (when ask
       [purge-ask {:ask ask :total total :set-ask set-ask
                   :purge! #(after (folders/act! server "purge" (if % {:id %} {})))}])]))
