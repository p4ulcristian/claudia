(ns claudia.node.owner
  "What a process belongs to (README decision 96, PROTOCOL.md `Owner`), from its cgroup: pure
  functions over /proc/<pid>/cgroup's text and what else is known (users, containers, the chats'
  key hashes), shared by the front, the hub and the root helper."
  (:require [clojure.string :as str]))

(defn cgroup-path
  "/proc/<pid>/cgroup's text as the process's cgroup path: the unified (v2) line `0::<path>`, else
  the systemd (v1) line's; nil when neither is there."
  [text]
  (let [lines (str/split-lines (str text))
        path-of (fn [re] (some #(second (re-matches re %)) lines))]
    (or (path-of #"^0::(.*)$") (path-of #"^\d+:name=systemd:(.*)$"))))

(defn unescape-unit
  "A unit name as systemd writes it in a cgroup (`\\x2d` for a dash in a slice's name) as text."
  [s]
  (str/replace s #"\\x([0-9a-fA-F]{2})" (fn [[_ h]] (js/String.fromCharCode (js/parseInt h 16)))))

(def ^:private container-re
  "A container's scope or folder: docker, podman (libpod, its conmon too), CRI-O, containerd."
  #"^(?:docker|libpod|libpod-conmon|crio|cri-containerd)-([0-9a-f]{12,64})\.scope$")

(defn- container-id [segs]
  (some #(or (second (re-matches container-re %))
             (second (re-matches #"^([0-9a-f]{64})$" %)))
        segs))

(defn- claudia-unit
  "A Claudia unit's place among segs: {:claudia program} or {:host profile-or-nil}."
  [segs]
  (some (fn [s]
          (if-let [[_ prog] (re-matches #"^claudia-(front|root|hub|broker)\.service$" s)]
            {:claudia prog}
            (when-let [[_ prog _ profile] (re-matches #"^claudia-(runner|host)(@(.+))?\.service$" s)]
              (if (= prog "host") {:host (or profile "")} {:claudia "runner" :profile profile}))))
        segs))

(defn- uid-in [segs pattern]
  (some #(some-> (re-matches pattern %) second (js/parseInt 10)) segs))

(defn- user-place
  "Inside a user's manager (user@<uid>.service): {:user-unit unit :uid} for a service there, else
  {:session uid} (a desktop app's scope, the manager itself)."
  [segs]
  (when-let [uid (uid-in segs #"^user@(\d+)\.service$")]
    (let [inner (rest (drop-while #(not (re-matches #"^user@\d+\.service$" %)) segs))
          unit (last (filter #(str/ends-with? % ".service") inner))]
      (if unit {:user-unit unit :uid uid} {:session uid}))))

(defn place
  "Where a cgroup path puts a process, as data: {:container id12}, {:claudia program}, {:host
  profile}, {:user-unit :uid}, {:session uid}, {:unit name}, or {} (none known)."
  [path]
  (let [segs (map unescape-unit (remove str/blank? (str/split (str path) #"/")))
        cid (container-id segs)]
    (cond cid {:container (subs cid 0 12)}
          (claudia-unit segs) (claudia-unit segs)
          (user-place segs) (user-place segs)
          (some #(re-matches #"^session-.+\.scope$" %) segs) {:session (uid-in segs #"^user-(\d+)\.slice$")}
          (some #(re-find #"\.(service|scope)$" %) segs) {:unit (last (filter #(re-find #"\.(service|scope)$" %) segs))}
          :else {})))

(defn service-key
  "The service a cgroup path is in, as \"system/<unit>\" or \"user/<uid>/<unit>\" (a user
  manager's), nil when it is in no service: what a Service's CPU and memory are summed by."
  [path]
  (let [segs (map unescape-unit (remove str/blank? (str/split (str path) #"/")))
        uid (uid-in segs #"^user@(\d+)\.service$")
        unit (last (filter #(and (str/ends-with? % ".service") (not (re-matches #"^user@\d+\.service$" %))) segs))]
    (when unit (if uid (str "user/" uid "/" unit) (str "system/" unit)))))

(defn host-profile
  "The profile of a Claudia host unit a cgroup path is in (\"\" for a single person's
  claudia-host.service), nil when it is in none: whose chat keys the root helper reads."
  [path]
  (:host (place path)))

(defn- name-of-user [users uid] (get users uid (str uid)))

(defn- host-owner
  "A process in a Claudia host's cgroup (not the host itself): its chat when its key names a running
  one, a leftover when its key names none that runs (only when the runners answered: chats not nil),
  else a chat not known by name."
  [pid host {hashes :keys chats :chats}]
  (let [key-hash (get hashes pid)
        chat (get chats key-hash)
        profile (or (:profile chat) (not-empty host))]
    (cond (and chat (:running chat)) (cond-> {:kind "chat" :chat (select-keys chat [:id :title :colour])}
                                       profile (assoc :profile profile))
          (and key-hash chats) {:kind "leftover" :name (or profile "")}
          :else (cond-> {:kind "chat"} profile (assoc :profile profile)))))

(defn owner-of
  "A process's Owner: p = {:pid :ppid :kind (Claudia's program by its command, or nil) :cgroup
  (its path)}; ctx = {:users {uid name} :containers {id12 name} :keys {pid key hash} :chats {hash
  {:id :title :colour :running :profile}} (nil: the runners were not asked or one did not answer)},
  any part may be missing."
  [{:keys [pid ppid kind cgroup]} {:keys [users containers] :as ctx}]
  (let [{:keys [container claudia host user-unit uid session unit]} (place cgroup)]
    (cond (or (= pid 2) (= ppid 2)) {:kind "kernel" :name "kernel"}
          kind {:kind "claudia" :name kind}
          container {:kind "container" :name (get containers container container)}
          claudia {:kind "claudia" :name claudia}
          host (host-owner pid host ctx)
          user-unit {:kind "user-unit" :name (str user-unit "@" (name-of-user users uid))}
          session {:kind "session" :name (name-of-user users session)}
          unit {:kind "unit" :name unit}
          :else {:kind "other" :name "other"})))

(defn parent-owner
  "A process's Owner on macOS (no cgroups), from its parent chain: kinds = pid -> Claudia's program
  or \"chats\" (everything a host started, claudia.node.system/kinds-of)."
  [{:keys [pid kind]} kinds]
  (cond (zero? pid) {:kind "kernel" :name "kernel"}
        kind {:kind "claudia" :name kind}
        (= "chats" (get kinds pid)) {:kind "chat"}
        :else {:kind "other" :name "other"}))

(defn passwd-users
  "/etc/passwd's text as {uid name}."
  [text]
  (into {} (for [line (str/split-lines (str text))
                 :let [[user-name _ uid] (str/split line #":")]
                 :when (and (seq user-name) uid (re-matches #"\d+" uid))]
             [(js/parseInt uid 10) user-name])))

(defn chat-key-of
  "/proc/<pid>/environ's text (NUL separated) as its CLAUDIA_CHAT_KEY, nil when it has none."
  [text]
  (some #(second (re-matches #"^CLAUDIA_CHAT_KEY=(.+)$" %)) (str/split (str text) #"\u0000")))

(defn owner-key
  "An Owner as the text processes are grouped by."
  [{:keys [kind chat profile] owner-name :name}]
  (str kind "/" (or (:id chat) owner-name) "/" profile))

(defn groups
  "Procs ({:name :cores :rss :owner}) grouped by owner: [{:owner :count :cores :rss :names (the 3
  biggest program names)}], most memory first."
  [procs]
  (->> (vals (group-by (comp owner-key :owner) procs))
       (map (fn [ps]
              {:owner (:owner (first ps)) :count (count ps)
               :cores (/ (js/Math.round (* 100 (reduce + (map :cores ps)))) 100)
               :rss (reduce + (map :rss ps))
               :names (vec (take 3 (distinct (map :name (sort-by (comp - :rss) ps)))))}))
       (sort-by (comp - :rss))
       vec))
