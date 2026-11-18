(ns claudia.shared.activity
  "The activity log's lines (README decision 59): one journal line per action, `activity {json}`,
  written by the front, the runner and the root helper alike. A line counts by the unit journald
  stamps on it (_SYSTEMD_UNIT, from the cgroup), never by its text."
  (:require [claudia.shared.protocol :as p]))

(def prefix "What every activity line starts with." "activity ")

(def input-max "The most characters of a tool's input a line keeps." 500)

(defn line
  "An entry (a map with :kind) as its journal line; nil values are left out."
  [entry]
  (str prefix (p/write-json (into {} (remove (comp nil? val)) entry))))

(defn cut
  "s as text in at most input-max characters (nil stays nil)."
  [s]
  (when (some? s)
    (let [s (str s)]
      (if (> (count s) input-max) (subs s 0 input-max) s))))

(defn argv-cut
  "A command's words for a line: at most 64, each cut to input-max (a journal line stays one line)."
  [argv]
  (when (sequential? argv) (mapv cut (take 64 argv))))

(defn root-entry
  "A root line's entry: which chat of which profile's agent user asked for argv, the decision, who
  allowed it (a person's email, when a person did) and the exit code (when it ran)."
  [{:keys [chat profile agent-user argv decision by exit]}]
  {:kind "root" :chat chat :profile profile :agentUser agent-user :argv (argv-cut argv) :decision decision
   :by by :exit exit})
