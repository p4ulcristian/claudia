(ns claudia.web.secret
  "Sealing a pasted secret to a secret ask's one-time public key (libsodium crypto_box_seal), so the
  hub and the page only ever carry ciphertext. libsodium loads on the first seal (the :secret
  module, claudia.web.secret.sodium), which hands its sealer over as it loads, as the :shiki module
  does (claudia.web.shiki): this namespace is also in the test build, which has no modules."
  (:require [shadow.loader :as loader]))

(defonce ^:private provided (atom nil))

(defn provide!
  "Called once by the :secret module as it loads: its function sealing text to a public key."
  [seal-fn]
  (reset! provided seal-fn))

(defn seal
  "A promise of the text sealed to pubkey (base64), as standard base64, the :secret module loaded first."
  [text pubkey]
  (-> (js/Promise. (fn [ok fail] (.addCallbacks (loader/load "secret") ok fail)))
      (.then (fn [_] (@provided text pubkey)))))
