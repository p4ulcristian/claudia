(ns claudia.broker.passkeys
  "A person's passkeys at the key broker (README decision 66): listing them, adding one (the first
  with a code made on the gate, every later one approved by a passkey they already have) and
  removing one (approved by a passkey)."
  (:require ["node:crypto" :as crypto]
            [claudia.broker.routes :as routes]
            [claudia.broker.rules :as rules]
            [claudia.broker.store :as store]
            [claudia.broker.webauthn :as webauthn]))

;; Open registrations: id -> {:email :challenge :purpose {:kind "register"} :expires}.
(defonce ^:private registering (atom {}))

(defn- fail [status text] (throw (ex-info text {:status status})))

(defn listing
  "A person's passkeys as the page lists them."
  [{:keys [email]}]
  {:passkeys (mapv rules/passkey-view (store/passkeys-of email))})

(defn- allowed-to-add
  "Promise that resolves once this person may add a passkey: by code when they have none, else by
  a passkey's approval of add-passkey."
  [cfg {:keys [email code challengeId response]}]
  (if (empty? (store/passkeys-of email))
    (js/Promise.resolve (routes/use-code! email code))
    (routes/approve! cfg email challengeId "add-passkey" response)))

(defn register-options
  "Options to make a new passkey, once the person is allowed to add one."
  [cfg {:keys [email] :as body}]
  (-> (allowed-to-add cfg body)
      (.then (fn [_] (webauthn/registration-options cfg email (store/passkeys-of email))))
      (.then (fn [^js o]
               (let [id (.toString (crypto/randomBytes 18) "base64url")]
                 (swap! registering rules/open-challenge id
                        {:email email :challenge (.-challenge o) :purpose {:kind "register"}} (js/Date.now))
                 {:registrationId id :options o})))))

(defn- take-registration! [id email]
  (let [[e rest-open] (rules/take-challenge @registering id email "register" (js/Date.now))]
    (reset! registering rest-open)
    (or e (fail 403 "That registration is not open any more. Start again."))))

(defn register!
  "Keeps the passkey the person just made, once it verifies against the registration's challenge."
  [cfg {:keys [email registrationId response] passkey-name :name}]
  (let [entry (take-registration! registrationId email)]
    (-> (webauthn/verify-registration cfg response (:challenge entry))
        (.catch (fn [e] (fail 400 (ex-message e))))
        (.then (fn [k]
                 (when (store/passkey (:id k)) (fail 409 "That passkey is already kept."))
                 (let [row (assoc k :email email :name (rules/passkey-name passkey-name))]
                   (store/add-passkey! row (js/Date.now))
                   (js/console.log (str "broker: passkey \"" (:name row) "\" added for " email))
                   {:passkey (rules/passkey-view (store/passkey (:id k)))}))))))

(defn remove!
  "Drops the passkey a passkey's approval named."
  [cfg {:keys [email challengeId response]}]
  (-> (routes/approve! cfg email challengeId "remove-passkey" response)
      (.then (fn [{:keys [id]}]
               (store/remove-passkey! email id)
               (js/console.log (str "broker: passkey " id " removed for " email))
               {:removed id}))))
