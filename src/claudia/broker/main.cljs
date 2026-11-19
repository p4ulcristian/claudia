(ns claudia.broker.main
  "Claudia's key broker (README decision 66): on the gate, in its own container and user beside the
  hub, the only program holding the 1Password service account's token and the passkeys' public
  keys. It listens on a unix socket only the hub's container reaches (BROKER_SOCKET).

  `node broker.js` serves; `node broker.js enroll <email>` prints a one-time code that lets that
  person add their first passkey (run on the gate: `docker exec claudia-broker /nodejs/bin/node
  /app/broker.js enroll <email>`)."
  (:require ["node:fs" :as fs]
            ["node:http" :as http]
            [claudia.broker.passkeys :as passkeys]
            [claudia.broker.routes :as routes]
            [claudia.broker.rules :as rules]
            [claudia.broker.store :as store]
            [claudia.broker.vault :as vault]
            [claudia.node.env :refer [env]]
            [claudia.node.http :as io]
            [claudia.node.sealbox :as sealbox]))

(defn origin-host
  "The host name of an https://host origin (the passkeys' relying party), or nil."
  [origin]
  (try (not-empty (.-hostname (js/URL. origin))) (catch :default _ nil)))

(defn settings
  "The broker's settings from the environment (env-of: name, default -> value). The relying party
  is the origin's host unless BROKER_RP_ID names another."
  [env-of]
  {:socket (env-of "BROKER_SOCKET" nil)
   :data (env-of "BROKER_DATA" "/data")
   :token-file (env-of "BROKER_OP_TOKEN_FILE" nil)
   :vault (env-of "BROKER_VAULT" "Claudia")
   :rp-id (env-of "BROKER_RP_ID" (origin-host (env-of "BROKER_ORIGIN" nil)))
   :rp-name "Claudia"
   :origin (env-of "BROKER_ORIGIN" nil)
   :commit (env-of "BROKER_COMMIT" nil)})

(defn problems
  "What keeps the broker from serving (missing settings), as texts."
  [{:keys [socket rp-id origin]}]
  (cond-> []
    (not socket) (conj "BROKER_SOCKET is not set.")
    (not rp-id) (conj "BROKER_RP_ID (the hub's host name) is not set, and BROKER_ORIGIN names none.")
    (not origin) (conj "BROKER_ORIGIN (the hub's https://host) is not set.")))

(def ^:private routes-by-path
  {"/status" (fn [cfg _] {:vault (:vault cfg) :reads (vault/ready?)})
   "/passkeys/list" (fn [_ body] (passkeys/listing body))
   "/passkeys/register/options" passkeys/register-options
   "/passkeys/register" passkeys/register!
   "/passkeys/remove" passkeys/remove!
   "/approve/options" routes/approve-options
   "/vault/read" routes/read!})

(defn- answer! [^js res e]
  (let [status (or (:status (ex-data e)) 500)]
    (when (= 500 status) (js/console.error (str "broker: " (ex-message e))))
    (io/send-json res status {:error (if (= 500 status) "The broker failed; see its log." (ex-message e))})))

(defn- on-request [cfg ^js req ^js res]
  (if-let [f (and (= "POST" (.-method req)) (get routes-by-path (.-url req)))]
    (-> (io/read-json req (* 64 1024))
        (.then (fn [body]
                 (if (rules/email? (:email body))
                   (f cfg body)
                   (throw (ex-info "The hub must say whose call this is." {:status 400})))))
        (.then #(io/send-json res 200 %))
        (.catch #(answer! res %)))
    (io/send-json res 404 {:error "not found"})))

(defn- listen! [{:keys [socket] :as cfg}]
  (when (fs/existsSync socket) (fs/unlinkSync socket))
  (let [^js server (http/createServer (partial on-request cfg))]
    (.on server "error" (fn [e] (js/console.error (str "broker: " (.-message e))) (js/process.exit 1)))
    (doseq [sig ["SIGTERM" "SIGINT"]]
      (js/process.on sig (fn [_] (.close server #(do (store/close!) (js/process.exit 0))))))
    (.listen server socket
             (fn []
               ;; The hub's container shares this socket's group; nobody else may connect.
               (fs/chmodSync socket (js/parseInt "660" 8))
               (js/console.log (str "Claudia key broker on " socket ", vault \"" (:vault cfg) "\""))))))

(defn- enroll! [{:keys [data]} email]
  (if-not (rules/email? email)
    (do (js/console.error "usage: broker.js enroll <email, lower case>") (js/process.exit 2))
    (do (store/open! data)
        (println (str "Code for " email " (30 minutes, once): " (routes/make-code! email)))
        (store/close!))))

(defn- serve! [cfg]
  (let [missing (problems cfg)]
    (when (seq missing)
      (js/console.error (str "broker: " (apply str (interpose " " missing))))
      (js/process.exit 1))
    (store/open! (:data cfg))
    (when-let [p (:problem (vault/start! (:token-file cfg) (:commit cfg)))]
      (js/console.warn (str "broker: " p)))
    (.then sealbox/ready #(listen! cfg))))

(defn main [& args]
  (let [cfg (settings env)]
    (if (= "enroll" (first args))
      (enroll! cfg (second args))
      (serve! cfg))))
