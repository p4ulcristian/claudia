(ns claudia.web.views.pure.system-lists
  "The System sheet's lists (README decision 96), pure: what a thing's project is by the labels,
  who owns a process in words, the ports folded per listener, the services and processes grouped
  as the tabs show them."
  (:require [clojure.string :as str]
            [claudia.web.views.pure.systems :as systems]))

;; ---- projects, from the labels ----

(def label-kinds
  "The kinds a label can match, the most telling first: a unit or container names one thing, a port
  one door, a program's name maybe many."
  [:unit :container :port :process])

(defn unit-base
  "A unit's name without a user unit's \"@user\" ending (\"web.service@paul\" → \"web.service\");
  a template's instance (\"getty@tty1.service\") stays."
  [n]
  (str/replace (str n) #"@[^@./]+$" ""))

(defn label-keys
  "What a thing can be labelled by, from its owner, port and program name: {:unit :container :port
  :process}, those it has; :claudia? for Claudia's own (its project is Claudia, no label needed)."
  [{:keys [owner port] program :name}]
  {:claudia? (= "claudia" (:kind owner))
   :unit (when (#{"unit" "user-unit"} (:kind owner)) (unit-base (:name owner)))
   :container (when (= "container" (:kind owner)) (:name owner))
   :port (when port (str port))
   :process program})

(defn label-for
  "The label that names a thing's project on server (keys: label-keys): by unit, container, port,
  then program name, this server's label before one for every server (\"*\"); nil: unknown."
  [labels server keys-of]
  (first (for [kind label-kinds
               :let [m (get keys-of kind)]
               :when m
               where [server "*"]
               l labels
               :when (and (= (name kind) (:kind l)) (= m (:match l)) (= where (:server l)))]
           l)))

(defn label-target
  "What an admin's new label on a row names: its unit, else its container, else its program, else
  its port, as {:kind :match}; nil when the row has none of them."
  [keys-of]
  (some (fn [kind] (when-let [m (get keys-of kind)] {:kind (name kind) :match m}))
        [:unit :container :process :port]))

;; ---- owners ----

(def owning-kinds
  "Owners that account for a port: a unit, a container, a chat or Claudia itself."
  #{"chat" "claudia" "unit" "user-unit" "container"})

(defn owned? [owner] (contains? owning-kinds (:kind owner)))

(defn owner-text
  "Who a process belongs to, in words (a chat's colour square is the view's); nil for other."
  [{:keys [kind chat profile] who :name}]
  (case kind
    "chat" (if chat (str "chat “" (:title chat) "”") (str "a chat of " (or profile "someone")))
    "claudia" (str "Claudia's " who)
    ("unit" "user-unit") who
    "container" (str "container " who)
    "session" (str who "'s login")
    "kernel" "the kernel"
    "leftover" (str "left by " who "'s chats")
    nil))

;; ---- numbers in words ----

(defn cores-text
  "CPU in cores: \"3.7 cores\", \"1.0 core\", \"<0.1 cores\" for a trace, \"0 cores\" for none."
  [c]
  (let [c (or c 0)
        t (cond (zero? c) "0" (< c 0.05) "<0.1" (>= c 10) (str (js/Math.round c)) :else (.toFixed c 1))]
    (str t (if (= t "1.0") " core" " cores"))))

(defn old-text
  "Seconds since something started as its age: \"3 days old\", \"1 hour old\", \"12 minutes old\"."
  [secs]
  (let [[n unit] (cond (>= secs 86400) [(js/Math.floor (/ secs 86400)) "day"]
                       (>= secs 3600) [(js/Math.floor (/ secs 3600)) "hour"]
                       :else [(max 1 (js/Math.floor (/ secs 60))) "minute"])]
    (str n " " unit (when (not= 1 n) "s") " old")))

;; ---- ports ----

(def ^:private reach-rank {"all" 0 "lan" 1 "headscale" 2 "local" 3})

(def reach-names
  {"all" "all addresses" "lan" "LAN" "headscale" "Headscale" "local" "this machine"})

(defn reach-text [r] (get reach-names r (str r)))

(defn- widest
  "One listener's addresses as one row: the widest reach, every address under :addrs."
  [ps]
  (assoc (first (sort-by #(reach-rank (:reach %) 9) ps)) :addrs (mapv :addr ps)))

(defn port-rows
  "One row per protocol, port and program (a listener on 0.0.0.0 and :: is one), port order."
  [ports]
  (->> ports
       (group-by (juxt :proto :port :pid))
       vals
       (map widest)
       (sort-by (juxt :port :proto))
       vec))

(defn ports-of
  "The port numbers a process listens on, in order, each once."
  [ports pid]
  (vec (distinct (sort (keep #(when (and pid (= pid (:pid %))) (:port %)) ports)))))

;; ---- services ----

(defn service-tone
  "A service's dot: green running, red failed, amber on its way up or down, grey otherwise."
  [state]
  (case state
    "active" "green"
    "failed" "red"
    ("activating" "deactivating" "reloading") "amber"
    "grey"))

(defn service-up-text
  "How long a service has been as it is (\"3 d 4 h\"), or \"failed\"."
  [{:keys [state since]} now]
  (cond (= state "failed") "failed"
        since (systems/uptime-text (js/Math.max 0 (js/Math.floor (/ (- now since) 1000))))
        :else ""))

(defn- service-group
  "Which group a service sits in: :claudia, a project's name, :none (no project), :containers or
  :os (the system's own)."
  [labels server {:keys [origin] :as svc}]
  (let [l (label-for labels server {:unit (:name svc)})]
    (cond (= origin "claudia") :claudia
          l (:project l)
          (= origin "container") :containers
          (= origin "os") :os
          :else :none)))

(defn- group-title [g]
  (case g :claudia "Claudia" :none "No project" :containers "Containers" :os "The system's own" g))

(defn- group-cell [g]
  (case g :none :full (:containers :os) :quiet :grouped))

(defn- group-rank [g]
  (case g :claudia 0 :none 2 :containers 3 :os 4 1))

(defn service-groups
  "The services as the Services tab groups them: Claudia, each project (by the labels, by name), no
  project, containers, then the system's own (folded): [{:id :title :services :folded? :cell}],
  each group's services by name; cell: how its rows show their project (:grouped where the title
  names it, :full in No project, :quiet elsewhere: no \"unknown\"); empty groups left out."
  [services labels server]
  (->> (group-by #(service-group labels server %) services)
       (sort-by (fn [[g]] [(group-rank g) (str g)]))
       (mapv (fn [[g svcs]] {:id (str g) :title (group-title g) :folded? (= g :os) :cell (group-cell g)
                             :services (vec (sort-by :name svcs))}))))

(defn group-counts
  "A group header's counts: \"6 running · 1 failed\" (the others as \"2 other\")."
  [services]
  (let [n (frequencies (map #(case (:state %) "active" :running "failed" :failed :other) services))]
    (str/join " · " (remove nil? [(str (get n :running 0) " running")
                                  (when (n :failed) (str (n :failed) " failed"))
                                  (when (n :other) (str (n :other) " other"))]))))

;; ---- processes ----

(def ^:private process-sections
  [["chats" "Chats" #{"chat"}]
   ["claudia" "Claudia" #{"claudia"}]
   ["services" "Services" #{"unit" "user-unit"}]
   ["containers" "Containers" #{"container"}]
   ["rest" "Logins and the rest" #{"session" "kernel" "other"}]])

(defn process-groups
  "The process groups (Details' groups) as the Processes tab shows them: chats, Claudia, services,
  containers, the rest (leftovers have a section of their own): [{:id :title :groups}], most memory
  first in each, empty sections left out."
  [groups]
  (vec (for [[id title kinds] process-sections
             :let [gs (filter #(kinds (get-in % [:owner :kind])) groups)]
             :when (seq gs)]
         {:id id :title title :groups (vec (sort-by #(- (or (:rss %) 0)) gs))})))

(defn process-count "Every process of the groups." [groups]
  (reduce + 0 (map #(or (:count %) 0) groups)))

(defn tab-counts
  "The counts System's tabs show from the Details (nil without them; a tab not here shows none)."
  [details]
  (when details
    {"ports" (count (port-rows (:ports details)))
     "services" (count (:services details))
     "processes" (process-count (:groups details))
     "log" (count (:log details))}))
