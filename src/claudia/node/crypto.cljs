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
