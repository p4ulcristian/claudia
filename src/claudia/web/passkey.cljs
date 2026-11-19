(ns claudia.web.passkey
  "Passkeys at the key broker, from the page (README decision 66): WebAuthn through
  @simplewebauthn/browser, the calls through claudia.web.api. Any passkey provider works (iCloud
  Keychain, 1Password, Google, …); the browser or the iPhone/iPad app asks for Face ID, Touch ID
  or a PIN each time. Only challenges, signatures and sealed values pass through here."
  (:require ["@simplewebauthn/browser" :as wa]
            [claudia.web.api :as api]))

(defn- sign [options] (wa/startAuthentication #js {:optionsJSON (clj->js options)}))

(defn- create [options] (wa/startRegistration #js {:optionsJSON (clj->js options)}))

(defn approve
  "Promise of {:challengeId :response}: one of the person's passkeys signed the broker's challenge
  for purpose ({:kind \"read\" …}, {:kind \"add-passkey\"}, {:kind \"remove-passkey\" :id})."
  [purpose]
  (-> (api/post-json "/hub/approve/options" purpose)
      (.then (fn [{:keys [challengeId options]}]
               (.then (sign options) (fn [r] {:challengeId challengeId :response (js->clj r)}))))))

(defn read-sealed
  "Promise of a secret ask's value, sealed by the broker to the ask's one-time key, once the person
  approved it with a passkey."
  [ask]
  (-> (approve {:kind "read" :ref (:ref ask) :pubkey (:pubkey ask) :request (:id ask)})
      (.then #(api/post-json "/hub/vault/read" %))
      (.then #(:sealed %))))

(defn listing
  "Promise of the person's passkeys [{:id :name :createdAt :usedAt}]."
  []
  (.then (api/fetch-json "/hub/passkeys") #(:passkeys %)))

(defn- allowed
  "Promise of what lets this person add a passkey: the gate's code for the first, else a passkey's
  approval."
  [code]
  (if code
    (js/Promise.resolve {:code code})
    (approve {:kind "add-passkey"})))

(defn add!
  "Promise of the new passkey: allowed (code or approval), made on this device, kept by the broker."
  [passkey-name code]
  (-> (allowed code)
      (.then #(api/post-json "/hub/passkeys/register/options" %))
      (.then (fn [{:keys [registrationId options]}]
               (.then (create options)
                      (fn [r] (api/post-json "/hub/passkeys/register"
                                             {:registrationId registrationId :response (js->clj r) :name passkey-name})))))
      (.then #(:passkey %))))

(defn remove!
  "Promise once the passkey id is removed (approved by one of the person's passkeys)."
  [id]
  (-> (approve {:kind "remove-passkey" :id id})
      (.then #(api/post-json "/hub/passkeys/remove" %))))

(defn problem
  "A short text for why a passkey step failed: cancelled, or the broker's word."
  [e]
  (if (= "NotAllowedError" (or (.-name e) (:name (ex-data e))))
    "Cancelled, or no passkey of yours here answered."
    (or (ex-message e) (str e))))
