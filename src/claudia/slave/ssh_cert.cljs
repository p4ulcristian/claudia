(ns claudia.slave.ssh-cert
  "The agent user's SSH certificate (README decision 53): the runner's side, and the pure parts the
  front shares. The front (user claudia, which holds the hub key) renews it (claudia.slave.sshfront):
  it asks a runner that runs as an agent user (`<profile>-claudia-agent`) for its public key (POST
  /api/ssh/pubkey: the runner makes ~/.ssh/claudia_agent the first time), gets the one-hour
  certificate from the hub, and hands it over (POST /api/ssh/cert); the runner writes it to
  ~/.ssh/claudia_agent-cert.pub and keeps a managed block in ~/.ssh/config, so `ssh <server>` uses
  both and lands as the same agent user. The runner never calls the hub for it, nor reads the hub key."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.node.http :as io]
            [claudia.shared.agent-user :as agent-user]
            [claudia.shared.protocol :as protocol]))

(defn- mode [octal] (js/parseInt octal 8))

(def renew-ms "Between renewals: well inside the certificate's hour." (* 45 60 1000))
(def retry-ms "After a failed renewal." (* 5 60 1000))

(def block-begin "# >>> Claudia agent SSH (managed by Claudia's runner; edits inside are replaced) >>>")
(def block-end "# <<< Claudia agent SSH <<<")

(defn next-delay "When to renew again after a renewal that went ok? or not." [ok?] (if ok? renew-ms retry-ms))

(defn config-block
  "The managed ~/.ssh/config block for agent user `user` (its lines, markers included)."
  [user]
  (str/join "\n" [block-begin
                  "Host *"
                  "    IdentityFile ~/.ssh/claudia_agent"
                  "    CertificateFile ~/.ssh/claudia_agent-cert.pub"
                  "    IdentitiesOnly yes"
                  (str "    User " user)
                  block-end]))

(defn merge-block
  "The ssh config text with block in place of the managed one (between the markers), or added at
  its end when it has none; every other line kept as it was. At the end, so a Host entry above it
  still wins for its own options (ssh takes each option's first value)."
  [text block]
  (let [text (str text)
        start (str/index-of text block-begin)
        stop (when start (str/index-of text block-end start))]
    (if (and start stop)
      (str (subs text 0 start) block (subs text (+ stop (count block-end))))
      (str text
           (cond (str/blank? text) "" (str/ends-with? text "\n") "\n" :else "\n\n")
           block "\n"))))

(defn cert-request
  "The body of POST /hub/server/ssh-cert: the agent user and its public key (one line)."
  [user public-key]
  {:user user :publicKey (str/trim (str public-key))})

(defn cert-line
  "cert as one OpenSSH ed25519 user certificate line ending in a newline, or nil when it is not one."
  [cert]
  (let [line (when (string? cert) (str/trim cert))]
    (when (and line (str/starts-with? line "ssh-ed25519-cert-v01@openssh.com ") (not (str/includes? line "\n")))
      (str line "\n"))))

(defn certificate-of
  "The certificate in the hub's answer {:ok :status :text}: {:certificate line}, or {:problem}."
  [{:keys [ok status text]}]
  (let [body (try (protocol/read-json text) (catch :default _ nil))
        cert (when (map? body) (cert-line (:certificate body)))]
    (if (and ok cert)
      {:certificate cert}
      {:problem (str "hub answered " status ": " (or (when (map? body) (:error body)) (str/trim (str text))))})))

;; ---- the edges: files and ssh-keygen, on the front's calls ----

(defn- paths [^js me]
  (let [dir (path/join (.-homedir me) ".ssh")]
    {:dir dir :key (path/join dir "claudia_agent") :cert (path/join dir "claudia_agent-cert.pub")
     :config (path/join dir "config")}))

(defn- write-file!
  "Writes text to file through a file beside it and a rename, so ssh never reads half of it."
  [file text file-mode]
  (let [tmp (str file ".claudia-tmp")]
    (fs/writeFileSync tmp text #js {:mode file-mode})
    (fs/chmodSync tmp file-mode)
    (fs/renameSync tmp file)))

(defn- ensure-key!
  "Promise, resolved once the key exists (made with ssh-keygen the first time, mode 600)."
  [{:keys [dir] key-file :key} user]
  (fs/mkdirSync dir #js {:recursive true :mode (mode "700")})
  (if (fs/existsSync key-file)
    (js/Promise.resolve (fs/chmodSync key-file (mode "600")))
    (js/Promise. (fn [done fail]
                   (cp/execFile "ssh-keygen" #js ["-q" "-t" "ed25519" "-N" "" "-C" user "-f" key-file]
                                (fn [err] (if err (fail err) (done (fs/chmodSync key-file (mode "600"))))))))))

(defn- install!
  "Writes the certificate and, when it changed, the managed block of ~/.ssh/config."
  [{:keys [cert config]} user certificate]
  (write-file! cert certificate (mode "644"))
  (let [old (if (fs/existsSync config) (fs/readFileSync config "utf8") "")
        new-text (merge-block old (config-block user))]
    (when (not= old new-text) (write-file! config new-text (mode "600")))))

(defn- public-key!
  "Promise of [status body] for POST /api/ssh/pubkey: {:publicKey line}, the key made when missing."
  [ps user]
  (.then (ensure-key! ps user)
         (fn [_] [200 {:publicKey (str/trim (fs/readFileSync (str (:key ps) ".pub") "utf8"))}])))

(defn- certificate!
  "[status body] for POST /api/ssh/cert {certificate}: written with the config block, or why not."
  [ps user {:keys [certificate]}]
  (if-let [line (cert-line certificate)]
    (do (install! ps user line)
        (js/console.log (str "ssh certificate: installed for " user))
        [200 {:ok true}])
    [400 {:error "certificate must be one ssh-ed25519-cert-v01@openssh.com line."}]))

(defn- answer [ps user sub body]
  (case sub
    "pubkey" (public-key! ps user)
    "cert" (certificate! ps user body)
    [404 {:error "Not found."}]))

(defn call
  "The front's POST /api/ssh/<sub> (pubkey, cert) on this runner's socket; 409 when this runner does
  not run as an agent user."
  [^js req ^js res sub]
  (let [^js me (os/userInfo)
        user (.-username me)]
    (if-not (agent-user/agent-user? user)
      (io/send-json res 409 {:error "This runner does not run as an agent user."})
      (-> (io/read-json req 65536)
          (.then #(answer (paths me) user sub %))
          (.then (fn [[status body]] (io/send-json res status body)))
          (.catch #(io/send-json res 500 {:error (.-message %)}))))))
