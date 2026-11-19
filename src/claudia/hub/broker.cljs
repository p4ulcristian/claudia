(ns claudia.hub.broker
  "The hub's door to the key broker (README decision 66): a signed-in person's passkey and secret
  calls go on to the broker's unix socket (HUB_BROKER_SOCKET) with the email the gate's login
  vouched for put in the body; whatever email the page sent is replaced. The hub holds no
  1Password token and no passkey: it only carries challenges, signatures and sealed values."
  (:require [claudia.node.env :refer [env]]
            [claudia.node.http :as io]))

(defn on? [] (some? (env "HUB_BROKER_SOCKET" nil)))

(def ^:private paths
  "Hub path and method -> the broker's path."
  {["GET" "/hub/passkeys"] "/passkeys/list"
   ["POST" "/hub/passkeys/register/options"] "/passkeys/register/options"
   ["POST" "/hub/passkeys/register"] "/passkeys/register"
   ["POST" "/hub/passkeys/remove"] "/passkeys/remove"
   ["POST" "/hub/approve/options"] "/approve/options"
   ["POST" "/hub/vault/read"] "/vault/read"
   ["POST" "/hub/vault/standing"] "/vault/standing"
   ["GET" "/hub/keys"] "/keys/list"
   ["GET" "/hub/keys/mine"] "/keys/mine"
   ["POST" "/hub/keys/grants"] "/keys/grants"})

(defn call?
  "A path the broker answers."
  [pathname]
  (boolean (some (fn [[_ p]] (= p pathname)) (keys paths))))

(defn target
  "The broker's path for a method and hub path, or nil."
  [method pathname]
  (get paths [method pathname]))

(defn- forward! [^js req ^js res path email]
  (-> (if (= "GET" (.-method req)) (js/Promise.resolve {}) (io/read-json req (* 64 1024)))
      (.then #(io/socket-json (env "HUB_BROKER_SOCKET") path (assoc % :email email) 120000))
      (.then (fn [{:keys [status body]}] (io/send-json res status body)))
      (.catch (fn [e]
                (js/console.error (str "hub: key broker " path ": " (.-message e)))
                (io/send-json res 502 {:error "The key broker does not answer."})))))

(defn call
  "A signed-in person's call to the broker."
  [^js req ^js res pathname email]
  (let [path (target (.-method req) pathname)]
    (cond
      (not (on?)) (io/send-json res 503 {:error "No key broker on this hub (HUB_BROKER_SOCKET)."})
      (not path) (io/send-json res 405 {:error "method not allowed"})
      :else (forward! req res path email))))
