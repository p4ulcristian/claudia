(ns claudia.node.crypto
  "Hashes the hub and the slave programs take, from node:crypto."
  (:require ["node:crypto" :as crypto]))

(defn sha256-hex
  "SHA-256 of s (text or bytes) as lower-case hex."
  [s]
  (-> (crypto/createHash "sha256") (.update s) (.digest "hex")))
