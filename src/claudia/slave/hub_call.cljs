(ns claudia.slave.hub-call
  "A runner's calls to the hub as its server (README decisions 47 and 48): CLAUDIA_HUB_URL,
  CLAUDIA_SERVER_NAME and CLAUDIA_HUB_KEY_FILE (this server's hub key, as the front keeps it) go
  with every call as X-Claudia-Server-Name and X-Claudia-Server-Key. Claudia's own chat and the SSH
  certificate renewal (claudia.slave.ssh-cert) call through here."
  (:require ["node:fs" :as fs]
            [clojure.string :as str]))

(defn settings
  "The hub link from env (a JS object): {:url :server :key-file}, or nil when one is missing."
  [^js env]
  (let [c {:url (.-CLAUDIA_HUB_URL env) :server (.-CLAUDIA_SERVER_NAME env)
           :key-file (.-CLAUDIA_HUB_KEY_FILE env)}]
    (when (every? seq (vals c)) c)))

(defn post!
  "Promise of the hub's answer {:ok :status :text} to a POST of body (data, sent as JSON) to path,
  as this server. Rejects when the link is not set or the hub cannot be reached."
  [path body]
  (if-let [{:keys [url server key-file]} (settings (.-env js/process))]
    (-> (js/Promise.resolve)
        ;; Inside the promise, so an unreadable key file rejects instead of throwing.
        (.then #(js/fetch (str (str/replace url #"/+$" "") path)
                          #js {:method "POST"
                               :headers #js {"content-type" "application/json"
                                             "x-claudia-server-name" server
                                             "x-claudia-server-key" (str/trim (fs/readFileSync key-file "utf8"))}
                               :body (js/JSON.stringify (clj->js body))}))
        (.then (fn [^js r] (.then (.text r) (fn [t] {:ok (.-ok r) :status (.-status r) :text t})))))
    (js/Promise.reject (js/Error. "CLAUDIA_HUB_URL, CLAUDIA_SERVER_NAME or CLAUDIA_HUB_KEY_FILE is not set"))))
