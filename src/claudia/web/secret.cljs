(ns claudia.web.secret
  "Sealing a pasted secret to a secret ask's one-time public key (libsodium crypto_box_seal), so the
  hub and the page only ever carry ciphertext. Nothing here keeps or logs the value."
  (:require ["libsodium-wrappers" :as sodium]))

(defn- from-b64 [s]
  ;; PROTOCOL.md says base64; accept the URL-safe alphabet too.
  (try (.from_base64 sodium s (.. sodium -base64_variants -ORIGINAL))
       (catch :default _
         (.from_base64 sodium s (.. sodium -base64_variants -URLSAFE_NO_PADDING)))))

(defn seal
  "A promise of the text sealed to pubkey (base64), as standard base64."
  [text pubkey]
  (-> (.-ready sodium)
      (.then (fn []
               (let [msg (.from_string sodium text)
                     sealed (.crypto_box_seal sodium msg (from-b64 pubkey))]
                 ;; Wipe the plaintext bytes; the JS string itself can't be wiped.
                 (.fill msg 0)
                 (.to_base64 sodium sealed (.. sodium -base64_variants -ORIGINAL)))))))
