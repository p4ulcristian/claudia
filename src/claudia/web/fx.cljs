(ns claudia.web.fx
  "Effects that leave the page: HTTP, the address bar, local storage, sealing secrets."
  (:require [re-frame.core :as rf]
            [claudia.web.api :as api]
            [claudia.web.db :as db]
            [claudia.web.secret :as secret]
            [claudia.web.speech :as speech]
            [claudia.web.ws :as ws]))

;; ---- routes: / and /c/<server>/<chat> ----

(defn parse-path
  "A path -> [server chat] or nil."
  [path]
  (let [[_ s c] (re-matches #"/c/([^/]+)/([^/]+)/?" (or path ""))]
    (when (and s c) [(js/decodeURIComponent s) (js/decodeURIComponent c)])))

(defn path-of [[server chat]]
  (if (and server chat)
    (str "/c/" (js/encodeURIComponent server) "/" (js/encodeURIComponent chat))
    "/"))

(rf/reg-fx
 :history/push
 (fn [k]
   (let [p (path-of k)]
     (when (not= p js/location.pathname)
       (js/history.pushState nil "" p)))))

(rf/reg-fx
 :history/replace
 (fn [k] (js/history.replaceState nil "" (path-of k))))

(rf/reg-fx :store (fn [[k v]] (db/store! k v)))
(rf/reg-fx :speech/say speech/say!)
(rf/reg-fx :speech/stop (fn [_] (speech/stop!)))

;; ---- HTTP (same origin; the gate's login cookie goes along) ----

(rf/reg-fx
 :http/get
 (fn [{:keys [url on-ok on-fail]}]
   (-> (api/fetch-json url {})
       (.then #(rf/dispatch (conj on-ok %)))
       (.catch #(rf/dispatch (conj on-fail (ex-message %)))))))

(rf/reg-fx
 :keys/answer
 ;; list_secrets: the keys this person may use (names and references, never values) from the key
 ;; broker, sent back to the asking runner (README decision 77).
 (fn [{:keys [server chat ask]}]
   (-> (api/fetch-json "/hub/keys/mine")
       (.then #(ws/send! server {:type "keys" :chat chat :ask ask :answer %}))
       (.catch #(ws/send! server {:type "keys" :chat chat :ask ask :error (ex-message %)})))))

;; ---- secrets: the value is never in app-db; only ciphertext passes through here ----

(defn- secret-done [server chat ask-id sealed]
  (if (ws/send! server {:type "secret" :chat chat :ask ask-id :sealed sealed})
    (rf/dispatch [:ask/state ask-id {:busy true :sent true}])
    (rf/dispatch [:ask/state ask-id {:error "Not connected to the chat's server. Try again."}])))

(rf/reg-fx
 :secret/send
 ;; The value the key broker sealed to the ask's key (the card's passkey tap, README decision 66).
 (fn [{:keys [server chat ask sealed]}]
   (if sealed
     (secret-done server chat (:id ask) sealed)
     (rf/dispatch [:ask/state (:id ask) {:error "The key broker gave no answer."}]))))

(rf/reg-fx
 :secret/seal
 ;; Paste: sealed here in the page, to the ask's one-time key; the text goes no further.
 (fn [{:keys [server chat ask text]}]
   (-> (secret/seal text (:pubkey ask))
       (.then #(secret-done server chat (:id ask) %))
       (.catch (fn [_] (rf/dispatch [:ask/state (:id ask) {:error "Couldn't seal the text."}]))))))
