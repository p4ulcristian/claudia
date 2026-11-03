(ns claudia.slave.worktree
  "Tells Eye on Rainbow when a chat works inside one of its worktrees: an edit, a git command or any
  other command (a PostToolUse hook). Only where it is configured: CLAUDIA_WORKTREES_DIR (the
  worktrees' folder), CLAUDIA_EOR_URL and the EOR token file (CLAUDIA_EOR_TOKEN_FILE, default
  ~/.config/eor/token) must all be there; elsewhere nothing is sent. CLAUDIA_CHAT_URL, when set,
  makes the chat's link: its {chat} is replaced by the chat's id. The link is a log, not an owner: a chat may hop between worktrees."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.shared.protocol :as p]))

(def ^:private env (.-env js/process))
(def ^:private dir (some-> (.-CLAUDIA_WORKTREES_DIR env) not-empty path/resolve))
(def ^:private eor (some-> (.-CLAUDIA_EOR_URL env) not-empty (str/replace #"/$" "")))
(def ^:private token-file (or (.-CLAUDIA_EOR_TOKEN_FILE env) (path/join (os/homedir) ".config" "eor" "token")))
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

(defonce ^:private last-sent (atom {}))

(defn report!
  "A PostToolUse hook's input, for chat {:id :title}."
  [{:keys [id title]} {:keys [tool_name tool_input cwd]}]
  (when (and dir eor (fs/existsSync dir) (fs/existsSync token-file))
    (when-let [t (touch-of tool_name tool_input cwd)]
      (let [wt (second (str/split (subs (:path t) (count dir)) #"/"))
            k (str id " " wt " " (:kind t))
            now (js/Date.now)]
        (when (>= (- now (get @last-sent k 0)) same-again-ms)
          (swap! last-sent assoc k now)
          (-> (js/fetch (str eor "/api/worktrees/touches")
                        #js {:method "POST"
                             :headers #js {"content-type" "application/json"
                                           "x-eor-token" (str/trim (fs/readFileSync token-file "utf8"))}
                             :body (p/write-json (cond-> (assoc t :chat id :title (str title))
                                                   (seq (.-CLAUDIA_CHAT_URL env))
                                                   (assoc :url (str/replace (.-CLAUDIA_CHAT_URL env) "{chat}" id))))
                             :signal (js/AbortSignal.timeout 5000)})
              (.catch (fn [_] nil))))))))

(defn on? [] (boolean (and dir eor)))
