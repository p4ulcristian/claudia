(ns claudia.hub.ops
  "The hub's own operations beyond the pass-through (PROTOCOL.md, Hub operations): the admins'
  Servers and Access pages (/hub/admin…), a joining server (/hub/enroll), phones and their Live
  Activities (/hub/push/…), desktop presence (/hub/presence) and GitHub's webhook (/hub/github)."
  (:require [clojure.string :as str]
            [claudia.hub.apns :as apns]
            [claudia.hub.config :as config]
            [claudia.hub.github :as github]
            [claudia.hub.live :as live]
            [claudia.hub.pairing :as pairing]
            [claudia.hub.presence :as presence]
            [claudia.hub.push :as push]
            [claudia.hub.seal :as seal]
            [claudia.hub.store :as store]
            [claudia.shared.protocol :as protocol]))

(def ^:private max-body (* 64 1024))
(def ^:private max-github-body (* 4 1024 1024))

(defn- send-json [^js res status data]
  (.writeHead res status #js {"content-type" "application/json; charset=utf-8" "cache-control" "no-store"})
  (.end res (protocol/write-json data)))

(defn- no-content [^js res] (.writeHead res 204 #js {"cache-control" "no-store"}) (.end res))

(defn- read-raw
  "Promise of the body as a Buffer (more than max is refused)."
  [^js req max]
  (js/Promise.
   (fn [resolve reject]
     (let [chunks #js [] size (atom 0)]
       (.on req "data" (fn [c]
                         (if (> (swap! size + (.-length c)) max)
                           (do (reject (js/Error. "body too large")) (.destroy req))
                           (.push chunks c))))
       (.on req "end" #(resolve (js/Buffer.concat chunks)))
       (.on req "error" reject)))))

(defn- read-body
  "Promise of the JSON body as a map ({} when empty or not an object)."
  [req]
  (.then (read-raw req max-body)
         (fn [^js b] (let [v (try (protocol/read-json (.toString b "utf8")) (catch :default _ nil))]
                       (if (map? v) v {})))))

(defn- text
  "A string field, trimmed and cut, or \"\"."
  ([v] (text v 200))
  ([v max] (if (string? v) (let [t (str/trim v)] (subs t 0 (min max (count t)))) "")))

(def ^:private address-re #"^[A-Za-z0-9.-]+:\d{1,5}$|^\[[0-9A-Fa-f:.]+\]:\d{1,5}$")
(def ^:private device-token #"^[0-9a-f]{64,200}$")
(def ^:private topic-re #"^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$")
(def ^:private live-token #"^[0-9a-f]{32,512}$")
(def ^:private activity-re #"^[A-Za-z0-9-]{1,100}$")
(def ^:private page-re #"^[A-Za-z0-9-]{8,64}$")

;; Admin ------------------------------------------------------------------------------------------

(defn admin-state
  "What the Servers and Access pages show."
  []
  (let [pairings (store/pairings)]
    {:servers (vec (for [s (store/servers)]
                     (assoc s
                            :pairing (some #(when (= (:name s) (:server %)) %) pairings)
                            :peerUp (pairing/applied? (:name s)))))
     :people (store/people)
     :access (store/access)
     :pairing {:available (pairing/available?)}}))

(defn- changed!
  "After a change to the servers or the grid: pushes follow at once; the answer is the new state."
  [res]
  (push/sync!)
  (send-json res 200 (admin-state)))

(defn- admin-call [^js req ^js res pathname]
  (let [method (.-method req)
        [_ server-name] (re-matches #"^/hub/admin/servers/([^/]+)$" pathname)
        [_ person-email] (re-matches #"^/hub/admin/people/([^/]+)$" pathname)
        server-name (some-> server-name js/decodeURIComponent)
        person-email (some-> person-email js/decodeURIComponent str/lower-case)
        fail #(send-json res %1 {:error %2})]
    (cond
      (and (= pathname "/hub/admin") (= method "GET")) (send-json res 200 (admin-state))

      (and server-name (= method "PUT"))
      (.then (read-body req)
             (fn [b]
               (let [old (store/server server-name)
                     label (or (not-empty (text (:label b) 40)) (:label old) server-name)
                     address (or (not-empty (text (:address b) 100)) (:address old) "")]
                 (cond
                   (not (re-matches config/server-name server-name))
                   (fail 400 "A server name: lower-case letters, digits and dashes, starting with a letter.")
                   (not (and (re-matches address-re address) (config/host-port address)))
                   (fail 400 "Address: host:port, e.g. 10.0.0.2:4540.")
                   :else (do (store/save-server! {:name server-name :label label :address address})
                             (changed! res))))))

      (and server-name (= method "DELETE"))
      (do (pairing/drop-peer! server-name)
          (store/drop-server! server-name)
          (changed! res))

      (and (= pathname "/hub/admin/people") (= method "POST"))
      (.then (read-body req)
             (fn [b]
               (let [email (str/lower-case (text (:email b)))]
                 (if-not (re-matches config/email-re email)
                   (fail 400 "That isn't an email address.")
                   (do (store/save-person! {:email email :name (not-empty (text (:name b) 80))
                                            :admin (when (boolean? (:admin b)) (:admin b))})
                       (changed! res))))))

      (and person-email (= method "PUT"))
      (.then (read-body req)
             (fn [b]
               (if-not (store/person person-email)
                 (fail 404 "No such person.")
                 (do (store/save-person! {:email person-email
                                          :name (when (string? (:name b)) (text (:name b) 80))
                                          :admin (when (boolean? (:admin b)) (:admin b))})
                     (changed! res)))))

      (and person-email (= method "DELETE"))
      (do (store/drop-person! person-email) (changed! res))

      (and (= pathname "/hub/admin/access") (= method "PUT"))
      (.then (read-body req)
             (fn [b]
               (let [email (str/lower-case (text (:email b)))
                     server (text (:server b))]
                 (if-not (and (store/person email) (store/server server))
                   (fail 400 "No such person or server.")
                   (do (store/set-access! email server (true? (:granted b)))
                       (changed! res))))))

      (and (= pathname "/hub/admin/pair") (= method "POST"))
      (.then (read-body req)
             (fn [b]
               (let [key (pairing/parse-key (text (:key b) 400))]
                 (cond
                   (not (pairing/available?))
                   (fail 409 "Pairing is unavailable on this hub (no HUB_WG_POOL).")
                   (not key)
                   (fail 400 "That isn't a pairing key (claudia-pair:…). Run deploy/pair.sh on the server to get one.")
                   :else
                   (try (pairing/pair! key
                                       (or (not-empty (text (:name b) 31)) (pairing/suggest-name (:host key)))
                                       (or (not-empty (text (:label b) 40)) (:host key)))
                        (changed! res)
                        (catch :default e (fail 409 (.-message e))))))))

      :else (fail 404 "not found"))))

;; Phones -----------------------------------------------------------------------------------------

(defn- register-device [^js req ^js res email]
  (.then (read-body req)
         (fn [b]
           (let [token (str/lower-case (text (:token b)))
                 env (text (:env b))
                 topic (text (:topic b) 155)
                 key (seal/device-key (:key b))
                 live-start (when (some? (:liveStartToken b)) (str/lower-case (text (:liveStartToken b) 600)))]
             (cond
               (not (re-matches device-token token)) (send-json res 400 {:error "token: an APNs device token in hex"})
               (not (#{"development" "production"} env)) (send-json res 400 {:error "env: development or production"})
               (not (re-matches topic-re topic)) (send-json res 400 {:error "topic: the app's bundle id"})
               (not key) (send-json res 400 {:error "key: base64 of 32 random bytes"})
               (and live-start (not (re-matches live-token live-start)))
               (send-json res 400 {:error "liveStartToken: ActivityKit's push-to-start token in hex"})
               :else
               (do (store/save-device! {:token token :email email :env env :topic topic :key key :live-start live-start})
                   (js/console.log (str "push: phone " (apns/short-token token) " (" env (when live-start ", Live Activity")
                                        ") registered for a person"))
                   (push/sync!)
                   (push/changed! email)
                   (send-json res 200 {:ok true :push (push/on?)})))))))

(defn- report-live [^js req ^js res email]
  (.then (read-body req)
         (fn [b]
           (let [device (str/lower-case (text (:device b)))
                 id (text (:activity b) 100)
                 token (str/lower-case (text (:token b) 600))]
             (cond
               (not (re-matches activity-re id)) (send-json res 400 {:error "activity: the activity's id"})
               (not (re-matches live-token token)) (send-json res 400 {:error "token: the activity's update token in hex"})
               (not (some #(= device (:token %)) (store/devices email))) (send-json res 404 {:error "no such phone of yours"})
               :else
               (do (store/save-activity! id device token)
                   (let [a (store/activity id)]
                     (cond
                       (not= device (:device a)) (send-json res 409 {:error "that activity is another phone's"})
                       (:ended-at a) (send-json res 200 {:ok true :ended true})
                       :else (do (live/changed! email) (send-json res 200 {:ok true :ended false}))))))))))

(defn- person-call
  "Calls any signed-in person makes about themselves (phones, Live Activities, presence)."
  [^js req ^js res pathname email]
  (let [method (.-method req)
        [_ token] (re-matches #"^/hub/push/devices/([^/]+)$" pathname)
        [_ activity] (re-matches #"^/hub/push/live/([^/]+)$" pathname)]
    (cond
      (and (= pathname "/hub/push/devices") (= method "POST")) (register-device req res email)
      (and token (= method "DELETE"))
      (if (store/drop-device! (str/lower-case (js/decodeURIComponent token)) email)
        (do (push/sync!) (send-json res 200 {:ok true}))
        (send-json res 404 {:error "no such phone of yours"}))
      (and (= pathname "/hub/push/live") (= method "POST")) (report-live req res email)
      (and activity (= method "DELETE"))
      (let [a (store/activity (js/decodeURIComponent activity))]
        (if (and a (= email (:email a)))
          (do (live/ended! email a) (send-json res 200 {:ok true}))
          (send-json res 404 {:error "no such activity of yours"})))
      (and (= pathname "/hub/presence") (= method "POST"))
      (.then (read-body req)
             (fn [b]
               (let [page (text (:page b) 64)]
                 (if-not (re-matches page-re page)
                   (send-json res 400 {:error "page: the page's id"})
                   (do (presence/beat! email page (true? (:active b)))
                       (no-content res))))))
      :else (send-json res 404 {:error "not found"}))))

;; Entry points -----------------------------------------------------------------------------------

(defn open-call?
  "Calls that come with no login: a joining server, GitHub."
  [pathname]
  (#{"/hub/enroll" "/hub/github"} pathname))

(defn open-call
  [^js req ^js res pathname]
  (if (not= "POST" (.-method req))
    (do (.setHeader res "allow" "POST") (send-json res 405 {:error "POST only"}))
    (-> (if (= pathname "/hub/github")
          (.then (read-raw req max-github-body)
                 (fn [body]
                   (let [h (.-headers req)
                         status (github/handle body {"x-hub-signature-256" (aget h "x-hub-signature-256")
                                                     "x-github-event" (aget h "x-github-event")})]
                     (.writeHead res status #js {"cache-control" "no-store"})
                     (.end res))))
          (.then (read-body req)
                 (fn [b] (let [[status body] (pairing/enroll (:secret b))] (send-json res status body)))))
        (.catch (fn [e] (when-not (.-headersSent res) (send-json res 400 {:error (.-message e)})))))))

(defn call
  "A signed-in caller's /hub/… call other than /hub/me and server state: admin? decides the admin
  pages. Answers 404 for anything else."
  [^js req ^js res pathname email admin?]
  (-> (js/Promise.resolve)
      (.then (fn []
               (cond
                 (str/starts-with? pathname "/hub/admin")
                 (if admin? (admin-call req res pathname) (send-json res 403 {:error "admins only"}))
                 (or (str/starts-with? pathname "/hub/push/") (= pathname "/hub/presence"))
                 (person-call req res pathname email)
                 :else (send-json res 404 {:error "not found"}))))
      (.catch (fn [e]
                (js/console.error (str "hub: " pathname ": " (.-message e)))
                (when-not (.-headersSent res) (send-json res 500 {:error (.-message e)}))))))
