(ns claudia.hub.seal
  "Alert texts sealed for one phone: the chat's title and the start of Claude's reply or question
  travel as AES-256-GCM ciphertext under the 32-byte key the app made, so Apple's push servers see
  only ciphertext and ids. The app's notification extension opens it; the plain alert beside it
  (\"Claudia\", \"New message\") shows if it can't. node:crypto does the cipher."
  (:require ["node:crypto" :as crypto]))

(defn device-key
  "The app's key as it registered it (base64 of 32 bytes), or nil."
  [b64]
  (when (and (string? b64) (re-matches #"^[A-Za-z0-9+/]{43}=$" (.trim b64))
             (= 32 (.-length (js/Buffer.from (.trim b64) "base64"))))
    (.trim b64)))

(defn seal
  "base64 of nonce (12) || ciphertext || tag (16): a fresh random nonce each time, no AAD."
  [key-b64 text]
  (let [key (js/Buffer.from key-b64 "base64")
        _ (when-not (= 32 (.-length key)) (throw (js/Error. "not a 32-byte key")))
        nonce (crypto/randomBytes 12)
        c (crypto/createCipheriv "aes-256-gcm" key nonce)
        sealed (js/Buffer.concat #js [(.update c (js/JSON.stringify (clj->js text)) "utf8") (.final c)])]
    (.toString (js/Buffer.concat #js [nonce sealed (.getAuthTag c)]) "base64")))
