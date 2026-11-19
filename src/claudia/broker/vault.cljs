(ns claudia.broker.vault
  "The key broker's 1Password reads (README decision 66): the official SDK with a read-only service
  account limited to one vault. The token comes from BROKER_OP_TOKEN_FILE, read once at start and
  kept only in this process. Values are sealed to the asking runner's one-time key right away and
  never logged or stored."
  (:require ["@1password/sdk" :as op]
            ["node:fs" :as fs]
            [clojure.string :as str]
            [claudia.broker.rules :as rules]
            [claudia.node.sealbox :as sealbox]))

(defonce ^:private client (atom nil))

(defn start!
  "Makes the SDK client from the token file; {:problem text} when the file is missing or empty
  (the broker then answers reads with 503 and still lists passkeys)."
  [token-file version]
  (let [token (try (some-> token-file (fs/readFileSync "utf8") str/trim) (catch :default _ nil))]
    (if (seq token)
      (do (reset! client (-> (op/createClient #js {:auth token :integrationName "Claudia key broker"
                                                   :integrationVersion (rules/integration-version version)})
                             ;; A token 1Password refuses must not stop the broker: reads then fail.
                             (.catch (fn [e]
                                       (js/console.error (str "broker: 1Password refused the token: " (ex-message e)))
                                       nil))))
          {})
      {:problem "BROKER_OP_TOKEN_FILE is not set or empty: the broker reads no secrets."})))

(defn ready? [] (some? @client))

;; The vault's items {:at ms :items [{:id :title}]}: listed again after a minute.
(defonce ^:private item-cache (atom nil))

(defn- live-client []
  (.then @client (fn [c] (or c (throw (ex-info "1Password refused the broker's token; see its log." {:status 503}))))))

(defn- list-items [vault-title]
  (-> (live-client)
      (.then (fn [^js c]
               (.then (.list (.-vaults c))
                      (fn [vs]
                        (let [^js v (some #(when (= vault-title (.-title ^js %)) %) vs)]
                          (when-not v (throw (ex-info (str "The broker cannot see the vault " vault-title ".") {:status 503})))
                          (.list (.-items c) (.-id v)))))))
      (.then (fn [items] (mapv (fn [^js i] {:id (.-id i) :title (.-title i)}) items)))))

(defn items
  "Promise of the vault's items [{:id :title}], titles sorted; no values."
  [vault-title]
  (let [{:keys [at] cached :items} @item-cache]
    (if (and cached (< (- (js/Date.now) at) 60000))
      (js/Promise.resolve cached)
      (.then (list-items vault-title)
             (fn [xs] (let [sorted (vec (sort-by :title xs))]
                        (reset! item-cache {:at (js/Date.now) :items sorted})
                        sorted))))))

(defn read-sealed
  "Promise of the value at ref sealed to pubkey (base64). The plain value lives only inside this
  function's promise chain."
  [ref pubkey]
  (-> @client
      (.then (fn [^js c]
               (when-not c (throw (ex-info "1Password refused the broker's token; see its log." {:status 503})))
               (.resolve (.-secrets c) ref)))
      (.then (fn [value] (sealbox/seal value pubkey)))
      ;; 1Password's own errors (no such item, no access) never hold a value: passed on as they are.
      (.catch (fn [e]
                (throw (if (:status (ex-data e)) e (ex-info (str "1Password: " (ex-message e)) {:status 502})))))))
