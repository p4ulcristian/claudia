(ns claudia.hub.ops
  "The hub's own operations beyond the pass-through (PROTOCOL.md, Hub operations): the admins'
  Servers and Access pages (/hub/admin…), a joining server (/hub/enroll), phones and their Live
  Activities (/hub/push/…), desktop presence (/hub/presence), Claude tokens (/hub/claude-token…),
  each person's GitHub login (/hub/github-login),
  the gate's own numbers for admins (/hub/gate/system) and GitHub's webhook (/hub/github)."
  (:require [claudia.hub.listen :as listen]
            [claudia.hub.narrate :as narrate]
            [claudia.node.http :as io]
            [claudia.shared.text :as text]
            [clojure.string :as str]
            [claudia.hub.apns :as apns]
            [claudia.hub.config :as config]
            [claudia.hub.github :as github]
            [claudia.hub.github-login :as github-login]
            [claudia.hub.live :as live]
            [claudia.hub.pairing :as pairing]
            [claudia.hub.progress :as progress]
            [claudia.hub.releases :as releases]
            [claudia.hub.presence :as presence]
            [claudia.hub.push :as push]
            [claudia.hub.seal :as seal]
            [claudia.hub.store :as store]
            [claudia.hub.systems :as systems]
            [claudia.hub.tokens :as tokens]))

(def ^:private max-body (* 64 1024))
(def ^:private max-github-body (* 4 1024 1024))

(defn field
  "A string field, trimmed and cut to n characters (200 when not given), or \"\"."
  ([v] (field v 200))
  ([v n] (if (string? v) (text/cut (str/trim v) n "") "")))

(def ^:private address-re #"^[A-Za-z0-9.-]+:\d{1,5}$|^\[[0-9A-Fa-f:.]+\]:\d{1,5}$")
(def ^:private device-token #"^[0-9a-f]{64,200}$")
(def ^:private topic-re #"^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$")
(def ^:private live-token #"^[0-9a-f]{32,512}$")
(def ^:private activity-re #"^[A-Za-z0-9-]{1,100}$")
(def ^:private page-re #"^[A-Za-z0-9-]{8,64}$")

(defn- fail [^js res status message]
  (io/send-json res status {:error message}))

(defn- with-body
  "Reads the request's JSON body, then (f body) -> Promise."
  [^js req f]
  (.then (io/read-json req max-body) f))

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
     :pairing {:available (pairing/available?)}
     :builder (releases/builder-state)}))

(defn- changed!
  "After a change to the servers or the grid: pushes follow at once; the answer is the new state."
  [res]
  (push/sync!)
  (io/send-json res 200 (admin-state)))

(defn admin-route
  "Which admin operation a call is: [op] or [op name-from-the-path], else nil. The server name or
  email in the path is decoded (a badly encoded one throws)."
  [method pathname]
  (let [[_ server-name] (re-matches #"^/hub/admin/servers/([^/]+)$" pathname)
        [_ person-email] (re-matches #"^/hub/admin/people/([^/]+)$" pathname)
        server-name (some-> server-name js/decodeURIComponent)
        person-email (some-> person-email js/decodeURIComponent str/lower-case)]
    (cond
      (and (= pathname "/hub/admin") (= method "GET")) [:state]
      (and server-name (= method "PUT")) [:save-server server-name]
      (and server-name (= method "DELETE")) [:drop-server server-name]
      (and (= pathname "/hub/admin/people") (= method "POST")) [:add-person]
      (and person-email (= method "PUT")) [:save-person person-email]
      (and person-email (= method "DELETE")) [:drop-person person-email]
      (and (= pathname "/hub/admin/access") (= method "PUT")) [:set-access]
      (and (= pathname "/hub/admin/pair") (= method "POST")) [:pair]
      (and (= pathname "/hub/admin/builder") (= method "POST")) [:pair-builder]
      :else nil)))

(defn server-entry
  "The server a Servers page edit makes: the body's label and address, else the old ones."
  [server-name old b]
  {:name server-name
   :label (or (not-empty (field (:label b) 40)) (:label old) server-name)
   :address (or (not-empty (field (:address b) 100)) (:address old) "")})

(defn server-problem
  "What is wrong with a server to save, or nil."
  [{server-name :name address :address}]
  (cond
    (not (re-matches config/server-name server-name))
    "A server name: lower-case letters, digits and dashes, starting with a letter."
    (not (and (re-matches address-re address) (config/host-port address)))
    "Address: host:port, e.g. 10.0.0.2:4540."
    :else nil))

(defn- save-server [req res server-name]
  (with-body req
    (fn [b]
      (let [s (server-entry server-name (store/server server-name) b)]
        (if-let [problem (server-problem s)]
          (fail res 400 problem)
          (do (store/save-server! s) (changed! res)))))))

(defn- drop-server [_ res server-name]
  (pairing/drop-peer! server-name)
  (store/drop-server! server-name)
  (changed! res))

(defn- admin-flag [b] (when (boolean? (:admin b)) (:admin b)))

(defn- add-person [req res _]
  (with-body req
    (fn [b]
      (let [email (str/lower-case (field (:email b)))]
        (if-not (re-matches config/email-re email)
          (fail res 400 "That isn't an email address.")
          (do (store/save-person! {:email email :name (not-empty (field (:name b) 80)) :admin (admin-flag b)})
              (changed! res)))))))

(defn- save-person [req res email]
  (with-body req
    (fn [b]
      (if-not (store/person email)
        (fail res 404 "No such person.")
        (do (store/save-person! {:email email
                                 :name (when (string? (:name b)) (field (:name b) 80))
                                 :admin (admin-flag b)})
            (changed! res))))))

(defn- drop-person [_ res email]
  (store/drop-person! email)
  (changed! res))

(defn- set-access [req res _]
  (with-body req
    (fn [b]
      (let [email (str/lower-case (field (:email b)))
            server-name (field (:server b))]
        (if-not (and (store/person email) (store/server server-name))
          (fail res 400 "No such person or server.")
          (do (store/set-access! email server-name (true? (:granted b)))
              (changed! res)))))))

(defn pair-names
  "The name and label a pairing gets: the body's, else from the host the key carries."
  [pair-key b]
  [(or (not-empty (field (:name b) 31)) (pairing/suggest-name (:host pair-key)))
   (or (not-empty (field (:label b) 40)) (:host pair-key))])

(defn- pair [req res _]
  (with-body req
    (fn [b]
      (let [pair-key (pairing/parse-key (field (:key b) 400))]
        (cond
          (not (pairing/available?))
          (fail res 409 "Pairing is unavailable on this hub (no HUB_WG_POOL).")
          (not pair-key)
          (fail res 400 "That isn't a pairing key (claudia-pair:…). Run deploy/pair.sh on the server to get one.")
          :else
          (try (let [[pair-name label] (pair-names pair-key b)]
                 (pairing/pair! pair-key pair-name label))
               (changed! res)
               (catch :default e (fail res 409 (.-message e)))))))))

(def ^:private admin-ops
  {:state (fn [_ res _] (io/send-json res 200 (admin-state)))
   :save-server save-server
   :drop-server drop-server
   :add-person add-person
   :save-person save-person
   :drop-person drop-person
   :set-access set-access
   :pair pair
   :pair-builder (fn [req res _]
                   (with-body req (fn [b] (let [[status body] (releases/pair! (field (:key b) 200))]
                                            (if (= 200 status) (io/send-json res 200 (admin-state)) (io/send-json res status body))))))})

(defn- admin-call [^js req ^js res pathname]
  (if-let [[op arg] (admin-route (.-method req) pathname)]
    ((admin-ops op) req res arg)
    (fail res 404 "not found")))

;; Phones -----------------------------------------------------------------------------------------

(defn device-entry
  "The phone a registration body describes, for this person."
  [b email]
  {:token (str/lower-case (field (:token b)))
   :email email
   :env (field (:env b))
   :topic (field (:topic b) 155)
   :key (seal/device-key (:key b))
   :live-start (when (some? (:liveStartToken b)) (str/lower-case (field (:liveStartToken b) 600)))})

(defn device-problem
  "What is wrong with a phone to register, or nil."
  [{:keys [token env topic live-start] device-key :key}]
  (cond
    (not (re-matches device-token token)) "token: an APNs device token in hex"
    (not (#{"development" "production"} env)) "env: development or production"
    (not (re-matches topic-re topic)) "topic: the app's bundle id"
    (not device-key) "key: base64 of 32 random bytes"
    (and live-start (not (re-matches live-token live-start)))
    "liveStartToken: ActivityKit's push-to-start token in hex"
    :else nil))

(defn- register-device [req res email]
  (with-body req
    (fn [b]
      (let [{:keys [token env live-start] :as d} (device-entry b email)]
        (if-let [problem (device-problem d)]
          (fail res 400 problem)
          (do (store/save-device! d)
              (js/console.log (str "push: phone " (apns/short-token token) " (" env (when live-start ", Live Activity")
                                   ") registered for " email))
              (push/sync!)
              (push/changed! email)
              (io/send-json res 200 {:ok true :push (push/on?)})))))))

(defn live-report
  "A Live Activity report's body as {:device :id :token}."
  [b]
  {:device (str/lower-case (field (:device b)))
   :id (field (:activity b) 100)
   :token (str/lower-case (field (:token b) 600))})

(defn live-report-problem
  "What is wrong with a Live Activity report by its shape, or nil."
  [{:keys [id token]}]
  (cond
    (not (re-matches activity-re id)) "activity: the activity's id"
    (not (re-matches live-token token)) "token: the activity's update token in hex"
    :else nil))

(defn- live-saved [res email device a]
  (cond
    (not= device (:device a)) (fail res 409 "that activity is another phone's")
    (:ended-at a) (io/send-json res 200 {:ok true :ended true})
    :else (do (live/changed! email) (io/send-json res 200 {:ok true :ended false}))))

(defn- report-live [req res email]
  (with-body req
    (fn [b]
      (let [{:keys [device id token] :as r} (live-report b)]
        (if-let [problem (live-report-problem r)]
          (fail res 400 problem)
          (if-not (some #(= device (:token %)) (store/devices email))
            (fail res 404 "no such phone of yours")
            (do (store/save-activity! id device token)
                (live-saved res email device (store/activity id)))))))))

(defn- drop-device [_ res email token]
  (if (store/drop-device! (str/lower-case (js/decodeURIComponent token)) email)
    (do (push/sync!) (io/send-json res 200 {:ok true}))
    (fail res 404 "no such phone of yours")))

(defn- end-live [_ res email activity]
  (let [a (store/activity (js/decodeURIComponent activity))]
    (if (and a (= email (:email a)))
      (do (live/ended! email a) (io/send-json res 200 {:ok true}))
      (fail res 404 "no such activity of yours"))))

(defn- beat [req res email _]
  (with-body req
    (fn [b]
      (let [page (field (:page b) 64)]
        (if-not (re-matches page-re page)
          (fail res 400 "page: the page's id")
          (do (presence/beat! email page (true? (:active b)))
              (io/no-content res)))))))

(defn person-route
  "Which of a person's own calls this is: [op] or [op id-from-the-path] (still encoded), else nil."
  [method pathname]
  (let [[_ token] (re-matches #"^/hub/push/devices/([^/]+)$" pathname)
        [_ activity] (re-matches #"^/hub/push/live/([^/]+)$" pathname)]
    (cond
      (and (= pathname "/hub/push/devices") (= method "POST")) [:register]
      (and token (= method "DELETE")) [:drop-device token]
      (and (= pathname "/hub/push/live") (= method "POST")) [:report-live]
      (and activity (= method "DELETE")) [:end-live activity]
      (and (= pathname "/hub/presence") (= method "POST")) [:presence]
      :else nil)))

(def ^:private person-ops
  {:register (fn [req res email _] (register-device req res email))
   :drop-device drop-device
   :report-live (fn [req res email _] (report-live req res email))
   :end-live end-live
   :presence beat})

(defn- person-call
  "Calls any signed-in person makes about themselves (phones, Live Activities, presence)."
  [^js req ^js res pathname email]
  (if-let [[op arg] (person-route (.-method req) pathname)]
    ((person-ops op) req res email arg)
    (fail res 404 "not found")))

;; Claude tokens (claudia.hub.tokens) -------------------------------------------------------------

(defn token-route
  "Which Claude-token call a method and path are: [:state], [:save], [:use], [:remove name-in-path]
  or nil."
  [method pathname]
  (let [[_ gone] (re-matches #"^/hub/claude-tokens/([^/]+)$" pathname)]
    (cond
      (and (= pathname "/hub/claude-tokens") (= method "GET")) [:state]
      (and (= pathname "/hub/claude-token") (= method "PUT")) [:save]
      (and (= pathname "/hub/claude-token/use") (= method "POST")) [:use]
      (and gone (= method "DELETE")) [:remove gone]
      :else nil)))

(defn- token-call
  "GET /hub/claude-tokens; PUT /hub/claude-token {name token}; POST /hub/claude-token/use {name};
  DELETE /hub/claude-tokens/<name>."
  [^js req ^js res pathname email]
  (let [person {:email email}
        answer (fn [[status body]] (io/send-json res status body))
        [op raw] (token-route (.-method req) pathname)]
    (case op
      :state (io/send-json res 200 (tokens/state person))
      :save (.then (with-body req #(tokens/save! person (:name %) (:token %))) answer)
      :use (.then (with-body req #(tokens/use! person (:name %))) answer)
      :remove (if-let [token-name (io/decode raw)]
                (answer (tokens/remove! person token-name))
                (fail res 400 "bad name"))
      (fail res 404 "not found"))))

;; GitHub logins (claudia.hub.github-login) --------------------------------------------------------

(defn- github-login-call
  "GET /hub/github-login (the caller's login and their servers); PUT /hub/github-login {token}."
  [^js req ^js res email]
  (let [person {:email email}]
    (case (.-method req)
      "GET" (io/send-json res 200 (github-login/state person))
      "PUT" (.then (with-body req #(github-login/save! person (:token %)))
                   (fn [[status body]] (io/send-json res status body)))
      (fail res 405 "GET or PUT"))))

;; Entry points -----------------------------------------------------------------------------------

(defn open-call?
  "Calls that come with no login: a joining server, GitHub, the release builder and release files
  (claudia.hub.releases checks the builder's token itself)."
  [pathname]
  (or (#{"/hub/enroll" "/hub/github"} pathname) (str/starts-with? pathname "/hub/releases/")))

(defn- github-delivery [^js req ^js res]
  (.then (io/read-bytes req max-github-body)
         (fn [body]
           (let [h (.-headers req)
                 status (github/handle body {"x-hub-signature-256" (aget h "x-hub-signature-256")
                                             "x-github-event" (aget h "x-github-event")})]
             (.writeHead res status #js {"cache-control" "no-store"})
             (.end res)))))

(defn- enroll [req res]
  (with-body req
    (fn [b] (let [[status body] (pairing/enroll (:secret b))] (io/send-json res status body)))))

(defn open-call
  [^js req ^js res pathname]
  (cond
    (str/starts-with? pathname "/hub/releases/")
    (-> (js/Promise.resolve) (.then #(releases/call req res pathname))
        (.catch (fn [e] (when-not (.-headersSent res) (fail res 400 (.-message e))))))
    (not= "POST" (.-method req))
    (do (.setHeader res "allow" "POST") (fail res 405 "POST only"))
    :else
    (-> (if (= pathname "/hub/github") (github-delivery req res) (enroll req res))
        (.catch (fn [e] (when-not (.-headersSent res) (fail res 400 (.-message e))))))))

(defn call
  "A signed-in caller's /hub/… call other than /hub/me and server state: admin? decides the admin
  pages. Answers 404 for anything else."
  [^js req ^js res pathname email admin?]
  (-> (js/Promise.resolve)
      (.then (fn []
               (cond
                 (str/starts-with? pathname "/hub/admin")
                 (if admin? (admin-call req res pathname) (fail res 403 "admins only"))
                 (or (str/starts-with? pathname "/hub/push/") (= pathname "/hub/presence"))
                 (person-call req res pathname email)
                 (= pathname "/hub/narrate") (narrate/call req res)
                 (= pathname "/hub/progress") (io/send-json res 200 (progress/now))
                 (= pathname "/hub/listen") (listen/call req res)
                 (= pathname "/hub/gate/system")
                 (cond (not admin?) (fail res 403 "admins only")
                       (not= "GET" (.-method req)) (fail res 405 "GET only")
                       :else (systems/gate-sample res))
                 (str/starts-with? pathname "/hub/claude-token")
                 (token-call req res pathname email)
                 (= pathname "/hub/github-login") (github-login-call req res email)
                 :else (fail res 404 "not found"))))
      (.catch (fn [e]
                (js/console.error (str "hub: " pathname ": " (.-message e)))
                (when-not (.-headersSent res) (fail res 500 (.-message e)))))))
