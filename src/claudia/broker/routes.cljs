(ns claudia.broker.routes
  "The key broker's calls (README decision 66, PROTOCOL.md Key broker). Only the hub reaches them,
  over the broker's unix socket, and every body carries the email the hub vouched for. The broker
  trusts that email only to pick whose passkeys may sign: nothing is read, added or removed without
  a fresh passkey signature (or, for a person's first passkey, a code made on the gate)."
  (:require ["node:crypto" :as crypto]
            [claudia.broker.grant :as grant]
            [claudia.broker.rules :as rules]
            [claudia.broker.store :as store]
            [claudia.broker.vault :as vault]
            [claudia.broker.webauthn :as webauthn]))

;; Open challenges: id -> {:email :challenge :purpose :expires} (claudia.broker.rules/open-challenge).
(defonce ^:private pending (atom {}))

;; Hours of trust after a tap: [email chat item-id] -> until (ms). In memory only: a restart asks again.
(defonce ^:private standing (atom {}))

(defn- fail [status text] (throw (ex-info text {:status status})))

(defn- new-id [] (.toString (crypto/randomBytes 18) "base64url"))

(defn- open! [email challenge purpose]
  (let [id (new-id)]
    (swap! pending rules/open-challenge id {:email email :challenge challenge :purpose purpose} (js/Date.now))
    id))

(defn- take! [id email kind]
  (let [[e rest-pending] (rules/take-challenge @pending id email kind (js/Date.now))]
    (reset! pending rest-pending)
    (or e (fail 403 "That approval is not open any more. Start again."))))

(defn- signing-passkey [email response]
  (let [k (some-> (:id response) store/passkey)]
    (if (and k (= email (:email k))) k (fail 403 "That passkey is not one of yours here."))))

(defn approve!
  "Promise of the approved purpose: the challenge was open, this person's, for kind, and one of
  their passkeys signed it with user verification. Used once either way."
  [cfg email challenge-id kind response]
  (let [entry (take! challenge-id email kind)
        k (signing-passkey email response)]
    (-> (webauthn/verify-authentication cfg response (:challenge entry) k)
        (.then (fn [counter]
                 (store/used-passkey! (:id k) counter (js/Date.now))
                 (js/console.log (str "broker: " kind " approved by " email " with passkey \"" (:name k) "\""))
                 (:purpose entry)))
        (.catch (fn [e] (if (:status (ex-data e)) (throw e) (fail 403 (ex-message e))))))))

(defn- passkey-of! [email id]
  (or (some #(when (= id (:id %)) %) (store/passkeys-of email)) (fail 404 "No such passkey of yours.")))

(defn check-use!
  "Promise that resolves when email may use the key ref names (admins: every key; others: the
  keys ticked for them); rejects 403 otherwise, or 404 when the vault has no such item."
  [cfg email ref]
  (.then (vault/items (:vault cfg))
         (fn [items]
           (let [item (rules/find-item items (rules/ref-item (:vault cfg) ref))]
             (cond
               (nil? item) (fail 404 (str "The vault \"" (:vault cfg) "\" has no item " (rules/ref-item (:vault cfg) ref) "."))
               (not (rules/may-use? (store/admin? email) (store/granted-items email) (:id item)))
               (fail 403 (str "You may not use " (:title item) ": an admin ticks it for you in Settings → Keys."))
               :else item)))))

(defn- allowed-purpose
  "Promise of purpose once its own rules hold: a read needs the key ticked, grants an admin, a
  removal one's own passkey."
  [cfg email p]
  (case (:kind p)
    "read" (.then (check-use! cfg email (:ref p)) (fn [_] p))
    "grants" (if (store/admin? email) (js/Promise.resolve p) (js/Promise.reject (ex-info "Admins only." {:status 403})))
    "remove-passkey" (do (passkey-of! email (:id p)) (js/Promise.resolve p))
    (js/Promise.resolve p)))

(defn approve-options
  "Options to sign one approval: a secret read, adding or removing a passkey, or key access."
  [cfg {:keys [email] :as body}]
  (let [p (rules/purpose (:vault cfg) body)
        keys-of (store/passkeys-of email)]
    (cond
      (:error p) (fail 400 (:error p))
      (empty? keys-of) (fail 409 "You have no passkey here yet: add one in Settings first.")
      :else
      (-> (allowed-purpose cfg email p)
          (.then (fn [_] (webauthn/authentication-options cfg keys-of)))
          (.then (fn [^js o] {:challengeId (open! email (.-challenge o) p) :options o}))))))

(defn- seal-for! [email {:keys [ref pubkey request]} how]
  (js/console.log (str "broker: read " ref " for request " request " by " email " (" how ")"))
  (.then (vault/read-sealed ref pubkey) (fn [sealed] {:sealed sealed})))

(defn read!
  "A secret read the person approved (and may still make): the value sealed to the runner's
  one-time key. The tap also lets that chat use that key for an hour without asking."
  [cfg {:keys [email challengeId response]}]
  (when-not (vault/ready?) (fail 503 "The broker has no 1Password token: it reads no secrets."))
  (-> (approve! cfg email challengeId "read" response)
      (.then (fn [{:keys [ref chat] :as p}]
               (.then (check-use! cfg email ref)
                      (fn [item]
                        (swap! standing rules/add-standing (rules/standing-key email chat (:id item)) (js/Date.now))
                        (seal-for! email p "passkey")))))))

(defn read-standing!
  "A read without a tap, when this chat's hour of trust for this key still runs; 404 otherwise
  (the page then asks for the passkey)."
  [cfg {:keys [email] :as body}]
  (when-not (vault/ready?) (fail 503 "The broker has no 1Password token: it reads no secrets."))
  (let [p (rules/read-purpose (:vault cfg) body)]
    (when (:error p) (fail 400 (:error p)))
    (.then (check-use! cfg email (:ref p))
           (fn [item]
             (if (rules/standing? @standing (rules/standing-key email (:chat p) (:id item)) (js/Date.now))
               (seal-for! email p "within the hour")
               (fail 404 "This chat needs a passkey tap for this key."))))))

(defn root-grant!
  "Root for one chat the person approved with a passkey (README decision 79): the grant signed
  with the broker's key, for the server's front to check. The broker knows no profiles: the front
  checks that the email is the chat's owner."
  [cfg {:keys [email challengeId response]}]
  (.then (approve! cfg email challengeId "root" response)
         (fn [{:keys [profile chat action] :as p}]
           (js/console.log (str "broker: root " action " for " profile "/" chat " by " email))
           (grant/signed (:data cfg) p email (js/Date.now)))))

(defn- code-hash [code] (.digest (.update (crypto/createHash "sha256") (str code)) "hex"))

(defn- same-hash? [a b]
  (and (= (count a) (count b)) (crypto/timingSafeEqual (js/Buffer.from a) (js/Buffer.from b))))

(defn check-code!
  "A person's first passkey: their enrollment code must match. Wrong tries count; a spent or
  expired code goes. A matching code stays until the passkey it lets in is kept
  (claudia.broker.passkeys/register!), so a cancelled or failed passkey step does not use it up."
  [email code]
  (let [row (store/code-of email)]
    (case (rules/code-check row (js/Date.now) (and row (same-hash? (:hash row) (code-hash code))))
      :ok :code
      :wrong (do (store/wrong-code! email) (fail 403 "That code is wrong."))
      :spent (do (store/drop-code! email) (fail 403 "That code has ended: make a new one on the gate."))
      :none (fail 403 "No enrollment code for you: make one on the gate (broker.js enroll <email>)."))))

(defn make-code!
  "A new enrollment code for email (printed once on the gate); only its hash is kept."
  [email]
  (let [code (.toString (crypto/randomBytes 8) "hex")]
    (store/put-code! email (code-hash code) (+ (js/Date.now) rules/code-ms))
    code))
