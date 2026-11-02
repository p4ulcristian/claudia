(ns claudia.web.fx
  "Effects that leave the page: HTTP, the address bar, local storage, sealing secrets."
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [claudia.shared.protocol :as protocol]
            [claudia.web.db :as db]
            [claudia.web.secret :as secret]
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

;; ---- HTTP (same origin; the gate's login cookie goes along) ----

(defn- fetch-json [url opts]
  (-> (js/fetch url (clj->js (merge {:credentials "same-origin"
                                     :headers {"accept" "application/json"}}
                                    opts)))
      (.then (fn [^js r]
               (-> (.text r)
                   (.then (fn [t]
                            (let [body (when-not (str/blank? t)
                                         (try (protocol/read-json t) (catch :default _ nil)))]
                              (if (.-ok r)
                                body
                                (throw (ex-info (or (:error body) (str "HTTP " (.-status r)))
                                                {:status (.-status r)})))))))))))

(rf/reg-fx
 :http/get
 (fn [{:keys [url on-ok on-fail]}]
   (-> (fetch-json url {})
       (.then #(rf/dispatch (conj on-ok %)))
       (.catch #(rf/dispatch (conj on-fail (ex-message %)))))))

;; ---- secrets: the value is never in app-db; only ciphertext passes through here ----

(defn- secret-done [server chat ask-id sealed]
  (if (ws/send! server {:type "secret" :chat chat :ask ask-id :sealed sealed})
    (rf/dispatch [:ask/state ask-id {:busy true :sent true}])
    (rf/dispatch [:ask/state ask-id {:error "Not connected to the chat's server. Try again."}])))

(rf/reg-fx
 :secret/read
 ;; Allow: the person's vault server reads 1Password and seals the value to the ask's key.
 (fn [{:keys [server chat ask]}]
   (-> (fetch-json (str "/s/" (js/encodeURIComponent (:vault ask)) "/api/secret/read")
                   {:method "POST"
                    :headers {"content-type" "application/json" "accept" "application/json"}
                    :body (protocol/write-json {:ref (:ref ask) :pubkey (:pubkey ask) :request (:id ask)})})
       (.then (fn [{:keys [sealed error]}]
                (if sealed
                  (secret-done server chat (:id ask) sealed)
                  (rf/dispatch [:ask/state (:id ask) {:error (or error "The vault server gave no answer.")}]))))
       (.catch #(rf/dispatch [:ask/state (:id ask) {:error (ex-message %)}])))))

(rf/reg-fx
 :secret/seal
 ;; Paste: sealed here in the page, to the ask's one-time key; the text goes no further.
 (fn [{:keys [server chat ask text]}]
   (-> (secret/seal text (:pubkey ask))
       (.then #(secret-done server chat (:id ask) %))
       (.catch (fn [_] (rf/dispatch [:ask/state (:id ask) {:error "Couldn't seal the text."}]))))))
