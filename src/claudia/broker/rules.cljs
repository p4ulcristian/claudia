(ns claudia.broker.rules
  "The key broker's decisions as plain data in, data out (README decision 66): which 1Password
  references it reads, what an approval is for, when a challenge or an enrollment code still holds,
  and what a passkey looks like to the page. No I/O here."
  (:require [clojure.string :as str]))

(def challenge-ms "How long a passkey challenge stays open (the person taps within this)." (* 5 60 1000))

(def code-ms "How long an enrollment code made on the gate holds." (* 30 60 1000))

(def code-tries "Wrong tries before an enrollment code is dropped." 5)

(defn vault-ref?
  "The reference names a field in the broker's one vault: op://<vault>/<item>/<field>[/…], no
  empty part, no query (op's ?attribute= forms are not needed and not read)."
  [vault ref]
  (let [prefix (str "op://" vault "/")]
    (boolean (and (string? vault) (seq vault) (string? ref)
                  (str/starts-with? ref prefix)
                  (not (str/includes? ref "?"))
                  (let [parts (str/split (subs ref (count prefix)) #"/" -1)]
                    (and (>= (count parts) 2) (every? seq parts)))))))

(defn pubkey?
  "A runner's one-time public key: 32 bytes as standard base64 with padding (44 characters)."
  [s]
  (boolean (and (string? s) (re-matches #"^[A-Za-z0-9+/]{43}=$" s))))

(defn read-purpose
  "What approving a secret read is for, from the page's body: {:kind \"read\" :ref :pubkey
  :request}, or {:error text}."
  [vault {:keys [ref pubkey request]}]
  (cond
    (not (vault-ref? vault ref)) {:error (str "Only references in the vault \"" vault "\" are read (op://" vault "/item/field).")}
    (not (pubkey? pubkey)) {:error "pubkey must be 32 bytes as standard base64."}
    (not (and (string? request) (<= 1 (count request) 100))) {:error "request (the ask's id) is needed."}
    :else {:kind "read" :ref ref :pubkey pubkey :request request}))

(defn purpose
  "What an approval is for, from the page's body: a read, adding a passkey, or removing one."
  [vault {:keys [kind id] :as body}]
  (case kind
    "read" (read-purpose vault body)
    "add-passkey" {:kind "add-passkey"}
    "remove-passkey" (if (and (string? id) (seq id)) {:kind "remove-passkey" :id id} {:error "id (the passkey) is needed."})
    {:error "kind must be read, add-passkey or remove-passkey."}))

(defn open-challenge
  "The pending challenges with one more: id -> {:email :challenge :purpose :expires}. Expired ones
  are dropped on the way."
  [pending id entry now]
  (-> (into {} (remove (fn [[_ e]] (< (:expires e) now))) pending)
      (assoc id (assoc entry :expires (+ now challenge-ms)))))

(defn take-challenge
  "[entry pending-without-it]: the entry when it is this person's, still open and for kind
  (\"read\", \"add-passkey\", \"remove-passkey\" or \"register\"); entry nil otherwise. A challenge is
  used once: it leaves pending whether it matched or not."
  [pending id email kind now]
  (let [e (get pending id)]
    [(when (and e (= email (:email e)) (= kind (get-in e [:purpose :kind])) (>= (:expires e) now)) e)
     (dissoc pending id)]))

(defn code-check
  "An enrollment code's row against a try: :ok, :wrong (tries left), :spent (too many tries or
  expired) or :none (no code for this person)."
  [row now hash-ok?]
  (cond
    (nil? row) :none
    (or (< (:expires row) now) (>= (:tries row) code-tries)) :spent
    hash-ok? :ok
    :else :wrong))

(defn passkey-view
  "A passkey as the page lists it: no key material."
  [row]
  (select-keys row [:id :name :createdAt :usedAt]))

(defn passkey-name
  "The name a person gave a passkey, trimmed and short; a default when blank."
  [s]
  (let [t (str/trim (str s))]
    (if (seq t) (subs t 0 (min 60 (count t))) "Passkey")))

(defn email?
  "A lower-case email the hub vouched for."
  [s]
  (boolean (and (string? s) (re-matches #"^[^\s@,A-Z]+@[^\s@,A-Z]+$" s))))

(defn integration-version
  "The version named to 1Password: the commit's first 7 characters (1Password allows at most 20),
  or dev."
  [commit]
  (if (seq commit) (subs commit 0 (min 7 (count commit))) "dev"))
