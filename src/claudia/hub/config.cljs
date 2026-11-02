(ns claudia.hub.config
  "hub.json (PROTOCOL.md): the servers and who may use which. Read at start (a broken file stops the
  hub) and again whenever the file changes (a broken change is logged and the last good one kept)."
  (:require ["fs" :as fs]
            [clojure.string :as str]
            [claudia.shared.protocol :as protocol]))

(def server-name #"^[a-z][a-z0-9-]{0,30}$")

(defonce ^:private current (atom nil))

(defn- fail [& parts]
  (throw (js/Error. (apply str parts))))

(defn- host-port
  "\"10.0.0.2:4540\", \"front.example.com:4540\" or \"[fd00::2]:4540\" -> [host port]."
  [address]
  (let [[_ v6 host port] (when (string? address)
                           (re-matches #"^(?:\[([0-9A-Fa-f:.]+)\]|([A-Za-z0-9.-]+)):(\d{1,5})$" address))
        port (some-> port js/parseInt)]
    (when (and port (< 0 port 65536))
      [(or v6 host) port])))

(defn- read-server [s]
  (let [{:keys [name label address]} s
        [host port] (host-port address)]
    (when-not (and (string? name) (re-matches server-name name))
      (fail "servers: name " (pr-str name) " must be lower-case letters, digits and dashes, starting with a letter"))
    (when-not host
      (fail "servers: " name ": address must be host:port"))
    {:name name
     :label (if (and (string? label) (not (str/blank? label))) label name)
     :address address
     :host host
     :port port}))

(defn- read-person [known p]
  (let [{:keys [email name admin servers]} p
        email (when (string? email) (str/lower-case (str/trim email)))]
    (when-not (and email (re-matches #"^[^\s@,]+@[^\s@,]+$" email))
      (fail "people: " (pr-str (:email p)) " is not an email address"))
    (doseq [s servers :when (not (known s))]
      (js/console.warn (str "hub.json: a person lists server " (pr-str s) ", which is not in servers; ignored")))
    {:email email
     :name (if (and (string? name) (not (str/blank? name))) name email)
     :admin (true? admin)
     :servers (->> servers (filter known) distinct vec)}))

(defn parse
  "hub.json text -> {:servers [server …] (file order), :by-name {name server}, :people {email person}}."
  [text]
  (let [data (protocol/read-json text)
        _ (when-not (map? data) (fail "the file must hold a JSON object"))
        servers (mapv read-server (:servers data))
        names (map :name servers)
        _ (when (not= (count names) (count (set names)))
            (fail "servers: a name is used twice"))
        known (set names)
        people (mapv (partial read-person known) (:people data))
        emails (map :email people)
        _ (when (not= (count emails) (count (set emails)))
            (fail "people: an email is listed twice"))]
    {:servers servers
     :by-name (zipmap names servers)
     :people (zipmap emails people)}))

(defn- load! [file]
  (let [cfg (parse (fs/readFileSync file "utf8"))]
    (reset! current cfg)
    (js/console.log (str "hub.json: " (count (:servers cfg)) " servers, " (count (:people cfg)) " people"))))

(defn start!
  "Reads the file (throws when it is missing or broken) and follows its changes. Polling the file's
  stat, not inotify: it sees a file replaced by an editor or by a bind mount's directory alike."
  [file]
  (load! file)
  (fs/watchFile file #js {:interval 2000 :persistent false}
                (fn [^js now ^js before]
                  (when (or (not= (.-mtimeMs now) (.-mtimeMs before))
                            (not= (.-ino now) (.-ino before))
                            (not= (.-size now) (.-size before)))
                    (try (load! file)
                         (catch :default e
                           (js/console.error (str "hub.json: not reloaded, the last good one stays: " (.-message e)))))))))

(defn stop! [file]
  (fs/unwatchFile file))

(defn person
  "The person with that (lower-case) email, or nil."
  [email]
  (get (:people @current) email))

(defn servers-of
  "The servers this person may use, in hub.json's order."
  [p]
  (let [mine (set (:servers p))]
    (filterv #(mine (:name %)) (:servers @current))))

(defn server-of
  "The server called `name` when this person may use it, else nil."
  [p name]
  (when (some #{name} (:servers p))
    (get (:by-name @current) name)))
