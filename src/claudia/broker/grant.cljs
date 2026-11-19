(ns claudia.broker.grant
  "The key broker's root grants (README decision 79): after a person's passkey approves root for a
  chat, the broker signs a grant with its own ed25519 key, which servers pin (FRONT_BROKER_GRANT_KEY)
  so a hacked hub can make none. The private key is BROKER_DATA/root-grant.pem (0600), made on first
  use; `broker.js grant-key` prints the public key to pin."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [claudia.node.crypto :as c]
            [claudia.shared.protocol :as p]
            [claudia.shared.root-grant :as rg]))

(defn- key-file [data] (path/join data "root-grant.pem"))

(defn private-key!
  "The broker's grant key (PKCS#8 PEM) from BROKER_DATA, made (0600) when there is none."
  [data]
  (let [f (key-file data)]
    (when-not (fs/existsSync f)
      (fs/writeFileSync f (:private (c/ed25519-keypair)) #js {:mode (js/parseInt "600" 8) :flag "wx"}))
    (fs/readFileSync f "utf8")))

(defn public-key!
  "The public key servers pin (SPKI DER, base64)."
  [data]
  (c/ed25519-public (private-key! data)))

(defn signed
  "{:grant (JSON text) :signature} for an approved root purpose."
  [data purpose email now]
  (let [text (p/write-json (rg/grant purpose email now))]
    {:grant text :signature (c/ed25519-sign (private-key! data) text)}))
