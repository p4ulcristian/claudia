(ns claudia.slave.sentinel-key
  "This profile's Sentinel API key (README decision 85): the hub keeps each person's and hands it
  to their profile on every server (PUT /api/sentinel-key through the front, as them); it lives in
  ~/.config/sentinel/key (0600, this Linux user's only). Every chat started here gets it as
  SENTINEL_KEY (and SENTINEL_URL from CLAUDIA_SENTINEL_URL), so chats call Sentinel's API as the
  person with nobody approving each use; worktree reports send it too. Nothing here ever answers
  the key itself: only its hint and fingerprint."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [claudia.node.crypto :as crypto]
            [claudia.node.env :refer [env]]
            [clojure.string :as str]
            [claudia.shared.sentinel-key :as sk]))

(defn key-file [] (path/join (os/homedir) ".config" "sentinel" "key"))

(defn url "Sentinel's address (CLAUDIA_SENTINEL_URL, no trailing slash), or nil." []
  (some-> (env "CLAUDIA_SENTINEL_URL") (str/replace #"/$" "")))

;; ---- pure ----

(defn shown
  "What a call may learn of key k: its hint (first 8 characters) and fingerprint (the start of its
  SHA-256, so the hub tells whether this server has the key it keeps); both nil without one."
  [k]
  {:hint (sk/hint k) :fingerprint (when k (crypto/token-hash k))})

(defn put-plan
  "What a PUT /api/sentinel-key body asks: [:write key], [:remove] (no key, or an empty one), or
  [:error text] when the key has no Sentinel key's shape."
  [body]
  (let [raw (:key body)]
    (cond
      (or (nil? raw) (and (string? raw) (re-matches #"\s*" raw))) [:remove]
      (sk/clean-key raw) [:write (sk/clean-key raw)]
      :else [:error "That doesn't look like a Sentinel key (snt_…)."])))

;; ---- the file ----

(defn read-key
  "The key in the key file, or nil when there is none (or it has no Sentinel key's shape)."
  []
  (try (sk/clean-key (fs/readFileSync (key-file) "utf8")) (catch :default _ nil)))

(defn- write-key!
  "The key written through a rename, readable by this user only."
  [k]
  (let [f (key-file)]
    (fs/mkdirSync (path/dirname f) #js {:recursive true :mode (js/parseInt "700" 8)})
    (fs/writeFileSync (str f ".new") (str k "\n") #js {:mode (js/parseInt "600" 8)})
    (fs/chmodSync (str f ".new") (js/parseInt "600" 8))
    (fs/renameSync (str f ".new") f)))

(defn state "GET /api/sentinel-key: {:hint :fingerprint} of the key here." [] (shown (read-key)))

(defn put!
  "PUT /api/sentinel-key {key}: written (a key) or removed (none, empty) -> (state), or {:error}."
  [body]
  (let [[op v] (put-plan body)]
    (try
      (case op
        :write (write-key! v)
        :remove (fs/rmSync (key-file) #js {:force true})
        nil)
      (if (= op :error) {:error v} (state))
      (catch :default e {:error (str "Couldn't write the Sentinel key: " (.-message e)) :status 500}))))
