(ns claudia.node.details
  "System's Details of this machine (README decision 95, PROTOCOL.md `Details`): ports, services,
  containers, every process grouped by its owner, leftovers and the last week's log. On Linux the
  reads that need root come from the root helper's read socket; without it (an older helper, the
  gate) the same reads run as this program's user and Details says why. On macOS from lsof, ps and
  launchctl. Read again at most every 15 s while asked in the last 60 s."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            [claudia.node.collect :as collect]
            [claudia.node.events :as events]
            [claudia.node.owner :as owner]
            [claudia.node.ports :as ports]
            [claudia.node.root-read :as root-read]
            [claudia.node.services :as services]
            [claudia.node.system :as system]
            [claudia.shared.protocol :as p]))

(def ^:private fsp (.-promises fs))
(defn- all [ps] (js/Promise.all (into-array ps)))
(defn- json-or [text fallback] (try (p/read-json text) (catch :default _ fallback)))

;; ---- pure: the reads' texts as data ----

(defn key-hashes
  "The pids read's text ({\"pid\": hash}) as {pid hash}."
  [text]
  (let [v (json-or text {})]
    (if (map? v) (into {} (for [[k h] v :let [pid (js/parseInt (name k) 10)] :when (and (pos? pid) (string? h))] [pid h])) {})))

(defn user-services
  "The units read's text ({user {units, show}}) as Services of scope user."
  [text opts]
  (let [v (json-or text {})]
    (vec (mapcat (fn [[user {u :units show :show}]] (services/services (str u) (str show) (assoc opts :scope "user" :user (name user))))
                 (if (map? v) v {})))))

(defn containers-of
  "The containers read's text ({podman, docker}) as Containers."
  [text]
  (let [{:keys [podman docker]} (json-or text {})]
    (into (services/podman-containers (str podman)) (services/docker-containers (str docker)))))

(defn with-owners
  "procs ({:pid …}) with :owner and :service from cgroups {pid path} and ctx (owner/owner-of)."
  [procs cgroups ctx]
  (mapv (fn [process]
          (let [cg (get cgroups (:pid process))]
            (assoc process :owner (owner/owner-of (assoc process :cgroup cg) ctx) :service (owner/service-key cg))))
        procs))

(defn- service-key-of [{:keys [scope user] service-name :name} uids]
  (if (= scope "user") (str "user/" (get uids user) "/" service-name) (str "system/" service-name)))

(defn service-usage
  "Services with their processes' cores, and rss where systemd gave none; uids: {user uid}."
  [svcs procs uids]
  (let [by (group-by :service procs)]
    (mapv (fn [s]
            (let [ps (get by (service-key-of s uids))]
              (cond-> s
                (seq ps) (assoc :cores (/ (js/Math.round (* 100 (reduce + (map :cores ps)))) 100))
                (and (seq ps) (nil? (:rss s))) (assoc :rss (reduce + (map :rss ps))))))
          svcs)))

(defn proc-row
  "A process as Details shows it: no command line, only its program's name."
  [process]
  (select-keys process [:pid :name :cores :rss :owner]))

(defn leftovers
  "The processes left in a Claudia host by a chat that no longer runs, with their age (s), from the
  machine's uptime (s)."
  [procs uptime]
  (vec (for [process procs :when (= "leftover" (:kind (:owner process)))]
         (assoc (proc-row process) :age (js/Math.round (- uptime (/ (:start process) 100)))))))

(defn port-owners
  "Ports with the owner of their process."
  [port-rows procs]
  (let [by-pid (into {} (map (juxt :pid :owner)) procs)]
    (mapv #(if-let [o (get by-pid (:pid %))] (assoc % :owner o) %) port-rows)))

;; ---- reads ----

(def ^:private root-kinds ["ports" "containers" "units" "pids" "journal" "coredumps"])

(defn- own-reads!
  "Promise of {kind out} of the reads run as this program's user."
  []
  (.then (all (map #(collect/read! :own {:read %}) root-kinds))
         #(zipmap root-kinds (map :out %))))

(defn- helper-reads!
  "Promise of {:root :why :outs {kind out}}: the helper's answers, or this user's own reads and why."
  [why-no-helper]
  (if why-no-helper
    (.then (own-reads!) #(do {:root false :why why-no-helper :outs %}))
    (.then (all (map #(root-read/ask! {:read %}) root-kinds))
           (fn [answers]
             (if-let [missing (some :missing answers)]
               (.then (own-reads!) #(do {:root false :why missing :outs %}))
               {:root true :outs (zipmap root-kinds (map :out answers))})))))

(defn- cgroups! [pids]
  (.then (all (map #(-> (.readFile fsp (str "/proc/" % "/cgroup") "utf8") (.catch (fn [_] nil))) pids))
         #(zipmap pids (map owner/cgroup-path %))))

(defn- reboot! []
  (-> (all [(-> (.readdir fsp "/usr/lib/modules") (.then js->clj) (.catch (fn [_] [])))
            (-> (.stat fsp "/usr/lib/modules") (.then #(.-mtimeMs ^js %)) (.catch (fn [_] (js/Date.now))))])
      (.then (fn [[dirs at]] (events/reboot-event (os/release) dirs (js/Math.round at))))))

(defn- context!
  "Promise of the owner context from the reads' outs and chats-fn ({hash chat} for hashes), kept in
  claudia.node.system/context for the samples too."
  [outs chats-fn]
  (let [hashes (key-hashes (get outs "pids"))]
    (-> (all [(collect/users!) (when (and chats-fn (seq hashes)) (chats-fn (vec (distinct (vals hashes)))))])
        (.then (fn [[users chats]]
                 (let [ctx {:users users :keys hashes :chats chats
                            :containers (services/container-names (containers-of (get outs "containers")))}]
                   (reset! system/context ctx)
                   ctx))))))

(defn- linux-parts!
  "Promise of everything Details is made of on Linux."
  [why-no-helper chats-fn]
  (-> (all [(helper-reads! why-no-helper) (system/newest!) (collect/system-units!) (reboot!)])
      (.then (fn [[{:keys [outs] :as r} sample sys reboot]]
               (-> (all [(context! outs chats-fn) (cgroups! (map :pid (:procs sample)))])
                   (.then (fn [[ctx cgroups]]
                            (assoc r :sample sample :system-units sys :reboot reboot :ctx ctx
                                   :procs (with-owners (:procs sample) cgroups ctx)))))))))

(defn- linux-services [{:keys [outs system-units procs ctx]} now uptime]
  (let [opts {:now now :uptime-ms (* 1000 uptime)}
        svcs (into (services/services (:units system-units) (:show system-units) (assoc opts :scope "system"))
                   (user-services (get outs "units") opts))]
    (service-usage svcs procs (into {} (map (fn [[k v]] [v k])) (:users ctx)))))

(defn linux-details
  "Details from linux-parts!' data at now."
  [{:keys [root why outs sample procs reboot] :as parts} now]
  (let [uptime (:uptime sample)]
    (cond-> {:at now :root root
             :ports (port-owners (ports/ss-ports (get outs "ports")) procs)
             :services (linux-services parts now uptime)
             :containers (containers-of (get outs "containers"))
             :groups (owner/groups procs)
             :leftovers (leftovers procs uptime)
             :log (events/newest (cond-> (into (events/journal-events (get outs "journal"))
                                               (events/coredump-events (get outs "coredumps")))
                                   reboot (conj reboot))
                                 now)}
      why (assoc :why why))))

;; ---- macOS ----

(defn- darwin-details! []
  (-> (all [(collect/exec! ["lsof" "-nP" "-iTCP" "-sTCP:LISTEN" "-F" "pcPn"]) (collect/exec! ["lsof" "-nP" "-iUDP" "-F" "pcPn"])
            (collect/exec! ["launchctl" "list"]) (system/newest!)])
      (.then (fn [[tcp udp launchd sample]]
               (.then (system/owners! (:procs sample) (:procs sample))
                      (fn [owners]
                        (let [procs (mapv #(assoc % :owner (get owners (:pid %))) (:procs sample))]
                          {:at (js/Date.now) :root false
                           :why "macOS has no root helper: ports and processes of other users are not shown."
                           :ports (port-owners (ports/lsof-ports (:out tcp) (:out udp)) procs)
                           :services (services/launchctl-services (:out launchd)) :containers []
                           :groups (owner/groups procs) :leftovers [] :log []})))))))

;; ---- the cache ----

(def ^:private fresh-ms 15000)
(def ^:private asked-for-ms 60000)

;; {:asked ms :details d :reading promise-or-nil :timer id-or-nil}
(defonce ^:private state (atom {:asked 0 :details nil :reading nil :timer nil}))

(defn- read-now!
  "Promise of new Details; opts {:why-no-helper (the gate: no helper) :chats-fn}."
  [{:keys [why-no-helper chats-fn]}]
  (if (= "darwin" (os/platform))
    (darwin-details!)
    (.then (linux-parts! why-no-helper chats-fn) #(linux-details % (js/Date.now)))))

(declare refresh!)

(defn- again-later!
  "One timer at a time: in 15 s, read again when someone asked in the last 60 s."
  [opts]
  (when-not (:timer @state)
    (swap! state assoc :timer
           (js/setTimeout (fn []
                            (swap! state assoc :timer nil)
                            (when (< (- (js/Date.now) (:asked @state)) asked-for-ms) (refresh! opts)))
                          fresh-ms))))

(defn- refresh!
  "Reads again (one read at a time), then again later (again-later!)."
  [opts]
  (or (:reading @state)
      (let [reading (-> (read-now! opts)
                        (.then (fn [d] (swap! state assoc :details d :reading nil) (again-later! opts) d))
                        (.catch (fn [e] (swap! state assoc :reading nil) (throw e))))]
        (swap! state assoc :reading reading)
        reading)))

(defn latest!
  "Promise of this machine's Details: the kept one when under 15 s old, else read now."
  [opts]
  (swap! state assoc :asked (js/Date.now))
  (let [{:keys [details reading]} @state]
    (cond (and details (< (- (js/Date.now) (:at details)) fresh-ms)) (js/Promise.resolve details)
          reading reading
          :else (refresh! opts))))

(defn context-now!
  "Promise of the owner context read afresh (the chats' keys, the containers' names), for the
  history's minute without a whole Details read."
  [{:keys [why-no-helper chats-fn]}]
  (let [kinds ["pids" "containers"]
        own #(collect/read! :own {:read %})
        ask (if (or why-no-helper (= "darwin" (os/platform))) own #(root-read/ask! {:read %}))]
    (-> (all (map ask kinds))
        (.then (fn [answers] (if (some :missing answers) (all (map own kinds)) answers)))
        (.then #(context! (zipmap kinds (map :out %)) chats-fn)))))

(defn log!
  "Promise of the last week's Events (for History): the kept Details' when fresh, else read."
  [opts]
  (.then (latest! opts) #(:log %)))
