(ns claudia.slave.github-services
  "The front's half of GitHub for services (README decision 78): the hub hands a service's 1-hour
  token here (PUT /api/github/service-token, a call only the hub makes) and the front writes it to
  `<FRONT_GITHUB_SERVICES_DIR>/<service>/token`, with the bot it acts as in `login` beside it. A
  service gets tokens only when root made its folder (owned by user claudia, the service's group,
  setgid), so the files are readable by the service and by no other user; the front makes no folder."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [claudia.node.http :as io]))

(def ^:private env (.-env js/process))
(def ^:private file-mode "rw-r----- (0640): the service's group reads it." 416)

(defn checked
  "The hub's body as {:name :token :login}, or {:error} when anything is not what it should be."
  [{svc :name :keys [token login]}]
  (cond
    (not (and (string? svc) (re-matches #"^[a-z][a-z0-9-]{0,31}$" svc))) {:error "Not a service name."}
    (not (and (string? token) (re-matches #"^ghs_[A-Za-z0-9_.-]{20,1024}$" token))) {:error "Not an app installation token."}
    (not (and (string? login) (re-matches #"^[A-Za-z0-9][A-Za-z0-9-]{0,38}\[bot\]$" login))) {:error "Not an app's bot login."}
    :else {:name svc :token token :login login}))

(defn- dir-of [root svc]
  (let [d (path/join root svc)]
    (when (try (.isDirectory (fs/statSync d)) (catch :default _ false)) d)))

(defn- write-file!
  "Writes text to file through a file beside it and a rename, so a reader never sees half of it."
  [file text]
  (let [tmp (str file ".new")]
    (fs/writeFileSync tmp text #js {:mode file-mode})
    (fs/chmodSync tmp file-mode)
    (fs/renameSync tmp file)))

(defn- write! [root {svc :name :keys [token login]}]
  (if-let [d (dir-of root svc)]
    (do (write-file! (path/join d "login") (str login "\n"))
        (write-file! (path/join d "token") (str token "\n"))
        {:ok true})
    {:status 404 :error (str "No GitHub folder for " svc " on this server.")}))

(defn handle
  "PUT /api/github/service-token from the hub."
  [^js req ^js res]
  (let [root (.-FRONT_GITHUB_SERVICES_DIR env)]
    (cond
      (not= "PUT" (.-method req)) (io/send-json res 405 {:error "PUT only"})
      (empty? root) (io/send-json res 501 {:error "No FRONT_GITHUB_SERVICES_DIR on this server."})
      :else
      (.then (io/read-json req 4096)
             (fn [body]
               (let [c (checked body)
                     r (if (:error c) (assoc c :status 400)
                           (try (write! root c) (catch :default e {:status 500 :error (.-message e)})))]
                 (io/send-json res (or (:status r) 200) (dissoc r :status))))))))
