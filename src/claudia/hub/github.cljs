(ns claudia.hub.github
  "GitHub's webhook for this repository (POST /hub/github; no login: the gate's Caddy lets this path
  through). GitHub signs each delivery with the shared secret (X-Hub-Signature-256, HMAC-SHA256 with
  the secret in HUB_GITHUB_SECRET_FILE); a delivery can only make machines look at the main branch
  sooner: each one fetches and checks what it runs by itself (README, Hub operations: following main).
  - push to main: every server is poked (POST /api/update-check on its front).
  - a release run on main succeeded: the gate's root helper is asked to update the hub, by
    writing the run's commit into HUB_UPDATE_REQUEST, and every server is poked again (its slave
    release exists only now).
  Everything else (other branches, other workflows, other events) is answered 202 and ignored."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:http" :as http]
            [clojure.string :as str]
            [claudia.hub.config :as config]
            [claudia.shared.protocol :as protocol]))

(defn- env [k default] (let [v (aget js/process.env k)] (if (str/blank? v) default (str/trim v))))

(def branch "main")
(def workflow ".github/workflows/release.yml")

(defonce ^:private secret (atom nil))

(defn start! []
  (when-let [file (env "HUB_GITHUB_SECRET_FILE" nil)]
    (try (reset! secret (not-empty (str/trim (fs/readFileSync file "utf8"))))
         (catch :default e
           (js/console.error (str "github: no webhook secret (" (.-message e) "); /hub/github is off")))))
  (when-not @secret (js/console.log "github: /hub/github is off (no HUB_GITHUB_SECRET_FILE)")))

(defn signed?
  "The body's HMAC-SHA256 under the secret equals the header's (constant time)."
  [key ^js body header]
  (boolean
   (when (and key (string? header) (str/starts-with? header "sha256="))
     (let [want (-> (crypto/createHmac "sha256" key) (.update body) (.digest))
           got (js/Buffer.from (subs header 7) "hex")]
       (and (= (.-length got) (.-length want)) (crypto/timingSafeEqual got want))))))

(defn poke!
  "POST /api/update-check on one front; logs the answer."
  [server]
  (let [^js req (http/request #js {:host (:host server) :port (:port server) :method "POST"
                                   :path "/api/update-check" :timeout 5000
                                   :headers #js {"content-length" "0"}}
                              (fn [^js res]
                                (.resume res)
                                (js/console.log (str "github: poked " (:name server) ": " (.-statusCode res)))))]
    (.on req "timeout" #(.destroy req (js/Error. "timeout")))
    (.on req "error" #(js/console.log (str "github: poked " (:name server) ": " (.-message %))))
    (.end req)))

(defn- request-hub-update! [commit]
  (if-let [file (env "HUB_UPDATE_REQUEST" nil)]
    (do (fs/writeFileSync file (str commit "\n"))
        (js/console.log (str "github: hub image signed for " (subs commit 0 7) "; hub update asked")))
    (js/console.log "github: hub image signed; HUB_UPDATE_REQUEST not set, so no update asked")))

(defn handle
  "One delivery -> status. body: the raw bytes (Buffer)."
  [^js body headers]
  (let [repo (env "HUB_REPO" "p4ulcristian/claudia")]
    (cond
      (not @secret) 503
      (not (signed? @secret body (get headers "x-hub-signature-256"))) 401
      :else
      (let [d (try (protocol/read-json (.toString body "utf8")) (catch :default _ ::bad))
            event (get headers "x-github-event")
            run (:workflow_run d)]
        (cond
          (= d ::bad) 400
          (not= repo (get-in d [:repository :full_name])) 204
          :else
          (do (when (and (= event "push") (= (:ref d) (str "refs/heads/" branch)))
                (doseq [s (config/all-servers)] (poke! s)))
              (when (and (= event "workflow_run") (= (:action d) "completed")
                         (= "success" (:conclusion run)) (= branch (:head_branch run))
                         (= workflow (:path run))
                         (re-matches #"^[0-9a-f]{40}$" (str (:head_sha run))))
                (request-hub-update! (:head_sha run))
                ;; The slave release is published now: the servers look again (the poke on the
                ;; push came before it existed).
                (doseq [s (config/all-servers)] (poke! s)))
              202))))))
