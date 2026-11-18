(ns claudia.root.args
  "The sudo shim's command line as data: `sudo [-n] [-E] [--] command args…`. -n and -E are taken
  and ignored (nothing asks for a password; root's environment is fixed); every other option is
  refused, since the root helper runs one command, never a shell or another user."
  (:require [clojure.string :as str]))

(def usage "usage: sudo [-n] [-E] [--] command [args...]")

(def no-shells "root shells are not offered; run the command itself")

(defn- ignored-flag?
  "-n, -E, or several of them in one word (-nE)."
  [a]
  (boolean (re-matches #"-[nE]+" a)))

(defn sudo-args
  "args (the shim's arguments) as {:argv [command args…]} or {:error text}."
  [args]
  (loop [[a & more :as left] (vec args)]
    (cond
      (empty? left) {:error usage}
      (= a "--") (if (seq more) {:argv (vec more)} {:error usage})
      (ignored-flag? a) (recur more)
      (and (str/starts-with? a "-") (not= a "-")) {:error (str "sudo: " a " is not offered: " no-shells " (" usage ")")}
      :else {:argv (vec left)})))
