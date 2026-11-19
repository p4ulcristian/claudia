(ns claudia.broker.access
  "Who may use which key (README decision 66): admins see the vault's items (titles, no values)
  and every tick, and change ticks in batches, each batch approved with the admin's passkey.
  Admins are the broker's own list (`broker.js admin <email>` on the gate). Everyone lists the keys
  they may use, by name, for list_secrets (README decision 77)."
  (:require [claudia.broker.routes :as routes]
            [claudia.broker.rules :as rules]
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

(defn mine
  "The keys this person may use (admins: every key; others: the ticked ones), each with its fields'
  labels and ready references for run_with_secret (README decision 77). Names only, so no passkey."
  [cfg {:keys [email]}]
  (when-not (vault/ready?) (throw (ex-info "The broker has no 1Password token: it reads no secrets." {:status 503})))
  (-> (vault/items (:vault cfg))
      (.then (fn [items]
               (let [usable (rules/usable-items (store/admin? email) (store/granted-items email) items)]
                 (.then (vault/labels (:vault cfg) (map :id usable))
                        (fn [{:keys [all labels]}] (rules/key-list (:vault cfg) all usable labels))))))))

(defn grants!
  "An admin's approved batch of ticks, applied at once."
  [cfg {:keys [email challengeId response]}]
  (admin! email)
  (-> (routes/approve! cfg email challengeId "grants" response)
      (.then (fn [{:keys [changes]}]
               (doseq [c changes] (store/set-grant! c))
               (js/console.log (str "broker: " (count changes) " key access changes by " email))
               {:grants (store/grants)}))))
