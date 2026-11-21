(ns claudia.node.reads
  "The fixed reads System needs (README decision 96, PROTOCOL.md \"The root helper's read socket\"),
  as data: every program and argument is a constant here but a checked unit name, user name and line
  count. The root helper runs them as root for the front; without it the front runs the same ones
  as its own user (what that user may see). Pure: the argv, the checks, the log's rule."
  (:require [clojure.string :as str]
            [claudia.node.events :as events]))

(def kinds
  "The reads the helper's read socket answers."
  #{"ports" "containers" "units" "pids" "journal" "coredumps" "unit-log"})

(def ports-argv ["ss" "-Htlnup"])

(def podman-argv ["podman" "ps" "-a" "--format" "json"])

(def docker-argv ["docker" "ps" "-a" "--no-trunc" "--format" "{{json .}}"])

(def journal-argv
  "The last 7 days' failed units and out-of-memory kills, a JSON object a line, only the fields
  System reads."
  (into ["journalctl" "--since=-7d" "-o" "json" "--no-pager" "--output-fields=MESSAGE,MESSAGE_ID,UNIT,USER_UNIT"]
        (map #(str "MESSAGE_ID=" %) (sort (keys events/message-ids)))))

(def coredumps-argv ["coredumpctl" "list" "--json=short" "--since=-7d" "--no-pager"])

(def system-units-argv ["systemctl" "list-units" "--type=service" "--output=json" "--no-pager"])

(defn user-units-argv
  "One user manager's services; user nil: the caller's own manager."
  [user]
  (if user
    ["systemctl" "--user" "-M" (str user "@") "list-units" "--type=service" "--output=json" "--no-pager"]
    ["systemctl" "--user" "list-units" "--type=service" "--output=json" "--no-pager"]))

(defn show-argv
  "systemctl show for units (each a checked unit name) of the system (user nil), a user's manager
  (user), or the caller's own manager (user :own)."
  [units user show-properties]
  (vec (concat ["systemctl"]
               (cond (= user :own) ["--user"] user ["--user" "-M" (str user "@")])
               ["show" "-p" show-properties "--"] units)))

(def unit-types
  #{"service" "socket" "timer" "scope" "slice" "mount" "path" "target" "swap" "automount" "device"})

(defn unit-name?
  "A unit name System passes on: [A-Za-z0-9@._:-], 1 to 200 characters, not starting with a dash,
  ending in a unit type."
  [s]
  (boolean (and (string? s)
                (re-matches #"^[A-Za-z0-9@._:][A-Za-z0-9@._:-]{0,199}$" s)
                (unit-types (last (str/split s #"\."))))))

(defn user-name?
  "A Linux user name System passes on."
  [s]
  (boolean (and (string? s) (re-matches #"^[a-z_][a-z0-9_-]{0,31}$" s))))

(defn unit-log-problem
  "Why a unit-log ask {:unit :scope :user :n} cannot be read, or nil."
  [{:keys [unit scope user n]}]
  (cond (not (unit-name? unit)) "unit must be a unit name ([A-Za-z0-9@._:-], ending in a unit type)."
        (not (#{"system" "user"} scope)) "scope must be system or user."
        (and (= scope "user") (not (user-name? user))) "user must be a user name."
        (not (and (integer? n) (<= 1 n 200))) "n must be 1 to 200."))

(defn read-problem
  "Why a frame sent to the read socket cannot be answered, or nil."
  [{:keys [read] :as frame}]
  (cond (not (map? frame)) "The request is not a JSON object."
        (not (kinds read)) (str "read must be one of " (str/join ", " (sort kinds)) ".")
        (= read "unit-log") (unit-log-problem frame)))

(defn unit-log-argv
  "journalctl of one checked unit: n lines, oldest first, a JSON object a line; a user's unit by
  that user's uid."
  [{:keys [unit scope n]} uid]
  (into ["journalctl"]
        (concat (if (= scope "user") [(str "--user-unit=" unit) (str "_UID=" uid)] [(str "--unit=" unit)])
                ["-n" (str n) "-o" "json" "--no-pager" "--output-fields=MESSAGE,PRIORITY"])))

(def log-every-ms "How often the helper logs a read of one kind (unit-log: every one)." (* 10 60 1000))

(defn log-read?
  "Whether a read of kind is logged now, given when each kind was last logged (logged)."
  [logged kind now]
  (or (= kind "unit-log") (not (contains? logged kind)) (> (- now (get logged kind)) log-every-ms)))

(defn read-entry
  "A read's activity entry (README decision 59)."
  [{:keys [read unit scope user n]}]
  {:kind "read" :read read :unit unit :scope scope :user user :n n})
