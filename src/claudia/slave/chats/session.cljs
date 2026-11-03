(ns claudia.slave.chats.session
  "A chat's Claude Code session as data: what the host starts it with, the environment it runs in,
  and the names that tell when it must start again (its account, its tools' and prompt's versions)."
  (:require ["node:crypto" :as crypto]))

(defn- sha256-hex [s] (.digest (.update (crypto/createHash "sha256") (str s)) "hex"))

(defn account-of
  "The account (token) name a session runs on (src/account.ts): a hash of the token, so replacing it
  restarts each chat's Claude Code on its next message."
  [t]
  (str "token-" (subs (sha256-hex t) 0 8)))

(defn hash36
  "A short name for a text (its SHA-256's start in base 36): a version of the tools or the prompt."
  [s]
  (.toString (js/parseInt (subs (sha256-hex s) 0 12) 16) 36))

(defn run-env
  "The environment chats run with: the runner's own (env, a JS object), the token, and bin-dir (with
  Claudia's helpers) on the PATH."
  [^js env t bin-dir]
  (assoc (into {} (for [k (js-keys env)] [k (aget env k)]))
         "CLAUDE_CODE_OAUTH_TOKEN" t
         "PATH" (str bin-dir ":" (.-PATH env))))

(defn start-spec
  "What the host starts (resume? true: resumes) a chat's Claude Code with. prompt: the system
  prompt's addition; allowed: the allowed tools; edit-tools: the edit tools' matcher; worktrees?:
  edits and commands in Eye on Rainbow's worktrees are reported."
  [{:keys [cwd id resume? env prompt allowed model effort mcp edit-tools worktrees?]}]
  {:options (cond-> {:cwd cwd
                     :env env
                     (if resume? :resume :sessionId) id
                     :systemPrompt {:type "preset" :preset "claude_code" :append prompt}
                     :allowedTools allowed
                     ;; Same as the user's terminal Claude (defaultMode bypassPermissions).
                     :permissionMode "bypassPermissions"
                     :allowDangerouslySkipPermissions true
                     :includePartialMessages true}
              model (assoc :model model)
              effort (assoc :effort effort))
   :mcp mcp
   ;; A copy of each file before Claude's edit tools first touch it (the turn's changes).
   :hooks {:PreToolUse [{:matcher edit-tools}]
           ;; After an edit: a chat with no folder gets the edited file's project. Edits and commands
           ;; inside Eye on Rainbow's worktrees are reported (where configured).
           :PostToolUse [{:matcher (if worktrees? (str edit-tools "|Bash") edit-tools)}]}})
