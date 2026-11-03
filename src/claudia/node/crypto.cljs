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
