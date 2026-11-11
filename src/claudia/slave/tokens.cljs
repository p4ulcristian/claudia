(ns claudia.slave.tokens
  "Tokens. Claude: the hub keeps the saved ones (claudia.hub.tokens, README decision 10) and writes
  the one in use here through the front (PUT /api/claude-token/current, a call only the hub can
  make): CLAUDIA_TOKEN_FILE, which every chat reads on its next start, and the note of whose it is
  beside it (`<token file>.json`: name, by, at; the runner shows the name). The front writes them
  (on a shared server it runs as the page's own user): the token is readable by the runners' group
  (0640, in a folder setgid to that group). Every saved token comes the same way (PUT
  /api/claude-token/saved, README decision 16): one file each in `<token file>.d/<key>` and their
  names in `<token file>.saved.json`, so each chat runs on the one picked for it (the token in use
  is what a chat without a pick runs on). GitHub: this profile's own
  login, handed to gh (kept in this Linux user's ~/.config/gh); git uses gh as its credential
  helper and commits carry the account's name."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.shared.protocol :as p]))

(def ^:private env (.-env js/process))

(defn- token-file [] (.-CLAUDIA_TOKEN_FILE env))
(defn- note-file [] (some-> (token-file) (str ".json")))

(defn- read-json-file [f] (try (p/read-json (fs/readFileSync f "utf8")) (catch :default _ nil)))

(defn current
  "The token in use here, without the token: {:key :name :by :at} (nil fields when none came from the hub)."
  []
  (let [note (when (some-> (token-file) fs/existsSync) (read-json-file (note-file)))]
    {:key (:key note) :name (:name note) :by (:by note) :at (:at note)}))

(defn- write-atomic! [f text mode]
  (fs/writeFileSync (str f ".new") text #js {:mode (js/parseInt mode 8)})
  (fs/renameSync (str f ".new") f))

(defn current-problem
  "What is wrong with the hub's token in use before it is written here ({:error :status?}), or nil:
  the token text without whitespace is t."
  [{token-name :name at :at} t file]
  (cond
    (not file) {:error "No token file is set here (CLAUDIA_TOKEN_FILE)." :status 501}
    (not (re-matches #"^sk-ant-[A-Za-z0-9_-]{20,}$" t)) {:error "Not a Claude token."}
    (not (and (string? token-name) (seq token-name) (number? at))) {:error "name and at are needed."}
    :else nil))

(defn- write-files!
  "The note first (the runners read it again when the token file changes), then the token."
  [{token-key :key token-name :name by :by at :at} t]
  (fs/mkdirSync (path/dirname (token-file)) #js {:recursive true})
  (write-atomic! (note-file) (p/write-json {:key token-key :name token-name :by by :at at}) "644")
  (write-atomic! (token-file) (str t "\n") "640")
  (js/console.log (str "Claude token in use: \"" token-name "\" (from the hub, switched by " by ")")))

(defn write-current!
  "The hub's token in use, written over this server's: {:name :by :at :token} -> current, or {:error}."
  [body]
  (let [t (str/replace (str (:token body)) #"\s+" "")]
    (or (current-problem body t (token-file))
        (try (write-files! body t)
             (current)
             (catch :default e {:error (str "Couldn't write the token: " (.-message e)) :status 500})))))

;; ---- every saved token (a chat's own pick) ----

(defn- saved-dir [] (some-> (token-file) (str ".d")))
(defn- saved-list-file [] (some-> (token-file) (str ".saved.json")))

(def ^:private token-shape #"^sk-ant-[A-Za-z0-9_-]{20,}$")
(def ^:private key-shape #"^[a-z0-9-]{1,24}$")

(defn saved
  "The saved tokens here, without the tokens: [{:key :name :at}] (at: when the hub saved it)."
  []
  (vec (for [t (:tokens (some-> (saved-list-file) read-json-file))
             :when (and (string? (:key t)) (re-matches key-shape (:key t)))]
         (select-keys t [:key :name :at]))))

(defn saved-token
  "The saved token with key k (a chat's pick), or nil when none is kept here."
  [k]
  (when (and (string? k) (re-matches key-shape k) (some #(= k (:key %)) (saved)))
    (try (not-empty (str/trim (fs/readFileSync (path/join (saved-dir) k) "utf8"))) (catch :default _ nil))))

(defn clean-saved
  "The hub's saved tokens as they are written ([{:key :name :at :token}], whitespace out of each
  token), or nil when one of them is not a key, a name, a time and a Claude token."
  [tokens]
  (let [rows (for [t tokens] (assoc (select-keys t [:key :name :at])
                                    :token (str/replace (str (:token t)) #"\s+" "")))
        ok? (fn [{k :key n :name at :at t :token}]
              (and (string? k) (re-matches key-shape k) (string? n) (seq n) (number? at)
                   (re-matches token-shape t)))]
    (when (and (sequential? tokens) (every? ok? rows)) (vec rows))))

(defn- write-saved-files!
  "Each token's file, then the list (the runners read it again when it changes), then the files of
  tokens no longer saved go."
  [rows]
  (let [dir (saved-dir)]
    (fs/mkdirSync dir #js {:recursive true :mode (js/parseInt "750" 8)})
    (doseq [{k :key t :token} rows] (write-atomic! (path/join dir k) (str t "\n") "640"))
    (write-atomic! (saved-list-file) (p/write-json {:tokens (mapv #(dissoc % :token) rows)}) "644")
    (doseq [f (fs/readdirSync dir) :when (not (some #(= f (:key %)) rows))]
      (fs/rmSync (path/join dir f) #js {:force true}))))

(defn write-saved!
  "The hub's saved tokens, written over this server's: {:tokens [{:key :name :at :token}]} ->
  {:tokens (saved)}, or {:error}."
  [body]
  (let [rows (clean-saved (:tokens body))]
    (cond
      (not (token-file)) {:error "No token file is set here (CLAUDIA_TOKEN_FILE)." :status 501}
      (not rows) {:error "tokens: each needs key, name, at and a Claude token."}
      :else (try (write-saved-files! rows)
                 {:tokens (saved)}
                 (catch :default e {:error (str "Couldn't write the tokens: " (.-message e)) :status 500})))))

;; ---- GitHub ----

(defn- run
  "Promise of {:out :err :code} of a command (stdin: text or nil)."
  [cmd args stdin]
  (js/Promise.
   (fn [done]
     (let [^js c (cp/spawn cmd (clj->js args) #js {:stdio #js ["pipe" "pipe" "pipe"]})
           out (atom "") err (atom "")]
       (.on (.-stdout c) "data" #(swap! out str %))
       (.on (.-stderr c) "data" #(swap! err str %))
       (.on c "error" (fn [e] (done {:out "" :err (.-message e) :code -1})))
       (.on c "close" (fn [code] (done {:out (str/trim @out) :err (str/trim @err) :code code})))
       (if stdin (.end (.-stdin c) stdin) (.end (.-stdin c)))))))

(defonce ^:private known (atom ::unknown))

(defn github-user
  "Promise of {:login :name} or nil."
  []
  (if (not= ::unknown @known)
    (js/Promise.resolve @known)
    (-> (run "gh" ["api" "user" "--hostname" "github.com"] nil)
        (.then (fn [{:keys [out code]}]
                 (reset! known (when (zero? code)
                                 (let [u (p/read-json out)] {:login (:login u) :name (:name u)}))))))))

(defn commit-identity
  "The name and email commits carry for a GitHub user (its API answer): [name email]."
  [u]
  [(or (not-empty (:name u)) (:login u))
   (str (:id u) "+" (:login u) "@users.noreply.github.com")])

(defn- use-identity!
  "Sets git's commit name and email to the GitHub user u's; promise of {:user}."
  [u]
  (let [[user-name email] (commit-identity u)]
    (-> (run "git" ["config" "--global" "user.name" user-name] nil)
        (.then #(run "git" ["config" "--global" "user.email" email] nil))
        (.then (fn [_]
                 (reset! known {:login (:login u) :name (:name u)})
                 {:user @known})))))

(defn- logged-in!
  "After gh took the token: gh as git's credential helper, then the user's identity for commits."
  []
  (-> (run "gh" ["auth" "setup-git" "--hostname" "github.com"] nil)
      (.then #(run "gh" ["api" "user" "--hostname" "github.com"] nil))
      (.then (fn [{:keys [code out err]}]
               (if-not (zero? code)
                 {:error (str "GitHub: " err)}
                 (use-identity! (p/read-json out)))))))

(defn set-github-token!
  "Promise of {:user} or {:error}."
  [raw]
  (let [t (str/replace (str raw) #"\s+" "")]
    (if-not (re-matches #"^(github_pat_|gh[pousr]_)[A-Za-z0-9_]{20,}$" t)
      (js/Promise.resolve {:error "That doesn't look like a GitHub token (github_pat_… or ghp_…)."})
      (-> (run "gh" ["auth" "login" "--hostname" "github.com" "--git-protocol" "https" "--with-token"] t)
          (.then (fn [{:keys [code err out]}]
                   (if-not (zero? code)
                     {:error (str "GitHub refused the token: " (or (not-empty err) out))}
                     (logged-in!))))))))
