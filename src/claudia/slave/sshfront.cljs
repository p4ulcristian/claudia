(ns claudia.slave.sshfront
  "The front renews the agent users' SSH certificates (README decision 53). It runs as user claudia
  and holds this server's hub key (FRONT_HUB_KEY_FILE), which no agent user may read. At start and
  every 45 minutes, for each profile whose Linux user (the profiles file's `user=`, never a runner's
  word) is an agent user, it asks that profile's runner for its public key (POST /api/ssh/pubkey),
  the hub for a one-hour certificate (POST /hub/server/ssh-cert, as this server: claudia.slave.hub-call
  with CLAUDIA_HUB_URL, CLAUDIA_SERVER_NAME and FRONT_HUB_KEY_FILE), and hands it to the runner (POST
  /api/ssh/cert), which writes it (claudia.slave.ssh-cert). A failure is logged and tried again in 5
  minutes; it never stops the front."
  (:require [claudia.node.http :as io]
            [claudia.shared.activity :as activity]
            [claudia.shared.agent-user :as agent-user]
            [claudia.slave.hub-call :as hub-call]
            [claudia.slave.ssh-cert :as ssh-cert]))

(def ^:private runner-ms "A runner's answer, ssh-keygen included." 30000)

(defn agent-profiles
  "The profiles (the front's list, each with its :user) whose user is an agent user."
  [profiles]
  (filterv #(agent-user/agent-user? (:user %)) profiles))

(defn runner-problem
  "Why a runner's answer {:status :body} to the step named what failed, or nil (status 200)."
  [what {:keys [status body]}]
  (when-not (= 200 status)
    (str what ": the runner answered " status ": " (or (:error body) "no reason"))))

(defn public-key-of
  "The public key in a runner's answer to POST /api/ssh/pubkey: {:public-key line}, or {:problem}."
  [answer]
  (let [k (get-in answer [:body :publicKey])]
    (cond (runner-problem "public key" answer) {:problem (runner-problem "public key" answer)}
          (string? k) {:public-key k}
          :else {:problem "public key: the runner sent none"})))

(defn- or-throw [{:keys [problem] :as v}]
  (if problem (throw (js/Error. problem)) v))

(defn issued-line
  "The front's activity line for a certificate it handed to an agent user's runner (README decision
  59): the user and the certificate's key id (as sshd logs it)."
  [user key-id]
  (activity/line {:kind "ssh-cert" :agentUser user :keyId key-id}))

(defn- hand-over!
  "Promise: the certificate in the hub's answer written by the runner, the activity line logged."
  [socket user hub-answer]
  (-> (io/socket-json socket "/api/ssh/cert" (or-throw (ssh-cert/certificate-of hub-answer)) runner-ms)
      (.then (fn [answer]
               (or-throw {:problem (runner-problem "certificate" answer)})
               (js/console.log (issued-line user (ssh-cert/key-id-of hub-answer)))
               true))))

(defn renew!
  "Promise of whether profile's agent user has a new certificate in place."
  [hub-link {:keys [socket user]}]
  (-> (io/socket-json socket "/api/ssh/pubkey" {} runner-ms)
      (.then #(hub-call/post! hub-link "/hub/server/ssh-cert"
                              (ssh-cert/cert-request user (:public-key (or-throw (public-key-of %))))))
      (.then #(hand-over! socket user %))
      (.catch (fn [e] (js/console.error (str "ssh certificate for " user ": " (.-message e) "; again in 5 minutes")) false))))

(defn- loop! [hub-link profile]
  (.then (renew! hub-link profile)
         (fn [ok?] (.unref (js/setTimeout #(loop! hub-link profile) (ssh-cert/next-delay ok?))))))

(defn start!
  "Keeps the certificates of profiles' agent users fresh (each on its own clock); nothing when no
  profile here runs as an agent user, logged when the front has no hub link."
  [profiles]
  (let [agents (agent-profiles profiles)
        hub-link (hub-call/front-settings (.-env js/process))]
    (cond (empty? agents) nil
          (not hub-link) (js/console.log "ssh certificates: off, the front has no hub link (CLAUDIA_HUB_URL, CLAUDIA_SERVER_NAME, FRONT_HUB_KEY_FILE)")
          :else (doseq [p agents] (loop! hub-link p)))))
