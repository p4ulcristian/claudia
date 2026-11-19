(ns claudia.broker.access
  "Who may use which key (README decision 66): admins see the vault's items (titles, no values)
  and every tick, and change ticks in batches, each batch approved with the admin's passkey.
  Admins are the broker's own list (`broker.js admin <email>` on the gate)."
  (:require [claudia.broker.routes :as routes]
            [claudia.broker.store :as store]
            [claudia.broker.vault :as vault]))

(defn- admin! [email]
  (when-not (store/admin? email) (throw (ex-info "Admins only." {:status 403}))))

(defn listing
  "An admin's view: the vault's items, every tick and the admins."
  [cfg {:keys [email]}]
  (admin! email)
  (.then (vault/items (:vault cfg))
         (fn [items] {:items items :grants (store/grants) :admins (store/admins)})))

(defn grants!
  "An admin's approved batch of ticks, applied at once."
  [cfg {:keys [email challengeId response]}]
  (admin! email)
  (-> (routes/approve! cfg email challengeId "grants" response)
      (.then (fn [{:keys [changes]}]
               (doseq [c changes] (store/set-grant! c))
               (js/console.log (str "broker: " (count changes) " key access changes by " email))
               {:grants (store/grants)}))))
