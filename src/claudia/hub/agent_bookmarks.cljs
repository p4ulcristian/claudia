(ns claudia.hub.agent-bookmarks
  "An agent's bookmark tools reaching the hub (README decision 67): a runner calls POST
  /hub/server/claudia with its server's name and hub key (X-Claudia-Server-Name,
  X-Claudia-Server-Key; the gate passes this path without a login) and the person ({as: email}, who
  must have a profile on that server). The path keeps its name from Claudia's own chat, which is
  removed (README decision 112), so the gate's rule stays as it is.

  Body {as, op, args}: op bookmarks (the person's bookmarked folders, {items}), bookmark {server
  folder label? remove?} (one bookmark added, relabelled or removed; server one of the person's)."
  (:require [clojure.string :as str]
            [claudia.hub.bookmarks :as bookmarks]
            [claudia.hub.config :as config]
            [claudia.hub.hub-key :as hub-key]
            [claudia.hub.store :as store]
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

(defn- bookmark
  "The bookmark op for person: their list with one bookmark changed, kept; 400 when the server is
  not one of servers or the folder no absolute path."
  [person servers args]
  (let [email (:email person)
        changed (when (some #(= (:server args) (:name %)) servers)
                  (bookmarks/change (:items (bookmarks/of email)) args))]
    (cond (nil? changed) {:status 400 :body {:error "not one of your servers"}}
          (map? changed) {:status 400 :body changed}
          :else {:status 200 :body (select-keys (bookmarks/save! email changed) [:items])})))

(defn- operate [person {:keys [op args]}]
  (case op
    "bookmarks" {:status 200 :body (select-keys (bookmarks/of (:email person)) [:items])}
    "bookmark" (bookmark person (config/servers-of person) args)
    {:status 400 :body {:error (str "no operation " op)}}))

(defn call
  "POST /hub/server/claudia from a server."
  [^js req ^js res]
  (let [{:keys [server-name key-ok?]} (hub-key/server-caller req)]
    (-> (io/read-json req max-body)
        (.then (fn [b]
                 (let [as (str/lower-case (str (:as b)))
                       problem (caller-problem server-name as key-ok? (and key-ok? (store/may? as server-name)))]
                   (if problem
                     (io/send-json res 403 {:error problem})
                     (let [{:keys [status body]} (operate (config/person as) b)]
                       (io/send-json res status body))))))
        (.catch #(io/send-json res 400 {:error (.-message %)})))))
