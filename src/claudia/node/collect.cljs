(ns claudia.node.collect
  "Runs System's fixed reads (claudia.node.reads) and answers them as text: the root helper as root
  for every user (mode :root), the front as its own user when there is no helper (mode :own). Never
  a shell: every program runs by execFile with its argv as data."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            [claudia.node.crypto :as crypto]
            [claudia.node.owner :as owner]
            [claudia.node.reads :as reads]
            [claudia.node.services :as services]
            [claudia.shared.protocol :as p]))

(def ^:private fsp (.-promises fs))

(defn- text-of [f] (-> (.readFile fsp f "utf8") (.catch (fn [_] nil))))
(defn- names-in [dir] (-> (.readdir fsp dir) (.then js->clj) (.catch (fn [_] []))))
(defn- all [ps] (js/Promise.all (into-array ps)))

(defn exec!
  "Promise of {:out stdout} of argv once it ran (whatever its exit code: coredumpctl exits 1 when it
  found none), or {:error why} when it could not run or took over 15 s."
  [argv]
  (js/Promise.
   (fn [done]
     (cp/execFile (first argv) (clj->js (rest argv)) #js {:timeout 15000 :maxBuffer (* 32 1024 1024)}
                  (fn [^js err out]
                    (cond (nil? err) (done {:out out})
                          (= "ENOENT" (.-code err)) (done {:error (str (first argv) " is not installed")})
                          (.-killed err) (done {:error (str (first argv) " took too long")})
                          (number? (.-code err)) (done {:out (str out)})
                          :else (done {:error (str (first argv) ": " (.-message err))})))))))

(defn- out-of [argv] (.then (exec! argv) #(or (:out %) "")))

(defn- containers! []
  (.then (all [(out-of reads/podman-argv) (out-of reads/docker-argv)])
         (fn [[podman docker]] {:out (p/write-json {:podman podman :docker docker})})))

(defn users!
  "Promise of {uid name} from /etc/passwd."
  []
  (.then (text-of "/etc/passwd") owner/passwd-users))

(defn- units-of!
  "Promise of {:units (list-units' text) :show (show's text)} of one manager: user nil for the
  system's, :own for the caller's own, a name for that user's."
  [user]
  (-> (out-of (if (= user :own) (reads/user-units-argv nil) (if user (reads/user-units-argv user) reads/system-units-argv)))
      (.then (fn [list-text]
               (let [names (filterv reads/unit-name? (map :unit (services/listed-units list-text)))]
                 (if (seq names)
                   (.then (out-of (reads/show-argv names user services/show-properties)) #(do {:units list-text :show %}))
                   {:units list-text :show ""}))))))

(defn- manager-users!
  "Promise of the names of the users whose manager runs (a folder /run/user/<uid>)."
  []
  (.then (all [(names-in "/run/user") (users!)])
         (fn [[uids users]] (vec (keep #(get users (js/parseInt % 10)) (filter #(re-matches #"\d+" %) uids))))))

(defn- units!
  "Promise of {:out JSON {user {:units :show}}}: every running user manager's services (:root), or
  only the caller's own (:own, under its own name)."
  [mode]
  (-> (if (= mode :root)
        (.then (manager-users!) (fn [names] (.then (all (map units-of! names)) #(zipmap names %))))
        (.then (units-of! :own) (fn [u] {(or (.. js/process -env -USER) "own") u})))
      (.then #(do {:out (p/write-json %)}))))

(defn- pid-key!
  "Promise of [pid key-hash] for a process in a Claudia host unit's cgroup that holds a chat key,
  else nil. The key never leaves this function: only the first 16 hex of its SHA-256."
  [pid]
  (.then (text-of (str "/proc/" pid "/cgroup"))
         (fn [cg]
           (when (some? (owner/host-profile (owner/cgroup-path cg)))
             (.then (text-of (str "/proc/" pid "/environ"))
                    #(when-let [k (owner/chat-key-of %)] [pid (crypto/token-hash k)]))))))

(defn pids!
  "Promise of {:out JSON {pid key-hash}} for the processes in Claudia's host units' cgroups (those
  this user may read: all of them as root)."
  []
  (-> (names-in "/proc")
      (.then #(all (map pid-key! (filter (fn [n] (re-matches #"^\d+$" n)) %))))
      (.then #(do {:out (p/write-json (into {} (keep identity %)))}))))

(defn- unit-log! [{:keys [scope user] :as frame}]
  (.then (users!)
         (fn [users]
           (let [uid (some (fn [[k v]] (when (= v user) k)) users)]
             (if (and (= scope "user") (nil? uid))
               {:error (str "No user " user " here.")}
               (exec! (reads/unit-log-argv frame uid)))))))

(defn read!
  "Promise of {:out text} or {:error why} for a checked read frame, as mode (:root or :own)."
  [mode {:keys [read] :as frame}]
  (case read
    "ports" (exec! reads/ports-argv)
    "containers" (containers!)
    "units" (units! mode)
    "pids" (pids!)
    "journal" (exec! reads/journal-argv)
    "coredumps" (exec! reads/coredumps-argv)
    "unit-log" (unit-log! frame)
    (js/Promise.resolve {:error "No such read."})))

(defn system-units!
  "Promise of the system manager's {:units :show} (no root needed)."
  []
  (units-of! nil))
