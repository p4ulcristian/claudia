(ns claudia.hub.releases
  "The release builder's side of the hub (README decision 29). One machine at a time is the release
  builder: it reads GitHub, builds, signs each file with its own key (ssh-keygen -Y) and brings the
  files here; the hub keeps them and hands them out. The hub signs nothing and checks no signature:
  every server and the gate check the builder's signature (deploy/release-signers on GitHub's main)
  before they install, so the hub can hold an update back but never change one.

  - Pairing: deploy/release-builder/install.sh prints `claudia-builder:<host>:<secret>`; an admin
    pastes it in Settings (POST /hub/admin/builder). The secret is the builder's bearer token; the
    hub keeps only its SHA-256, and a new builder replaces the old one at once.
  - What to build: GitHub's webhook (claudia.hub.github) adds main's pushes and the heads of this
    repository's pull requests; the builder asks GET /hub/releases/wanted (held up to 25 s).
  - Files: PUT /hub/releases/files/<name> (builder) into HUB_RELEASES (default <data>/releases);
    GET /hub/releases/files/<name> (no login: they are signed, and the code is public).
  - Ready: POST /hub/releases/ready {commit, tree, deps}: once a tree's code and both platforms'
    node_modules are here with their signatures, servers and the gate are told to fetch it ahead."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:crypto" :as crypto]
            [clojure.string :as str]
            [claudia.node.crypto :refer [sha256-hex]]
            [claudia.node.env :refer [env]]
            [claudia.node.http :as io]
            [claudia.hub.announce :as announce]
            [claudia.hub.progress :as progress]
            [claudia.hub.store :as store]))

(def ^:private hex40 #"^[0-9a-f]{40}$")
(def ^:private max-file (* 400 1024 1024))
(def ^:private wait-ms 25000)
(def ^:private queue-max 12)

;; Pure ------------------------------------------------------------------------------------------

(defn parse-builder-key
  "A pasted builder key `claudia-builder:<host>:<secret>` -> {:host :secret}, or nil."
  [raw]
  (let [[tag host secret :as parts] (str/split (str/trim (str raw)) #":")]
    (when (and (= 3 (count parts)) (= "claudia-builder" tag)
               (re-matches #"^[A-Za-z0-9.-]{1,63}$" host)
               (re-matches #"^[A-Za-z0-9_-]{43}$" secret))
      {:host host :secret secret})))

(defn file-name?
  "Whether file (a name) is a release file the hub keeps: a tree's code, a lockfile's node_modules (Linux or
  a Mac's), or one of their signatures (.sig)."
  [file]
  (boolean (re-matches #"^claudia-(code-[0-9a-f]{40}|deps-[0-9a-f]{20}(-darwin-arm64)?)\.tar\.gz(\.sig)?$"
                       (str file))))

(defn needed-files
  "Every file a tree's release needs: its code, both platforms' node_modules, each signed."
  [tree deps]
  (for [base [(str "claudia-code-" tree) (str "claudia-deps-" deps) (str "claudia-deps-" deps "-darwin-arm64")]
        suffix [".tar.gz" ".tar.gz.sig"]]
    (str base suffix)))

(defn with-wanted
  "The queue with item ({:key :commit :kind}) in, newest first: one per key (a pull request's new
  head replaces its old one), at most queue-max."
  [queue item]
  (vec (take queue-max (cons item (remove #(= (:key %) (:key item)) queue)))))

(defn without-commit
  "The queue without the items for commit (built) or key (a closed pull request)."
  [queue {:keys [commit] k :key}]
  (vec (remove #(or (and commit (= commit (:commit %))) (and k (= k (:key %)))) queue)))

(defn stale-files
  "Of the files [name mtime], the ones to drop: code beyond the newest 20 trees, node_modules beyond
  the newest 3 lockfiles (a signature goes with its file)."
  [files]
  (let [base #(str/replace % #"\.sig$" "")
        group (fn [re n] (->> files (filter #(re-find re (first %))) (sort-by second >) (map (comp base first))
                              distinct (drop n) set))
        drop-set (into (group #"^claudia-code-" 20) (group #"^claudia-deps-[0-9a-f]{20}\.tar" 3))
        drop-set (into drop-set (group #"^claudia-deps-[0-9a-f]{20}-darwin" 3))]
    (vec (filter #(drop-set (base (first %))) files))))

;; State -----------------------------------------------------------------------------------------

(defonce ^:private queue (atom []))     ; what to build, newest first
(defonce ^:private waiters (atom #{}))  ; builders' held GET /hub/releases/wanted answers
(defonce ^:private dir (atom nil))

(defn start!
  "The folder release files live in (HUB_RELEASES, default <data>/releases)."
  [data-dir]
  (let [d (env "HUB_RELEASES" (path/join data-dir "releases"))]
    (try (fs/mkdirSync d #js {:recursive true})
         (catch :default e (js/console.error (str "releases: " d ": " (.-message e)))))
    (reset! dir d)))

(defn- answer-waiters! []
  (let [ws @waiters]
    (reset! waiters #{})
    (doseq [f ws] (f))))

(defn want!
  "Something to build (from GitHub's webhook): {:key \"main\"|\"pr-<n>\" :commit :kind}."
  [item]
  (when (re-matches hex40 (str (:commit item)))
    (swap! queue with-wanted (assoc item :at (js/Date.now)))
    (answer-waiters!)))

(defn forget!
  "Nothing more to build for k (a closed pull request's key)."
  [k]
  (swap! queue without-commit {:key k}))

;; Calls -----------------------------------------------------------------------------------------

(defn- bearer [^js req]
  (second (re-matches #"^Bearer ([A-Za-z0-9_-]{43})$" (str (aget (.-headers req) "authorization")))))

(defn- builder? [^js req]
  (let [b (store/release-builder) token (bearer req)]
    (boolean (when (and b token)
               (let [got (js/Buffer.from (sha256-hex token)) want (js/Buffer.from (:token-hash b))]
                 (and (= (.-length got) (.-length want)) (crypto/timingSafeEqual got want)))))))

(defn- fail [^js res status message] (io/send-json res status {:error message}))

(defn- wanted [^js req ^js res]
  (store/release-builder-seen!)
  (let [send! #(when-not (.-writableEnded res) (io/send-json res 200 {:builds @queue}))]
    (if (seq @queue)
      (send!)
      (let [t (js/setTimeout #(do (swap! waiters disj send!) (send!)) wait-ms)]
        (swap! waiters conj send!)
        (.on req "close" #(do (js/clearTimeout t) (swap! waiters disj send!)))))))

(defn- file-path [file] (path/join @dir file))

(defn- put-file
  "The builder brings one file: streamed to <file>.part, renamed when complete."
  [^js req ^js res file]
  (let [part (str (file-path file) ".part." (.toString (crypto/randomBytes 4) "hex"))
        out (fs/createWriteStream part)
        size (atom 0)
        abort! #(do (.destroy out) (fs/rm part #js {:force true} (fn [_])))]
    (.on req "data" #(when (> (swap! size + (.-length %)) max-file) (abort!) (fail res 413 "too large") (.destroy req)))
    (.on out "error" #(do (abort!) (when-not (.-headersSent res) (fail res 500 (.-message %)))))
    (.on out "finish" #(when (<= @size max-file)
                         (fs/renameSync part (file-path file))
                         (io/send-json res 200 {:ok true :size @size})))
    (.pipe req out)))

(defn- get-file
  "A release file for anyone (they are signed); HEAD answers whether it is here."
  [^js req ^js res file]
  (let [p (file-path file)
        st (try (fs/statSync p) (catch :default _ nil))]
    (if-not st
      (fail res 404 "not here")
      (do (.writeHead res 200 #js {"content-type" "application/gzip" "content-length" (.-size st)
                                   "cache-control" "public, max-age=31536000, immutable"})
          (if (= "HEAD" (.-method req)) (.end res) (.pipe (fs/createReadStream p) res))))))

(defn- here? [file] (fs/existsSync (file-path file)))

(defn- tidy!
  "Old release files go (stale-files)."
  []
  (let [files (for [f (fs/readdirSync @dir) :when (file-name? f)]
                [f (.-mtimeMs (fs/statSync (file-path f)))])]
    (doseq [[f] (stale-files files)] (fs/rmSync (file-path f) #js {:force true}))))

(defn- ready
  "A tree's release is complete: built off the queue, servers and the gate fetch it ahead."
  [^js res {:keys [commit tree deps]}]
  (cond
    (not (and (re-matches hex40 (str commit)) (re-matches hex40 (str tree)) (re-matches #"^[0-9a-f]{20}$" (str deps))))
    (fail res 400 "commit, tree (40 hex) and deps (20 hex) are needed")
    (not (every? here? (needed-files tree deps)))
    (fail res 409 (str "missing: " (str/join ", " (remove here? (needed-files tree deps)))))
    :else
    (do (swap! queue without-commit {:commit commit})
        (announce/poke-all! {:stage tree})
        (announce/ask-gate! (str "stage " tree))
        (tidy!)
        (io/send-json res 200 {:ok true}))))

(defn- builder-call
  "The builder's calls, after its token: what to build, a file, a tree done."
  [^js req ^js res method op file]
  (case [method op]
    ["GET" "wanted"] (wanted req res)
    ["POST" "ready"] (.then (io/read-json req 4096) #(ready res %))
    ["PUT" "files"] (put-file req res file)
    ["POST" "progress"] (.then (io/read-json req 4096)
                               #(do (progress/builder-said! (select-keys % [:commit :tree :state :description]))
                                    (io/send-json res 200 {:ok true})))
    (fail res 405 "not this way")))

(defn call
  "/hub/releases/…: files for anyone (GET, HEAD); everything else for the builder only."
  [^js req ^js res pathname]
  (let [[_ op file] (re-matches #"^/hub/releases/(wanted|ready|files|progress)(?:/([^/]+))?$" pathname)
        method (.-method req)]
    (cond
      (not @dir) (fail res 503 "no release folder")
      (not op) (fail res 404 "not found")
      (and (= op "files") (not (file-name? file))) (fail res 404 "not a release file")
      (and (= op "files") (#{"GET" "HEAD"} method)) (get-file req res file)
      (not (builder? req)) (fail res 401 "not the release builder")
      :else (builder-call req res method op file))))

;; Admin -----------------------------------------------------------------------------------------

(defn builder-state
  "What Settings shows of the release builder: {:host :pairedAt :seenAt}, or nil."
  []
  (when-let [b (store/release-builder)]
    {:host (:host b) :pairedAt (:paired-at b) :seenAt (:seen-at b)}))

(defn pair!
  "A pasted builder key makes that machine the release builder: [status body]."
  [raw]
  (if-let [{:keys [host secret]} (parse-builder-key raw)]
    (do (store/save-release-builder! host (sha256-hex secret))
        [200 {:builder (builder-state)}])
    [400 {:error "That isn't a builder key (claudia-builder:…). Run deploy/release-builder/install.sh on the builder to get one."}]))
