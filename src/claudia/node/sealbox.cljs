(ns claudia.node.sealbox
  "Sealing a value to a one-time public key (libsodium crypto_box_seal), so only the holder of
  the matching private key opens it: the key broker seals 1Password values to a runner's request
  key (PROTOCOL.md, Secrets). Standard base64 with padding on the wire."
  (:require ["libsodium-wrappers" :as sodium]))

(def ready "Resolves once libsodium can be used." (.-ready sodium))

(defn b64 [^js raw] (.toString (js/Buffer.from raw) "base64"))

(defn unb64 [s] (js/Uint8Array. (js/Buffer.from (str s) "base64")))

(defn seal
  "The value sealed to a base64 public key, as base64. Call after ready."
  [value pubkey]
  (b64 (sodium/crypto_box_seal value (unb64 pubkey))))
