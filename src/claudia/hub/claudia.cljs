(ns claudia.hub.claudia
  "Claudia's own chat acting for its person (README decision 48): her tools run in that person's
  runner on one server, which calls POST /hub/server/claudia with the server's name and hub key
  (X-Claudia-Server-Name, X-Claudia-Server-Key; the gate passes this path without a login) and
  the person ({as: email}, who must have a profile on that server). The hub then carries the
  operation out on the person's servers as them (claudia.slave.chat-ops on each runner).

  Body {as, op, args}: op chats (every server's chats and the person's chat order), new {server
  text folder?}, send {server chat text}, archive {server chat archived}, stop {server chat},
  organize {steps} (README decision 88: rename, recolour, archive, group and order any of the
  person's chats, claudia.hub.organize); and for any chat's bookmark tools
  (README decision 67): bookmarks (the person's bookmarked folders, {items}), bookmark {server folder
  label? remove?} (one bookmark added, relabelled or removed; server one of the person's)."
  (:require [clojure.string :as str]
            [claudia.hub.bookmarks :as bookmarks]
            [claudia.hub.chat-order :as chat-order]
            [claudia.hub.config :as config]
            [claudia.hub.hub-key :as hub-key]
            [claudia.hub.organize :as organize]
            [claudia.hub.pass :as pass]
            [claudia.hub.store :as store]
            [claudia.node.http :as io]
            [claudia.shared.order :as order]))

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
  "What Claudia sees of a chat row on server: its reference (server/id), title, colour, folder,
  project, state, last activity (at) and start (started)."
  [server r]
  (-> (assoc r :server server :ref (order/ref-of (assoc r :server server)))
      (select-keys [:server :id :ref :title :color :folder :project :archived :running :passive :claudia
                    :at :started :lastTurn])))

(defn- all-chats [servers who]
  (-> (js/Promise.all
       (into-array (for [s servers]
                     (.then (pass/json! s "GET" "/api/chat-ops/chats" who nil)
                            (fn [{:keys [status body]}]
                              (if (= 200 status)
                                (mapv #(row-of (:name s) %) (:chats body))
                                [{:server (:name s) :error (or (:error body) "unreachable")}]))))))
      (.then #(do {:status 200 :body {:chats (vec (apply concat %)) :order (chat-order/of (:email who))}}))))

(defn op-target
  "The chat operation's runner path for op, or nil when it is not one."
  [op]
  ({"new" "/api/chat-ops/new" "send" "/api/chat-ops/send" "archive" "/api/chat-ops/archive"
    "stop" "/api/chat-ops/stop"} op))

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

(defn- edit! [servers who {:keys [server chat body]}]
  (if-let [s (some #(when (= server (:name %)) %) servers)]
    (pass/json! s "PATCH" (str "/api/chats/" (js/encodeURIComponent chat)) who body)
    (js/Promise.resolve {:status 400 :body {:error "not one of your servers"}})))

(defn- organized
  "The organize op's answer once plan's edits are made: each step's result and the list as kept."
  [email {:keys [results edits]} answers]
  {:status 200
   :body {:results (reduce (fn [rs [e a]] (assoc rs (:i e) (organize/edit-result a))) results (map vector edits answers))
          :order (chat-order/of email)}})

(defn- organize
  "organize {steps} (README decision 88): the order steps kept on the hub at once, then the edits
  made on each chat's server as the person."
  [servers who {:keys [steps]}]
  (if-let [p (organize/steps-problem steps)]
    (js/Promise.resolve {:status 400 :body {:error p}})
    (.then (all-chats servers who)
           (fn [{{:keys [chats]} :body}]
             (let [email (:email who)
                   plan (organize/plan chats (:items (chat-order/of email)) (vec steps)
                                       (vec (repeatedly (count steps) #(str "g-" (random-uuid)))))]
               (when (:changed plan) (chat-order/save! email (:items plan)))
               (.then (js/Promise.all (into-array (map #(edit! servers who %) (:edits plan))))
                      #(organized email plan (vec %))))))))

(defn- operate [person {:keys [op args]}]
  (let [who {:email (:email person)}
        servers (config/servers-of person)]
    (case op
      "chats" (all-chats servers who)
      "organize" (organize servers who args)
      "bookmarks" (js/Promise.resolve {:status 200 :body (select-keys (bookmarks/of (:email person)) [:items])})
      "bookmark" (js/Promise.resolve (bookmark person servers args))
      (let [target (op-target op)
            server (some #(when (= (:server args) (:name %)) %) servers)]
        (cond (nil? target) (js/Promise.resolve {:status 400 :body {:error (str "no operation " op)}})
              (nil? server) (js/Promise.resolve {:status 400 :body {:error "not one of your servers"}})
              :else (pass/json! server "POST" target who (dissoc args :server)))))))

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
                     (.then (operate (config/person as) b)
                            (fn [{:keys [status body]}] (io/send-json res status body)))))))
        (.catch #(io/send-json res 400 {:error (.-message %)})))))
