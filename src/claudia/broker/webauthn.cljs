(ns claudia.broker.webauthn
  "Passkeys for the key broker (README decision 66), all through @simplewebauthn/server: the
  options a page passes to navigator.credentials, and the checks of what comes back. Any passkey
  provider is accepted (no attestation, no provider list); user verification (Face ID, Touch ID,
  a PIN) is required every time."
  (:require ["@simplewebauthn/server" :as wa]
            ["@simplewebauthn/server/helpers" :refer [isoBase64URL]]
            ["node:crypto" :as crypto]))

(defn user-id
  "The WebAuthn user handle for a person: the first 16 bytes of SHA-256 of their email, so the
  same person has the same handle on every device and nothing readable is in it."
  [email]
  (js/Uint8Array. (.subarray (.digest (.update (crypto/createHash "sha256") (str "claudia-broker:" email))) 0 16)))

(defn- descriptor [{:keys [id transports]}]
  #js {:id id :transports (clj->js (vec transports))})

(defn registration-options
  "Promise of the options (JSON-ready) to make a passkey for email; passkeys the person already has
  are excluded, so one provider does not get a second."
  [{:keys [rp-id rp-name]} email passkeys]
  (wa/generateRegistrationOptions
   #js {:rpName rp-name :rpID rp-id :userName email :userID (user-id email) :userDisplayName email
        :attestationType "none"
        :excludeCredentials (into-array (map descriptor passkeys))
        :authenticatorSelection #js {:residentKey "preferred" :userVerification "required"}}))

(defn verify-registration
  "Promise of the new passkey {:id :publicKey :counter :transports}; rejects when the answer does
  not match challenge, this origin and relying party, or carries no user verification."
  [{:keys [rp-id origin]} response challenge]
  (-> (wa/verifyRegistrationResponse
       #js {:response (clj->js response) :expectedChallenge challenge :expectedOrigin origin
            :expectedRPID rp-id :requireUserVerification true})
      (.then (fn [^js v]
               (when-not (.-verified v) (throw (js/Error. "The passkey could not be verified.")))
               (let [^js c (.. v -registrationInfo -credential)]
                 {:id (.-id c) :publicKey (.fromBuffer isoBase64URL (.-publicKey c))
                  :counter (.-counter c) :transports (vec (or (some-> (.-transports c) js->clj) []))})))))

(defn authentication-options
  "Promise of the options (JSON-ready) to sign with one of passkeys."
  [{:keys [rp-id]} passkeys]
  (wa/generateAuthenticationOptions
   #js {:rpID rp-id :userVerification "required" :allowCredentials (into-array (map descriptor passkeys))}))

(defn verify-authentication
  "Promise of the passkey's new counter; rejects unless response is passkey's signature over
  challenge, from this origin and relying party, with user verification."
  [{:keys [rp-id origin]} response challenge {:keys [id publicKey counter transports]}]
  (-> (wa/verifyAuthenticationResponse
       #js {:response (clj->js response) :expectedChallenge challenge :expectedOrigin origin
            :expectedRPID rp-id :requireUserVerification true
            :credential #js {:id id :publicKey (.toBuffer isoBase64URL publicKey) :counter counter
                             :transports (clj->js (vec transports))}})
      (.then (fn [^js v]
               (when-not (.-verified v) (throw (js/Error. "The passkey's signature did not verify.")))
               (.. v -authenticationInfo -newCounter)))))
