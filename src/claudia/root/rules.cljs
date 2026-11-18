(ns claudia.root.rules
  "The root helper's decisions as data (README decision 52): which folders it serves, whether a
  request is well formed, the no-tap list, what the front's answer means, the command's environment
  and exit code, and the log line."
  (:require [clojure.string :as str]
            [claudia.shared.activity :as activity]))

(defn profile-of
  "The profile an agent user belongs to (\"paul-claudia-agent\" -> \"paul\"), nil for any other user."
  [user]
  (second (re-matches #"([a-z][a-z0-9_-]*)-claudia-agent" (str user))))

(defn serve-dir?
  "Whether the helper listens in a folder of the socket folder: named after an agent user, and no
  one but its owner and group may enter it (mode: the folder's permission bits)."
  [dir-name mode]
  (boolean (and (profile-of dir-name) (zero? (bit-and mode 7)))))

(defn no-tap-list
  "The no-tap list file's text as a set of commands: one per line, ends trimmed, blank lines and
  lines starting with # left out."
  [text]
  (into #{} (comp (map str/trim) (remove str/blank?) (remove #(str/starts-with? % "#")))
        (str/split-lines (str text))))

(defn no-tap?
  "Whether argv runs without asking: joined by single spaces it is exactly a line of the list. An
  argument that is empty or holds white space never matches (it would read as other arguments)."
  [no-tap argv]
  (boolean (and (seq argv)
                (every? #(re-matches #"\S+" %) argv)
                (contains? no-tap (str/join " " argv)))))

(def ^:private max-args 1000)
(def ^:private max-argv-chars (* 128 1024))

(defn- argv-problem [argv]
  (cond (not (and (vector? argv) (seq argv))) "argv must be a list of words, the command first."
        (> (count argv) max-args) "Too many arguments."
        (not (every? string? argv)) "Every argument must be text."
        (some #(str/includes? % "\u0000") argv) "An argument holds a NUL."
        (str/blank? (first argv)) "No command."
        (> (reduce + (map count argv)) max-argv-chars) "The arguments are too long."))

(defn request-problem
  "Why the shim's request (a frame) cannot run, or nil when it is well formed."
  [{:keys [argv cwd chatKey env]}]
  (or (argv-problem argv)
      (when-not (or (nil? cwd) (string? cwd)) "cwd must be text.")
      (when-not (or (nil? chatKey) (and (string? chatKey) (<= (count chatKey) 200))) "chatKey must be short text.")
      (when-not (or (nil? env) (map? env)) "env must be an object.")))

(def passed-env
  "The caller's variables a root command keeps; everything else is root's own."
  #{"LANG" "LANGUAGE" "LC_ALL" "LC_CTYPE" "LC_MESSAGES" "TERM" "COLUMNS" "LINES" "NO_COLOR"})

(def root-path "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")

(defn child-env
  "The environment a root command runs with: root's own, SUDO_USER (the agent user) and the
  allowlisted variables of the request's env."
  [user env]
  (merge (into {} (for [[k v] env
                        :let [n (name k)]
                        :when (and (passed-env n) (string? v) (not (str/includes? v "\u0000")))]
                    [n v]))
         {"PATH" root-path "HOME" "/root" "USER" "root" "LOGNAME" "root" "SHELL" "/bin/sh" "SUDO_USER" user}))

(defn- text-or-nil [v] (when (and (string? v) (seq v)) v))

(defn verdict
  "What the front's answer (parsed JSON, nil when there was none or it was not JSON) decides:
  {:allow true :via \"switch\"|\"allow\"} only for exactly that, else {:allow false :why}; with the
  front's :chat (the chat's id) and :by (the email of the person who allowed it) when it says them."
  [answer]
  (let [said (when (map? answer) {:chat (text-or-nil (:chat answer)) :by (text-or-nil (:by answer))})]
    (into {} (remove (comp nil? val))
          (merge said
                 (if (and (map? answer) (true? (:allow answer)) (#{"switch" "allow"} (:via answer)))
                   {:allow true :via (:via answer)}
                   {:allow false
                    :why (or (when (map? answer) (text-or-nil (:why answer))) "The person did not allow it.")})))))

(defn exit-code
  "The shim's exit code for a command that ended with code, or was killed by signal number signo
  (128 + it, as a shell says)."
  [code signo]
  (cond (number? code) code
        (number? signo) (+ 128 signo)
        :else 1))

(defn log-line
  "One journal line for a root command (README decision 59): which agent user and chat, what, the
  decision, who allowed it and how it ended."
  [{:keys [user] :as entry}]
  (activity/line (activity/root-entry (assoc entry :agent-user user :profile (profile-of user)))))

(defn key-tag
  "A chat key as the log names it when the runner did not say the chat: its first 8 characters."
  [chat-key]
  (if (string? chat-key) (str "key:" (subs chat-key 0 (min 8 (count chat-key)))) "no key"))
