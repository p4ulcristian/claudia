(ns claudia.web.passkey
  "Passkeys at the key broker, from the page (README decision 66): WebAuthn through
  @simplewebauthn/browser, the calls through claudia.web.api. Any passkey provider works (iCloud
  Keychain, 1Password, Google, …); the browser or the iPhone/iPad app asks for Face ID, Touch ID
  or a PIN each time. Only challenges, signatures and sealed values pass through here.

  Every step comes in two halves: prepare (fetch the broker's options) and a tap that calls the
  passkey with options already in hand. WebKit (Safari, the iPhone/iPad app) opens a passkey only
  straight from a tap; a fetch between the tap and the passkey call makes it refuse, which the
  page used to show as \"Cancelled\"."
  (:require ["@simplewebauthn/browser" :as wa]
            [claudia.web.api :as api]))

(defn prepare-approval
  "Promise of {:challengeId :options} for purpose ({:kind \"read\" …}, {:kind \"add-passkey\"},
  {:kind \"remove-passkey\" :id}): fetch before the tap."
  [purpose]
  (api/post-json "/hub/approve/options" purpose))

(defn sign!
  "Call from the tap itself: the passkey signs prepared's challenge. Promise of {:challengeId
  :response}."
  [{:keys [challengeId options]}]
  (.then (wa/startAuthentication #js {:optionsJSON (clj->js options)})
         (fn [r] {:challengeId challengeId :response (js->clj r)})))

(defn chat-id
  "The chat an hour of trust is bound to, from the page's [server chat] pair."
  [[server chat]]
  (str server "/" chat))

(defn read-ask-purpose
  "The approval a secret ask needs, in chat k ([server chat])."
  [ask k]
  {:kind "read" :ref (:ref ask) :pubkey (:pubkey ask) :request (:id ask) :chat (chat-id k)})

(defn read-standing
  "Promise of the value sealed to the ask's key when chat k's hour of trust for this key still
  runs (no tap); rejects with status 404 when it needs a passkey."
  [ask k]
  (.then (api/post-json "/hub/vault/standing" (dissoc (read-ask-purpose ask k) :kind)) #(:sealed %)))

(defn read-sealed!
  "Call from the tap itself: the passkey approves the prepared read; promise of the value sealed by
  the broker to the ask's one-time key."
  [prepared]
  (-> (sign! prepared)
      (.then #(api/post-json "/hub/vault/read" %))
      (.then #(:sealed %))))

(defn prepare-root
  "Promise of {:challengeId :options} for root on chat k ([server chat]): action \"on\" (the root
  switch) or \"allow\" (one \"Run as root\" card, ask its nonce). The server's front makes a one-time
  nonce, the broker a challenge bound to it (README decision 79); fetch before the tap."
  [[s c] action ask]
  (-> (api/post-json (api/url s "root/challenge" nil) {:chat c :action action :ask ask})
      (.then (fn [{:keys [nonce profile]}]
               (prepare-approval {:kind "root" :nonce nonce :profile profile :chat c :action action :ask ask})))))

(defn root-grant!
  "Call from the tap itself: the passkey approves the prepared root; promise of the broker's
  {:grant :signature}, which the front checks."
  [prepared]
  (.then (sign! prepared) #(api/post-json "/hub/root/grant" %)))

(defn listing
  "Promise of the person's passkeys [{:id :name :createdAt :usedAt}]."
  []
  (.then (api/fetch-json "/hub/passkeys") #(:passkeys %)))

(defn prepare-add
  "Promise of {:registrationId :options} for a new passkey, let in by the gate's code (first
  passkey) or by a signed add-passkey approval ({:challengeId :response})."
  [allowed-by]
  (api/post-json "/hub/passkeys/register/options" allowed-by))

(defn create!
  "Call from the tap itself: this device makes the passkey; promise of the passkey kept."
  [{:keys [registrationId options]} passkey-name]
  (-> (wa/startRegistration #js {:optionsJSON (clj->js options)})
      (.then (fn [r] (api/post-json "/hub/passkeys/register"
                                    {:registrationId registrationId :response (js->clj r) :name passkey-name})))
      (.then #(:passkey %))))

(defn remove!
  "Call from the tap itself: the passkey approves the prepared removal; promise once removed."
  [prepared]
  (.then (sign! prepared) #(api/post-json "/hub/passkeys/remove" %)))

(defn key-access
  "Promise of an admin's view of key access {:items :grants :admins}; rejects 403 for others."
  []
  (api/fetch-json "/hub/keys"))

(defn save-grants!
  "Call from the tap itself: the admin's passkey approves the prepared batch; promise of the
  ticks as saved."
  [prepared]
  (-> (sign! prepared)
      (.then #(api/post-json "/hub/keys/grants" %))
      (.then #(:grants %))))

(defn problem
  "A short text for why a passkey step failed: the browser's or the broker's own words."
  [e]
  (let [n (or (.-name e) (:name (ex-data e)))
        m (or (ex-message e) (str e))]
    (if (= "NotAllowedError" n)
      (str "The passkey step was cancelled or refused (" m ").")
      m)))
