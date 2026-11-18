(ns claudia.hub.claudia
  "Claudia's own chat acting for its person (README decision 48): her tools run in that person's
  runner on one server, which calls POST /hub/server/claudia with the server's name and hub key
  (X-Claudia-Server-Name, X-Claudia-Server-Key; the gate passes this path without a login) and
  the person ({as: email}, who must have a profile on that server). The hub then carries the
  operation out on the person's servers as them (claudia.slave.chat-ops on each runner).

  Body {as, op, args}: op chats (every server's chats), new {server text folder?}, send {server
  chat text}, archive {server chat archived}, stop {server chat}."
  (:require [clojure.string :as str]
            [claudia.hub.config :as config]
            [claudia.hub.hub-key :as hub-key]
            [claudia.hub.pass :as pass]
            [claudia.hub.store :as store]
            [claudia.node.crypto :as crypto]
            [claudia.node.http :as io]))

(def ^:private max-body (* 256 1024))

(defn caller-problem
  "Why a server's call is refused (server-name, key, as: the person, key-ok?: whether the key is
  that server's, profile?: whether the person has a profile there), or nil."
  [server-name as key-ok? profile?]
  (cond (str/blank? server-name) "no server named"
        (not key-ok?) "not that server's key"
        (str/blank? as) "no person named"
        (not profile?) "that person has no profile on that server"
        :else nil))

(defn row-of
  "What Claudia sees of a chat row on server: its id, title, project, state."
  [server r]
  (select-keys (assoc r :server server) [:server :id :title :project :archived :running :passive :lastTurn]))

(defn- all-chats [servers who]
  (-> (js/Promise.all
       (into-array (for [s servers]
                     (.then (pass/json! s "GET" "/api/chat-ops/chats" who nil)
                            (fn [{:keys [status body]}]
                              (if (= 200 status)
                                (mapv #(row-of (:name s) %) (:chats body))
                                [{:server (:name s) :error (or (:error body) "unreachable")}]))))))
      (.then #(do {:status 200 :body {:chats (vec (apply concat %))}}))))

(defn op-target
  "The chat operation's runner path for op, or nil when it is not one."
  [op]
  ({"new" "/api/chat-ops/new" "send" "/api/chat-ops/send" "archive" "/api/chat-ops/archive"
    "stop" "/api/chat-ops/stop"} op))

(defn- operate [person {:keys [op args]}]
  (let [who {:email (:email person)}
        servers (config/servers-of person)]
    (if (= op "chats")
      (all-chats servers who)
      (let [target (op-target op)
            server (some #(when (= (:server args) (:name %)) %) servers)]
        (cond (nil? target) (js/Promise.resolve {:status 400 :body {:error (str "no operation " op)}})
              (nil? server) (js/Promise.resolve {:status 400 :body {:error "not one of your servers"}})
              :else (pass/json! server "POST" target who (dissoc args :server)))))))

(defn call
  "POST /hub/server/claudia from a server."
  [^js req ^js res]
  (let [h (.-headers req)
        server-name (str (aget h "x-claudia-server-name"))
        key-ok? (and (seq server-name) (store/server server-name)
                     (crypto/same-secret? (aget h "x-claudia-server-key") (hub-key/for-server server-name)))]
    (-> (io/read-json req max-body)
        (.then (fn [b]
                 (let [as (str/lower-case (str (:as b)))
                       problem (caller-problem server-name as key-ok? (and key-ok? (store/may? as server-name)))]
                   (if problem
                     (io/send-json res 403 {:error problem})
                     (.then (operate (config/person as) b)
                            (fn [{:keys [status body]}] (io/send-json res status body)))))))
        (.catch #(io/send-json res 400 {:error (.-message %)})))))
