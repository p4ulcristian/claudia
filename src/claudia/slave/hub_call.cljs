(ns claudia.slave.hub-call
  "Calls to the hub as this server (README decisions 47, 53 and 67): the hub's address, this server's
  name and its hub key go with every call as X-Claudia-Server-Name and X-Claudia-Server-Key. The
  front calls through here for the agent users' SSH certificates (claudia.slave.sshfront, its own
  FRONT_HUB_KEY_FILE); a runner for its agents' bookmark tools (claudia.slave.bookmarks,
  CLAUDIA_HUB_KEY_FILE, readable by it)."
  (:require ["node:fs" :as fs]
            [clojure.string :as str]))

(defn- link [url server key-file]
  (let [c {:url url :server server :key-file key-file}]
    (when (every? seq (vals c)) c)))

(defn settings
  "A runner's hub link from env (a JS object): {:url :server :key-file} from CLAUDIA_HUB_URL,
  CLAUDIA_SERVER_NAME and CLAUDIA_HUB_KEY_FILE, or nil when one is missing."
  [^js env]
  (link (.-CLAUDIA_HUB_URL env) (.-CLAUDIA_SERVER_NAME env) (.-CLAUDIA_HUB_KEY_FILE env)))

(defn front-settings
  "The front's hub link from env (a JS object): {:url :server :key-file} from CLAUDIA_HUB_URL,
  CLAUDIA_SERVER_NAME and FRONT_HUB_KEY_FILE, or nil when one is missing."
  [^js env]
  (link (.-CLAUDIA_HUB_URL env) (.-CLAUDIA_SERVER_NAME env) (.-FRONT_HUB_KEY_FILE env)))

(defn post!
  "Promise of the hub's answer {:ok :status :text} to a POST of body (data, sent as JSON) to path,
  as the server of hub-link ({:url :server :key-file}). Rejects when hub-link is nil or the hub
  cannot be reached."
  [hub-link path body]
  (if-let [{:keys [url server key-file]} hub-link]
    (-> (js/Promise.resolve)
        ;; Inside the promise, so an unreadable key file rejects instead of throwing.
        (.then #(js/fetch (str (str/replace url #"/+$" "") path)
                          #js {:method "POST"
                               :headers #js {"content-type" "application/json"
                                             "x-claudia-server-name" server
                                             "x-claudia-server-key" (str/trim (fs/readFileSync key-file "utf8"))}
                               :body (js/JSON.stringify (clj->js body))}))
        (.then (fn [^js r] (.then (.text r) (fn [t] {:ok (.-ok r) :status (.-status r) :text t})))))
    (js/Promise.reject (js/Error. "no hub link: CLAUDIA_HUB_URL, CLAUDIA_SERVER_NAME or the hub key file is not set"))))
