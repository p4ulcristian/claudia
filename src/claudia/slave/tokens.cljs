(ns claudia.slave.tokens
  "Settings' tokens. Claude: long-term tokens saved by name (`claude setup-token`), one of them in use
  (copied over CLAUDIA_TOKEN_FILE, which every chat reads on its next start); anyone with a profile
  can add, replace or switch; nobody can read one back. The saved ones live in CLAUDIA_TOKENS_DIR
  (default: `claude-tokens` beside the token file), the note of who switched to which beside the token
  file (`<token file>.json`). GitHub: this profile's own login, handed to gh (kept in this Linux
  user's ~/.config/gh); git uses gh as its credential helper and commits carry the account's name."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.shared.protocol :as p]))

(def ^:private env (.-env js/process))

(defn- token-file [] (.-CLAUDIA_TOKEN_FILE env))
(defn- tokens-dir []
  (or (not-empty (.-CLAUDIA_TOKENS_DIR env))
      (some-> (token-file) path/dirname (path/join "claude-tokens"))))
(defn- note-file [] (some-> (token-file) (str ".json")))

(defn- read-json-file [f] (try (p/read-json (fs/readFileSync f "utf8")) (catch :default _ nil)))

(defn- key-of [n] (str/replace (str/lower-case n) #"[^a-z0-9]+" "-"))

(defn saved
  "The saved tokens' names, who saved each and when; never the tokens."
  []
  (->> (try (vec (fs/readdirSync (tokens-dir))) (catch :default _ []))
       (filter #(str/ends-with? % ".json"))
       (keep #(read-json-file (path/join (tokens-dir) %)))
       (sort-by #(str/lower-case (str (:name %))))
       vec))

(defn state
  "{:inUse {by at name} or nil, :tokens [{name by at}]}"
  []
  {:inUse (when (some-> (token-file) fs/existsSync) (read-json-file (note-file)))
   :tokens (saved)})

(defn- clean-token [raw]
  (let [t (str/replace (str raw) #"\s+" "")]
    (when (re-matches #"^sk-ant-[A-Za-z0-9_-]{20,}$" t) t)))

(defn- clean-name [raw]
  (let [n (str/trim (str raw))]
    (when (re-matches #"^[A-Za-z0-9][A-Za-z0-9 ._-]{0,23}$" n) n)))

(defn- write-atomic! [f text mode]
  (fs/writeFileSync (str f ".new") text #js {:mode (js/parseInt mode 8)})
  (fs/renameSync (str f ".new") f))

(defn use!
  "Copies a saved token over the one in use, in one step (write beside, then rename)."
  [raw-name by]
  (let [n (clean-name raw-name)
        s (some #(when (= (key-of (:name %)) (key-of (str n))) %) (saved))]
    (cond
      (not (token-file)) {:error "No token file is set for this runner (CLAUDIA_TOKEN_FILE)."}
      (not s) {:error "No saved token by that name." :status 404}
      :else
      (let [token (str/trim (fs/readFileSync (path/join (tokens-dir) (key-of (:name s))) "utf8"))]
        (fs/mkdirSync (path/dirname (token-file)) #js {:recursive true})
        (write-atomic! (note-file) (p/write-json {:by by :at (js/Date.now) :name (:name s)}) "644")
        (write-atomic! (token-file) (str token "\n") "640")
        (js/console.log (str "Claude token switched to \"" (:name s) "\" by " by))
        (state)))))

(defn save!
  "Saves a token under a name (same name = replaced) and switches to it."
  [raw-name raw-token by]
  (let [n (clean-name raw-name)
        t (clean-token raw-token)]
    (cond
      (not n) {:error "Give it a name, like Paul (letters, digits, spaces, up to 24)."}
      (not t) {:error "That doesn't look like a Claude token (sk-ant-…). Make one with `claude setup-token`."}
      (not (tokens-dir)) {:error "No token file is set for this runner (CLAUDIA_TOKEN_FILE)."}
      :else
      (try
        (fs/mkdirSync (tokens-dir) #js {:recursive true :mode (js/parseInt "700" 8)})
        (let [f (path/join (tokens-dir) (key-of n))]
          (write-atomic! f (str t "\n") "600")
          (write-atomic! (str f ".json") (p/write-json {:name n :by by :at (js/Date.now)}) "600"))
        (js/console.log (str "Claude token \"" n "\" saved by " by))
        (use! n by)
        (catch :default e {:error (str "Couldn't save the token: " (.-message e))})))))

;; ---- GitHub ----

(defn- run
  "Promise of {:out :err :code} of a command (stdin: text or nil)."
  [cmd args stdin]
  (js/Promise.
   (fn [resolve]
     (let [^js c (cp/spawn cmd (clj->js args) #js {:stdio #js ["pipe" "pipe" "pipe"]})
           out (atom "") err (atom "")]
       (.on (.-stdout c) "data" #(swap! out str %))
       (.on (.-stderr c) "data" #(swap! err str %))
       (.on c "error" (fn [e] (resolve {:out "" :err (.-message e) :code -1})))
       (.on c "close" (fn [code] (resolve {:out (str/trim @out) :err (str/trim @err) :code code})))
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
                     (-> (run "gh" ["auth" "setup-git" "--hostname" "github.com"] nil)
                         (.then #(run "gh" ["api" "user" "--hostname" "github.com"] nil))
                         (.then (fn [{:keys [code out err]}]
                                  (if-not (zero? code)
                                    {:error (str "GitHub: " err)}
                                    (let [u (p/read-json out)]
                                      (-> (run "git" ["config" "--global" "user.name" (or (not-empty (:name u)) (:login u))] nil)
                                          (.then #(run "git" ["config" "--global" "user.email"
                                                              (str (:id u) "+" (:login u) "@users.noreply.github.com")] nil))
                                          (.then (fn [_]
                                                   (reset! known {:login (:login u) :name (:name u)})
                                                   {:user @known})))))))))))))))
