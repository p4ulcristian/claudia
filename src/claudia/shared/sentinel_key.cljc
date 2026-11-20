(ns claudia.shared.sentinel-key
  "A person's Sentinel API key (README decision 85): its shape and the hint the page sees. The hub
  keeps it, the runner writes it for its chats; both check it with these."
  (:require [clojure.string :as str]))

(def key-shape "snt_ and 43 base64url characters, as Sentinel makes them." #"^snt_[A-Za-z0-9_-]{43}$")

(defn clean-key
  "The key without whitespace, when it has a Sentinel key's shape, else nil."
  [raw]
  (let [k (str/replace (str raw) #"\s+" "")]
    (when (re-matches key-shape k) k)))

(defn hint
  "What the page may see of a key: its first 8 characters (snt_ and 4 more)."
  [k]
  (when (string? k) (subs k 0 (min 8 (count k)))))
