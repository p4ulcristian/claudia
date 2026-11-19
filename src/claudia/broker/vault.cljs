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

;; The vault's items {:at ms :vault-id :items [{:id :title :updated}]}: listed again after a minute.
(defonce ^:private item-cache (atom nil))

;; Each item's field labels, item id -> {:updated :labels} (claudia.broker.rules/item-labels): fetched
;; again only when the item changed. Labels only: never a value.
(defonce ^:private label-cache (atom {}))

(defn- live-client []
  (.then @client (fn [c] (or c (throw (ex-info "1Password refused the broker's token; see its log." {:status 503}))))))

(defn- overview [^js i] {:id (.-id i) :title (.-title i) :updated (str (.-updatedAt i))})

(defn- list-items [vault-title]
  (.then (live-client)
         (fn [^js c]
           (.then (.list (.-vaults c))
                  (fn [vs]
                    (let [^js v (some #(when (= vault-title (.-title ^js %)) %) vs)]
                      (when-not v (throw (ex-info (str "The broker cannot see the vault " vault-title ".") {:status 503})))
                      (.then (.list (.-items c) (.-id v))
                             (fn [items] {:vault-id (.-id v) :items (mapv overview items)}))))))))

(defn- overviews
  "Promise of {:vault-id :items [{:id :title :updated}]}, titles sorted, from the cache within a minute."
  [vault-title]
  (let [{:keys [at] :as cached} @item-cache]
    (if (and cached (< (- (js/Date.now) at) 60000))
      (js/Promise.resolve cached)
      (.then (list-items vault-title)
             (fn [listed]
               (let [fresh (-> listed (update :items #(vec (sort-by :title %))) (assoc :at (js/Date.now)))]
                 (reset! item-cache fresh)
                 fresh))))))

(defn- titles [overview-items] (mapv #(select-keys % [:id :title]) overview-items))

(defn items
  "Promise of the vault's items [{:id :title}], titles sorted; no values."
  [vault-title]
  (.then (overviews vault-title) (fn [o] (titles (:items o)))))

(defn- js-labels
  "An SDK item's labels: only ids, titles and section ids are read from it, never a value."
  [^js item]
  (rules/item-labels
   {:fields (mapv (fn [^js f] {:id (.-id f) :title (.-title f) :section (not-empty (.-sectionId f))}) (.-fields item))
    :sections (mapv (fn [^js s] {:id (.-id s) :title (.-title s)}) (.-sections item))}))

(defn- sdk-error [e]
  (if (:status (ex-data e)) e (ex-info (str "1Password: " (ex-message e)) {:status 502})))

(defn- fetched-labels
  "item id -> {:updated :labels} from a getAll answer; an item 1Password did not return is left out."
  [^js answer updated]
  (into {} (for [^js one (.-individualResponses answer)
                 :let [^js item (.-content one)]
                 :when item]
             [(.-id item) {:updated (get updated (.-id item)) :labels (js-labels item)}])))

(defn- fetch-labels
  "Promise of item id -> {:updated :labels} for the overviews given, in one getAll."
  [vault-id stale]
  (if (empty? stale)
    (js/Promise.resolve {})
    (-> (live-client)
        (.then (fn [^js c] (.getAll (.-items c) vault-id (clj->js (map :id stale)))))
        (.then #(fetched-labels % (into {} (map (juxt :id :updated)) stale)))
        ;; 1Password's own errors never hold a value: passed on as they are.
        (.catch #(throw (sdk-error %))))))

(defn- cached-labels [ids]
  (into {} (for [id ids :let [l (get-in @label-cache [id :labels])] :when l] [id l])))

(defn- stale-of
  "The overviews of the wanted items whose labels are not cached for their last change."
  [all ids]
  (let [want (set ids)]
    (filter #(and (want (:id %)) (not= (:updated %) (get-in @label-cache [(:id %) :updated]))) all)))

(defn labels
  "Promise of {:all [{:id :title}] :labels {item id -> labels}}: every item's title, and the field
  labels and section names of the items with ids (claudia.broker.rules/item-labels). No values."
  [vault-title ids]
  (.then (overviews vault-title)
         (fn [{:keys [vault-id] all :items}]
           (.then (fetch-labels vault-id (stale-of all ids))
                  (fn [fresh]
                    (swap! label-cache #(merge (select-keys % (map :id all)) fresh))
                    {:all (titles all) :labels (cached-labels ids)})))))

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
      (.catch #(throw (sdk-error %)))))
