(ns claudia.node.crypto
  "Hashes the hub and the slave programs take, from node:crypto."
  (:require ["node:crypto" :as crypto]))

(defn sha256-hex
  "SHA-256 of s (text or bytes) as lower-case hex."
  [s]
  (-> (crypto/createHash "sha256") (.update s) (.digest "hex")))

(defn token-hash
  "A Claude token's name where its limits are kept (a runner's store, the hub's): the start of its
  SHA-256, never the token."
  [token]
  (subs (sha256-hex (str token)) 0 16))

(defn server-key
  "The key the hub proves itself with to one server's front (X-Claudia-Hub-Key): HMAC-SHA256 of
  the server's name under the hub's secret, base64url. Each server's differs, so one server's key
  opens no other front."
  [hub-secret server-name]
  (-> (crypto/createHmac "sha256" hub-secret) (.update (str "claudia-front:" server-name)) (.digest "base64url")))

(defn same-secret?
  "Whether two texts are equal, compared in constant time (their SHA-256s, so lengths leak nothing)."
  [a b]
  (and (string? a) (string? b)
       (crypto/timingSafeEqual (-> (crypto/createHash "sha256") (.update a) (.digest))
                               (-> (crypto/createHash "sha256") (.update b) (.digest)))))

;; ---- ed25519 (root grants, README decision 79) ----

(defn ed25519-keypair
  "A new ed25519 key pair: {:private (PKCS#8 PEM) :public (SPKI DER, base64)}."
  []
  (let [^js kp (crypto/generateKeyPairSync "ed25519")]
    {:private (.export (.-privateKey kp) #js {:type "pkcs8" :format "pem"})
     :public (.toString (.export (.-publicKey kp) #js {:type "spki" :format "der"}) "base64")}))

(defn ed25519-public
  "The public key (SPKI DER, base64) of a PKCS#8 PEM private key."
  [private-pem]
  (.toString (.export (crypto/createPublicKey private-pem) #js {:type "spki" :format "der"}) "base64"))

(defn ed25519-sign
  "The ed25519 signature (base64url) of text under a PKCS#8 PEM private key."
  [private-pem text]
  (.toString (crypto/sign nil (js/Buffer.from (str text) "utf8") private-pem) "base64url"))

(defn ed25519-valid?
  "Whether signature (base64url) is the ed25519 signature of text under public (SPKI DER, base64);
  false for any malformed part."
  [public text signature]
  (try
    (let [k (crypto/createPublicKey #js {:key (js/Buffer.from (str public) "base64") :format "der" :type "spki"})]
      (boolean (crypto/verify nil (js/Buffer.from (str text) "utf8") k (js/Buffer.from (str signature) "base64url"))))
    (catch :default _ false)))
