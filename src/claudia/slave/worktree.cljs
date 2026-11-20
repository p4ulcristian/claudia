(ns claudia.slave.worktree
  "Tells Sentinel when a chat works inside one of its worktrees: an edit, a git command or any
  other command (a PostToolUse hook). Only where it is configured: CLAUDIA_WORKTREES_DIR (the
  worktrees' folder) and CLAUDIA_SENTINEL_URL must be there, and a way to sign in: the person's
  Sentinel key (~/.config/sentinel/key, sent as Authorization: Bearer, README decision 85) or, while
  the old token is retired and only when there is no key, the token file (CLAUDIA_SENTINEL_TOKEN_FILE,
  default ~/.config/sentinel/token, sent as x-sentinel-token); elsewhere nothing is sent. CLAUDIA_CHAT_URL, when set,
  makes the chat's link: its {chat} is replaced by the chat's id. The link is a log, not an owner: a chat may hop between worktrees."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.shared.protocol :as p]
            [claudia.slave.sentinel-key :as sentinel-key]))

(def ^:private env (.-env js/process))
(def ^:private dir (some-> (.-CLAUDIA_WORKTREES_DIR env) not-empty path/resolve))
(def ^:private sentinel (sentinel-key/url))
(def ^:private token-file (or (.-CLAUDIA_SENTINEL_TOKEN_FILE env) (path/join (os/homedir) ".config" "sentinel" "token")))
(def ^:private same-again-ms 30000)

(defn- inside? [p]
  (let [r (path/resolve (str p))]
    (or (= r dir) (str/starts-with? r (str dir "/")))))

(defn- escape-re [s] (str/replace s #"[.*+?^${}()|\[\]\\]" "\\$&"))

(defn touch-of
  "What a finished tool call touched, or nil when it was not in a worktree."
  [tool input cwd]
  (if (= tool "Bash")
    (let [command (str (:command input))
          kind (if (re-find #"(^|[;&|(\s])git\s" command) "git" "command")]
      (cond
        (and cwd (inside? cwd)) {:kind kind :path cwd}
        :else (when-let [named (re-find (re-pattern (str (escape-re dir) "/[^\\s'\"]+")) command)]
                {:kind kind :path named})))
    (let [f (or (:file_path input) (:notebook_path input))]
      (when (and (string? f) (str/starts-with? f "/") (inside? f))
        {:kind "edit" :path f}))))

(defn auth-headers
  "How a report signs in: with the person's Sentinel key k when there is one, else with the old
  token t (the transition); nil with neither."
  [k t]
  (cond
    k {"authorization" (str "Bearer " k)}
    (seq t) {"x-sentinel-token" t}
    :else nil))

(defn- old-token [] (try (not-empty (str/trim (fs/readFileSync token-file "utf8"))) (catch :default _ nil)))

(defn- sign-in [] (let [k (sentinel-key/read-key)] (auth-headers k (when-not k (old-token)))))

(defonce ^:private last-sent (atom {}))

(defn- send! [auth body]
  (-> (js/fetch (str sentinel "/api/worktrees/touches")
                #js {:method "POST"
                     :headers (clj->js (assoc auth "content-type" "application/json"))
                     :body (p/write-json body)
                     :signal (js/AbortSignal.timeout 5000)})
      (.catch (fn [_] nil))))

(defn report!
  "A PostToolUse hook's input, for chat {:id :title}."
  [{:keys [id title]} {:keys [tool_name tool_input cwd]}]
  (when (and dir sentinel (fs/existsSync dir))
    (when-let [t (touch-of tool_name tool_input cwd)]
      (let [wt (second (str/split (subs (:path t) (count dir)) #"/"))
            k (str id " " wt " " (:kind t))
            now (js/Date.now)
            auth (sign-in)]
        (when (and auth (>= (- now (get @last-sent k 0)) same-again-ms))
          (swap! last-sent assoc k now)
          (send! auth (cond-> (assoc t :chat id :title (str title))
                        (seq (.-CLAUDIA_CHAT_URL env))
                        (assoc :url (str/replace (.-CLAUDIA_CHAT_URL env) "{chat}" id)))))))))

(defn on? [] (boolean (and dir sentinel)))
