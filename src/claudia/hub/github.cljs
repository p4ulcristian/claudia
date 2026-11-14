(ns claudia.hub.github
  "GitHub's webhook for this repository (POST /hub/github; no login: the gate's Caddy lets this path
  through). GitHub signs each delivery with the shared secret (X-Hub-Signature-256, HMAC-SHA256 with
  the secret in HUB_GITHUB_SECRET_FILE); a delivery can only make machines look at the main branch
  sooner: each one fetches and checks what it runs by itself (README, Hub operations: following main).
  - push to main: every server is poked with the commit (POST /api/update-check {main} on its
    front: GitHub's API names it a few seconds after the webhook, so they wait for it), and the gate's root
    helper is asked to move the hub to the pushed commit's tree (a line `update <commit> <tree>`
    added to HUB_UPDATE_REQUEST). When that tree was staged before the merge, both only swap.
  - a stage run succeeded (stage.yml; its title names the tree it published): servers and the gate
    are told to fetch that tree ahead (`{stage: tree}`; `stage <tree>`), and they look at main
    again (a tree published only after the push installs now).
  - the release builder (README decision 29) is asked to build main's pushes and the heads of this
    repository's pull requests (opened, pushed to, reopened; a closed one is dropped); it announces
    each tree itself once its files are on the hub (claudia.hub.releases).
  Everything else (other branches, other workflows, other events) is answered 202 and ignored."
  (:require ["node:crypto" :as crypto]
            [claudia.node.env :refer [env]]
            ["node:fs" :as fs]
            [clojure.string :as str]
            [claudia.hub.announce :as announce]
            [claudia.hub.progress :as progress]
            [claudia.hub.releases :as releases]
            [claudia.shared.protocol :as protocol]))

(def branch "main")
(def stage-workflow ".github/workflows/stage.yml")

(defonce ^:private secret (atom nil))

(defn start! []
  (when-let [file (env "HUB_GITHUB_SECRET_FILE" nil)]
    (try (reset! secret (not-empty (str/trim (fs/readFileSync file "utf8"))))
         (catch :default e
           (js/console.error (str "github: no webhook secret (" (.-message e) "); /hub/github is off")))))
  (when-not @secret (js/console.log "github: /hub/github is off (no HUB_GITHUB_SECRET_FILE)")))

(defn signed?
  "The body's HMAC-SHA256 under the secret equals the header's (constant time)."
  [secret-key ^js body header]
  (boolean
   (when (and secret-key (string? header) (str/starts-with? header "sha256="))
     (let [want (-> (crypto/createHmac "sha256" secret-key) (.update body) (.digest))
           got (js/Buffer.from (subs header 7) "hex")]
       (and (= (.-length got) (.-length want)) (crypto/timingSafeEqual got want))))))

(defn main-push?
  "A delivery for a push to the main branch."
  [event d]
  (and (= event "push") (= (:ref d) (str "refs/heads/" branch))))

(def ^:private hex40 #"^[0-9a-f]{40}$")

(defn push-target
  "A push to main's commit and git tree, when both are named: [commit tree], else nil."
  [d]
  (let [commit (str (:after d)) tree (str (get-in d [:head_commit :tree_id]))]
    (when (and (re-matches hex40 commit) (re-matches hex40 tree)) [commit tree])))

(defn staged-tree
  "The tree a succeeded stage run published (its title: \"stage <tree>\"), else nil."
  [event d]
  (let [run (:workflow_run d)]
    (when (and (= event "workflow_run") (= (:action d) "completed")
               (= "success" (:conclusion run)) (= stage-workflow (:path run)))
      (second (re-matches #"^stage ([0-9a-f]{40})$" (str (:display_title run)))))))

(defn pr-head
  "A pull request from this repository opened or moved on: {:key \"pr-<n>\" :commit :kind \"pr\"};
  closed: {:key :closed true}; else nil (a fork's pull request is never built)."
  [event d repo]
  (let [p (:pull_request d) n (:number p) head (str (get-in p [:head :sha]))]
    (when (and (= event "pull_request") (integer? n) (= repo (get-in p [:head :repo :full_name])))
      (cond (#{"opened" "synchronize" "reopened"} (:action d))
            (when (re-matches hex40 head) {:key (str "pr-" n) :commit head :kind "pr"})
            (= "closed" (:action d)) {:key (str "pr-" n) :closed true}))))

(defn- act! [event d repo]
  (when (main-push? event d)
    (let [[commit tree] (push-target d)]
      (announce/poke-all! (when commit {:main commit}))
      (when commit
        (announce/ask-gate! (str "update " commit " " tree))
        (releases/want! {:key "main" :commit commit :kind "main"})
        (progress/main-pushed! commit tree (first (str/split-lines (str (get-in d [:head_commit :message]))))))))
  (when-let [tree (staged-tree event d)]
    (announce/poke-all! {:stage tree})
    (announce/ask-gate! (str "stage " tree)))
  (when-let [{:keys [closed] :as head} (pr-head event d repo)]
    (if closed (releases/forget! (:key head)) (releases/want! head))))

(defn handle
  "One delivery -> status. body: the raw bytes (Buffer)."
  [^js body headers]
  (let [repo (env "HUB_REPO" "p4ulcristian/claudia")]
    (cond
      (not @secret) 503
      (not (signed? @secret body (get headers "x-hub-signature-256"))) 401
      :else
      (let [d (try (protocol/read-json (.toString body "utf8")) (catch :default _ ::bad))]
        (cond
          (= d ::bad) 400
          (not= repo (get-in d [:repository :full_name])) 204
          :else (do (act! (get headers "x-github-event") d repo) 202))))))
