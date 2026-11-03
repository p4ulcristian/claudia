(ns claudia.hub.config
  "The servers and who may use which, from the hub's store (claudia.hub.store, HUB_DATA/hub.db). A
  hub.json (HUB_CONFIG, PROTOCOL.md) is imported once, into a store that has no server and no person
  yet; after that the admins' Servers and Access pages change the store, and the file is not read
  again."
  (:require ["fs" :as fs]
            [clojure.string :as str]
            [claudia.hub.store :as store]
            [claudia.shared.protocol :as protocol]))

(def server-name #"^[a-z][a-z0-9-]{0,30}$")

(def email-re #"^[^\s@,]+@[^\s@,]+$")

(defn- fail [& parts]
  (throw (js/Error. (apply str parts))))

(defn host-port
  "\"10.0.0.2:4540\", \"front.example.com:4540\" or \"[fd00::2]:4540\" -> [host port], else nil."
  [address]
  (let [[_ v6 host port] (when (string? address)
                           (re-matches #"^(?:\[([0-9A-Fa-f:.]+)\]|([A-Za-z0-9.-]+)):(\d{1,5})$" address))
        port (some-> port js/parseInt)]
    (when (and port (< 0 port 65536))
      [(or v6 host) port])))

(defn- read-server [s]
  (let [{:keys [name label address]} s]
    (when-not (and (string? name) (re-matches server-name name))
      (fail "servers: name " (pr-str name) " must be lower-case letters, digits and dashes, starting with a letter"))
    (when-not (host-port address)
      (fail "servers: " name ": address must be host:port"))
    {:name name
     :label (if (and (string? label) (not (str/blank? label))) label name)
     :address address}))

(defn- read-person [known p]
  (let [{:keys [email name admin servers]} p
        email (when (string? email) (str/lower-case (str/trim email)))]
    (when-not (and email (re-matches email-re email))
      (fail "people: " (pr-str (:email p)) " is not an email address"))
    (doseq [s servers :when (not (known s))]
      (js/console.warn (str "hub.json: a person lists server " (pr-str s) ", which is not in servers; ignored")))
    {:email email
     :name (if (and (string? name) (not (str/blank? name))) name "")
     :admin (true? admin)
     :servers (->> servers (filter known) distinct vec)}))

(defn parse
  "hub.json text -> {:servers [server …] (file order), :people [person …]}; throws on a broken file."
  [text]
  (let [data (protocol/read-json text)
        _ (when-not (map? data) (fail "the file must hold a JSON object"))
        servers (mapv read-server (:servers data))
        names (map :name servers)
        _ (when (not= (count names) (count (set names)))
            (fail "servers: a name is used twice"))
        people (mapv (partial read-person (set names)) (:people data))
        emails (map :email people)
        _ (when (not= (count emails) (count (set emails)))
            (fail "people: an email is listed twice"))]
    {:servers servers :people people}))

(defn- import! [file]
  (let [{:keys [servers people]} (parse (fs/readFileSync file "utf8"))]
    (store/transaction!
     (fn []
       ;; One by one, so added_at keeps the file's order.
       (doseq [s servers] (store/save-server! s))
       (doseq [p people]
         (store/save-person! p)
         (doseq [s (:servers p)] (store/set-access! (:email p) s true)))))
    (js/console.log (str "hub: imported " file ": " (count servers) " servers, " (count people) " people"))))

(defn start!
  "Opens the store in data-dir; imports hub.json (when given and there) into an empty store. A broken
  hub.json throws (the hub does not start half set up)."
  [data-dir import-file]
  (store/open! data-dir)
  (when (and import-file (store/empty-grid?) (fs/existsSync import-file))
    (import! import-file))
  (js/console.log (str "hub: store " data-dir "/hub.db: " (count (store/servers)) " servers, "
                       (count (store/people)) " people")))

(defn stop! [] (store/close!))

(defn- with-address
  "A server row with its address split for connecting: + :host :port."
  [s]
  (when s
    (let [[host port] (host-port (:address s))]
      (assoc s :host host :port port))))

(defn person
  "The person with that (lower-case) email: {:email :name :admin :servers [names]}, or nil."
  [email]
  (when-let [p (store/person email)]
    (assoc p
           :name (if (str/blank? (:name p)) email (:name p))
           :servers (mapv :name (store/allowed email)))))

(defn servers-of
  "The servers this person may use, in the order they were added."
  [p]
  (mapv with-address (store/allowed (:email p))))

(defn server-of
  "The server called `name` when this person may use it, else nil."
  [p name]
  (when (store/may? (:email p) name)
    (with-address (store/server name))))

(defn server
  "Any server by name (the hub's own calls: push, pokes)."
  [name]
  (with-address (store/server name)))

(defn all-servers [] (mapv with-address (store/servers)))
