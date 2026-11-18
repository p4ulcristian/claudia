(ns claudia.hub.ssh-ca
  "The hub's SSH certificate authority (README decision 53): servers reach each other as agent
  users with one-hour certificates the hub signs. A runner calls POST /hub/server/ssh-cert with its
  server's name and hub key (claudia.hub.hub-key/server-caller, as for Claudia's chat) and
  {user, publicKey}; the hub signs only for `<profile>-claudia-agent` where that profile is a
  person's (the store's profile names) and that person has a profile on that server. GET
  /hub/ssh-ca.pub gives the authority's public key, for the servers' TrustedUserCAKeys.

  The key: HUB_SSH_CA_FILE, an ed25519 private key in OpenSSH format without a passphrase
  (`ssh-keygen -t ed25519 -N '' -f <file>`). Unset or unreadable: both routes answer 503, the rest
  of the hub runs on. Parsing and signing are sshpk's."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["sshpk" :as sshpk]
            [clojure.string :as str]
            [claudia.hub.hub-key :as hub-key]
            [claudia.hub.store :as store]
            [claudia.node.env :refer [env]]
            [claudia.node.http :as io]
            [claudia.shared.agent-user :as agent-user]))

(def ^:private max-body (* 16 1024))

(def unset "Why there is no authority when HUB_SSH_CA_FILE is not set."
  "HUB_SSH_CA_FILE is not set: this hub signs no SSH certificates.")

;; {:key sshpk PrivateKey :public "ssh-ed25519 … claudia-hub-ca"}, or {:problem text}.
(defonce ^:private authority (atom {:problem unset}))

(defn read-authority
  "The authority from a private key file's text: {:key :public}, or {:problem} when it is not an
  ed25519 key sshpk can read."
  [text]
  (try (let [^js k (sshpk/parsePrivateKey text "ssh")
             ^js pub (.toPublic k)]
         (if (= "ed25519" (.-type k))
           (do (set! (.-comment pub) "claudia-hub-ca")
               {:key k :public (str (.toString pub "ssh") "\n")})
           {:problem (str "HUB_SSH_CA_FILE holds an " (.-type k) " key; it must be ed25519.")}))
       (catch :default e {:problem (str "HUB_SSH_CA_FILE cannot be read as an OpenSSH private key: " (.-message e))})))

(defn start!
  "Reads HUB_SSH_CA_FILE once; a problem is logged and answered by both routes (503)."
  []
  (let [file (env "HUB_SSH_CA_FILE")
        a (if file
            (try (read-authority (fs/readFileSync file "utf8"))
                 (catch :default e {:problem (str "HUB_SSH_CA_FILE " file ": " (.-message e))}))
            {:problem unset})]
    (when (and file (:problem a)) (js/console.error (str "hub: " (:problem a))))
    (reset! authority a)))

(def lead-ms "How long before now a certificate starts (clocks a little apart)." (* 5 60 1000))
(def life-ms "How long after now a certificate ends." (* 60 60 1000))

(defn validity
  "The window a certificate signed at now (ms) is valid in: {:from :until} in ms."
  [now]
  {:from (- now lead-ms) :until (+ now life-ms)})

(defn key-id
  "The certificate's key id, as sshd logs it: <server>/<user>/<when, ISO 8601 UTC>."
  [server user now]
  (str server "/" user "/" (.toISOString (js/Date. now))))

(defn request-problem
  "Why a certificate request is refused, or nil. server-name: the caller's name; key-ok?: its key
  is that server's; user: the agent user asked for; email: whose profile user's is (nil: nobody's);
  may?: that person has a profile on that server."
  [{:keys [server-name key-ok? user email may?]}]
  (cond (str/blank? server-name) "no server named"
        (not key-ok?) "not that server's key"
        (not (agent-user/agent-user? user)) (str "user must be <profile>" agent-user/suffix)
        (nil? email) "no person has that profile"
        (not may?) "that person has no profile on that server"
        :else nil))

(defn subject-key
  "The public key to certify, from its OpenSSH one-line text; throws unless it is ed25519."
  [text]
  (let [^js k (sshpk/parseKey (str text) "ssh")]
    (when-not (= "ed25519" (.-type k))
      (throw (js/Error. (str "publicKey must be ed25519, not " (.-type k)))))
    k))

(defn- with-openssh-fields!
  "Sets the OpenSSH key id and extensions sshpk's create leaves out, then signs again (sshpk keeps
  both on the certificate's openssh signature): permit-pty only, no forwarding of any kind."
  [^js cert ^js ca id]
  (let [^js sig (.. cert -signatures -openssh)]
    (set! (.-keyId sig) id)
    (set! (.-exts sig) #js [#js {:critical false :name "permit-pty" :data (js/Buffer.alloc 0)}])
    (.signWith cert ca)
    cert))

(defn certificate
  "An OpenSSH user certificate (one line) for subject (an sshpk Key), signed by ca (an sshpk
  PrivateKey): principal exactly user, valid from 5 minutes before now to 60 after, key id
  <server>/<user>/<when>, a random serial, extension permit-pty only."
  [^js ca ^js subject {:keys [server user now]}]
  (let [{:keys [from until]} (validity now)
        cert (.create sshpk/Certificate (sshpk/identityForUser user) subject
                      (sshpk/identityForUser "claudia-hub-ca") ca
                      #js {:validFrom (js/Date. from) :validUntil (js/Date. until)
                           :serial (crypto/randomBytes 8)})]
    (.toString ^js (with-openssh-fields! cert ca (key-id server user now)) "openssh")))

(defn- answer!
  "Checks a read request body and answers with the certificate or why not."
  [^js res ca {:keys [server-name key-ok?]} {:keys [user publicKey]}]
  (let [user (str user)
        email (when key-ok? (some-> (agent-user/profile-of user) store/email-of-profile))
        problem (request-problem {:server-name server-name :key-ok? key-ok? :user user :email email
                                  :may? (boolean (and email (store/may? email server-name)))})]
    (if problem
      (io/send-json res 403 {:error problem})
      (let [now (js/Date.now)
            cert (certificate ca (subject-key publicKey) {:server server-name :user user :now now})]
        (js/console.log (str "hub: ssh certificate " (key-id server-name user now)))
        (io/send-json res 200 {:certificate cert})))))

(defn call
  "POST /hub/server/ssh-cert from a server: {user, publicKey} -> {certificate}."
  [^js req ^js res]
  (let [{ca :key problem :problem} @authority
        caller (hub-key/server-caller req)]
    (if-not ca
      (io/send-json res 503 {:error problem})
      (-> (io/read-json req max-body)
          (.then #(answer! res ca caller %))
          (.catch #(io/send-json res 400 {:error (.-message %)}))))))

(defn public-key
  "GET /hub/ssh-ca.pub, no login: the authority's public key (one OpenSSH line)."
  [^js req ^js res]
  (let [{pub :public problem :problem} @authority]
    (cond (not= "GET" (.-method req)) (do (.setHeader res "allow" "GET") (io/send-text res 405 "GET only\n"))
          pub (io/send-text res 200 pub)
          :else (io/send-text res 503 (str problem "\n")))))
