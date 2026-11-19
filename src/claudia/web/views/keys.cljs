(ns claudia.web.views.keys
  "Settings → Keys (README decision 66): for the key broker's admins, the vault's keys (titles
  only) with a tick per person for who may use each. Admins use every key. Ticks change on the
  page first; Save sends the batch, approved with the admin's passkey on its own tap
  (claudia.web.passkey). Hidden for everyone else (the broker answers 403)."
  (:require [claudia.web.passkey :as passkey]
            [claudia.web.views.pure.keys :as pk]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(defn- load! [st]
  (-> (passkey/key-access)
      (.then #(swap! st assoc :view % :error nil))
      (.catch #(swap! st assoc :hidden (= 403 (:status (ex-data %))) :error (passkey/problem %)))))

(defn- fail! [st e] (swap! st assoc :busy false :step nil :error (passkey/problem e)))

(defn- prepare-save! [st]
  (swap! st assoc :busy true :error nil)
  (-> (passkey/prepare-approval {:kind "grants" :changes (pk/batch (:changes @st))})
      (.then #(swap! st assoc :busy false :step %))
      (.catch #(fail! st %))))

(defn- save! [st]
  (swap! st assoc :busy true :error nil)
  (-> (passkey/save-grants! (:step @st))
      (.then #(swap! st (fn [s] (-> s (assoc-in [:view :grants] %) (assoc :busy false :step nil :changes {})))))
      (.catch #(fail! st %))))

(defn- tick [st saved {:keys [id]} {:keys [email]}]
  (let [changed (contains? (:changes @st) [id email])]
    [:td {:class (when changed "changed")}
     [:input {:type "checkbox" :checked (pk/on? saved (:changes @st) id email)
              :disabled (or (:busy @st) (:step @st))
              :on-change #(swap! st update :changes (fn [c] (pk/toggle saved c id email)))}]]))

(defn- grid [st people]
  (let [{:keys [items grants]} (:view @st)
        saved (pk/ticked grants)]
    [:table.st-keys
     [:thead [:tr [:th "Key"] (for [p people] ^{:key (:email p)} [:th (or (:name p) (:email p))])]]
     [:tbody (for [item items]
               ^{:key (:id item)}
               [:tr [:td (:title item)] (for [p people] ^{:key (:email p)} [tick st saved item p])])]]))

(defn- actions [st]
  (let [{:keys [busy step changes]} @st]
    (cond
      step [:div.st-token-form
            [:button.primary {:type "button" :disabled busy :on-click #(save! st)} "Confirm with a passkey"]
            [:button {:type "button" :disabled busy :on-click #(swap! st assoc :step nil)} "Cancel"]]
      (seq changes) [:div.st-token-form
                     [:button.primary {:type "button" :disabled busy :on-click #(prepare-save! st)}
                      (str "Save " (count changes) (if (= 1 (count changes)) " change" " changes"))]
                     [:button {:type "button" :disabled busy :on-click #(swap! st assoc :changes {})} "Undo"]])))

(defn- keys-body [st]
  (let [{:keys [view error]} @st
        people (pk/others (:people @(rf/subscribe [:me])) (:admins view))]
    [:<>
     (cond (nil? view) [:p.small "Checking…"]
           (empty? (:items view)) [:p.small "The vault has no keys yet."]
           :else [:<> [:p.small "Admins use every key. Tick who else may use each."] [grid st people] [actions st]])
     (when error [:p.ask-error error])]))

(defn keys-section
  "The section, for the key broker's admins only."
  []
  (r/with-let [st (r/atom {:view nil :changes {} :busy false :step nil :hidden false})
               _ (load! st)]
    (when (and (:vault @(rf/subscribe [:me])) (not (:hidden @st)))
      [:section.st-section.st-keys-section
       [:h3 "Keys " [:span.muted.small "who may use which"]]
       [keys-body st]])))
