(ns claudia.broker.rules
  "The key broker's decisions as plain data in, data out (README decision 66): which 1Password
  references it reads, what an approval is for, when a challenge or an enrollment code still holds,
  and what a passkey looks like to the page. No I/O here."
  (:require [clojure.string :as str]
            [claudia.shared.root-grant :as root-grant]))

(def challenge-ms "How long a passkey challenge stays open (the person taps within this)." (* 5 60 1000))

(def code-ms "How long an enrollment code made on the gate holds." (* 30 60 1000))

(def code-tries "Wrong tries before an enrollment code is dropped." 5)

(defn email?
  "A lower-case email the hub vouched for."
  [s]
  (boolean (and (string? s) (re-matches #"^[^\s@,A-Z]+@[^\s@,A-Z]+$" s))))

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
  :request :chat}, or {:error text}. chat (\"<server>/<chat id>\") is what the hour of trust
  after the tap is bound to."
  [vault {:keys [ref pubkey request chat]}]
  (cond
    (not (vault-ref? vault ref)) {:error (str "Only references in the vault \"" vault "\" are read (op://" vault "/item/field).")}
    (not (pubkey? pubkey)) {:error "pubkey must be 32 bytes as standard base64."}
    (not (and (string? request) (<= 1 (count request) 100))) {:error "request (the ask's id) is needed."}
    (not (and (string? chat) (<= 3 (count chat) 200))) {:error "chat (<server>/<chat id>) is needed."}
    :else {:kind "read" :ref ref :pubkey pubkey :request request :chat chat}))

(defn grant-changes
  "An admin's changes to who may use which key, from the page: [{:item :email :on}] (1 to 200,
  each a key's id, a lower-case email and true or false), or nil when any is malformed."
  [changes]
  (when (and (sequential? changes) (<= 1 (count changes) 200)
             (every? (fn [{:keys [item email on]}]
                       (and (string? item) (<= 1 (count item) 100) (email? email) (boolean? on)))
                     changes))
    (mapv #(select-keys % [:item :email :on]) changes)))

(defn purpose
  "What an approval is for, from the page's body: a read, adding or removing a passkey, an admin's
  changes to who may use which key, or root for one chat (README decision 79)."
  [vault {:keys [kind id changes] :as body}]
  (case kind
    "read" (read-purpose vault body)
    "grants" (if-let [c (grant-changes changes)] {:kind "grants" :changes c} {:error "changes: 1 to 200 of {item, email, on}."})
    "add-passkey" {:kind "add-passkey"}
    "remove-passkey" (if (and (string? id) (seq id)) {:kind "remove-passkey" :id id} {:error "id (the passkey) is needed."})
    "root" (root-grant/purpose body)
    {:error "kind must be read, add-passkey, remove-passkey, grants or root."}))

(defn open-challenge
  "The pending challenges with one more: id -> {:email :challenge :purpose :expires}. Expired ones
  are dropped on the way."
  [pending id entry now]
  (-> (into {} (remove (fn [[_ e]] (< (:expires e) now))) pending)
      (assoc id (assoc entry :expires (+ now challenge-ms)))))

(defn take-challenge
  "[entry pending-without-it]: the entry when it is this person's, still open and for kind
  (\"read\", \"add-passkey\", \"remove-passkey\", \"grants\", \"root\" or \"register\"); entry nil otherwise. A challenge is
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

(defn integration-version
  "The version named to 1Password: the commit's first 7 characters (1Password allows at most 20),
  or dev."
  [commit]
  (if (seq commit) (subs commit 0 (min 7 (count commit))) "dev"))

(defn ref-item
  "The item part of a vault reference (its title or id): op://<vault>/<item>/<field> -> item."
  [vault ref]
  (when (vault-ref? vault ref)
    (first (str/split (subs ref (count (str "op://" vault "/"))) #"/"))))

(defn find-item
  "The vault item a reference names: by id first, else by title; nil when none."
  [items item]
  (or (some #(when (= item (:id %)) %) items)
      (some #(when (= item (:title %)) %) items)))

(defn may-use?
  "An admin may use every key; anyone else only the keys an admin ticked for them."
  [admin? granted-ids item-id]
  (boolean (or admin? (contains? (set granted-ids) item-id))))

(def standing-ms "How long a tap lets one chat use one key again without asking." (* 60 60 1000))

(defn standing-key
  "What an hour of trust is bound to: the person, the chat and the key (its item id)."
  [email chat item-id]
  [email chat item-id])

(defn add-standing
  "The hours of trust with one more, from now; ended ones dropped."
  [standing k now]
  (-> (into {} (remove (fn [[_ until]] (< until now))) standing)
      (assoc k (+ now standing-ms))))

(defn standing?
  "Whether the chat may still use the key without a tap."
  [standing k now]
  (boolean (some-> (get standing k) (>= now))))

;; The keys a person may use, as references an agent passes to run_with_secret (README decision 77).

(defn usable-items
  "The vault's items this person may use: every item for an admin, else the ticked ones."
  [admin? granted-ids items]
  (filterv #(may-use? admin? granted-ids (:id %)) items))

(defn item-labels
  "An item's field labels and section names as plain data: only ids, titles and each field's section
  id. Anything else a field carries (its value above all) is left out."
  [{:keys [fields sections]}]
  {:fields (mapv #(select-keys % [:id :title :section]) fields)
   :sections (mapv #(select-keys % [:id :title]) sections)})

(defn- plain-part?
  "A title that can stand as one part of a reference: not blank, no slash or question mark, no space
  at either end."
  [s]
  (boolean (and (string? s) (not (str/blank? s)) (= s (str/trim s)) (not (re-find #"[/?]" s)))))

(defn ref-part
  "A reference's part for something with a title and an id: the title when it is plain and unique
  (unique?), else the id."
  [title id unique?]
  (if (and unique? (plain-part? title)) title id))

(defn- repeated
  "The lower-cased titles that appear more than once among xs (1Password matches names without case)."
  [xs]
  (set (for [[t n] (frequencies (map #(str/lower-case (str (:title %))) xs)) :when (> n 1)] t)))

(defn- unique-in? [clashes title] (not (contains? clashes (str/lower-case (str title)))))

(defn- field-entry
  "One field's label, section name (when its section has one) and reference."
  [prefix sections-by-id clashes {:keys [id title section]}]
  (let [s (get sections-by-id section)
        s-part (when-not (str/blank? (:title s)) (ref-part (:title s) (:id s) true))]
    (cond-> {:label (str title)
             :ref (str prefix (when s-part (str s-part "/")) (ref-part title id (unique-in? clashes title)))}
      s-part (assoc :section (:title s)))))

(defn item-entry
  "An item as list_secrets shows it: its title and every field's label and reference
  (op://<vault>/<item>/[<section>/]<field>). item-unique?: no other item in the vault has its title."
  [vault {:keys [id title]} item-unique? labels]
  (let [{:keys [fields sections]} (item-labels labels)
        prefix (str "op://" vault "/" (ref-part title id item-unique?) "/")
        by-id (into {} (map (juxt :id identity)) sections)]
    {:title title
     :fields (mapv (partial field-entry prefix by-id (repeated fields)) fields)}))

(defn key-list
  "What /keys/mine answers: the usable items in order, each with its fields' references. all-items:
  every item of the vault (to tell clashing titles); labels-by-id: item id -> labels (an item without
  labels lists no fields)."
  [vault all-items usable labels-by-id]
  (let [clashes (repeated all-items)]
    {:vault vault
     :items (mapv #(item-entry vault % (unique-in? clashes (:title %)) (get labels-by-id (:id %))) usable)}))
