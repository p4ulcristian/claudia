(ns claudia.node.services
  "Services and containers for System (README decision 96, PROTOCOL.md `Service`, `Container`):
  pure functions over `systemctl list-units --output=json`, `systemctl show`, `launchctl list`,
  `podman ps --format json` and `docker ps --format '{{json .}}'`."
  (:require [clojure.string :as str]
            [claudia.shared.protocol :as p]))

(defn- json-or [text fallback]
  (try (p/read-json text) (catch :default _ fallback)))

(def shown-states
  "The ActiveStates System lists: running, failed or on their way."
  #{"active" "failed" "activating" "deactivating" "reloading"})

(defn listed-units
  "`systemctl list-units --type=service --output=json`'s text as [{:unit :active :sub
  :description}], only services in shown-states."
  [text]
  (let [v (json-or text [])]
    (vec (for [u (if (sequential? v) v [])
               :when (and (str/ends-with? (str (:unit u)) ".service") (shown-states (:active u)))]
           (select-keys u [:unit :active :sub :description])))))

(defn show-blocks
  "`systemctl show <unit…> -p …`'s text as {Id {property value}} (one block per unit, blank lines
  between)."
  [text]
  (into {} (for [block (str/split (str text) #"\n\s*\n")
                 :let [props (into {} (keep #(let [[_ k v] (re-matches #"^([A-Za-z]+)=(.*)$" %)] (when k [k v])))
                                   (str/split-lines block))]
                 :when (seq (get props "Id"))]
             [(get props "Id") props])))

(def show-properties
  "What `systemctl show` is asked for each listed unit."
  "Id,ActiveState,SubState,StateChangeTimestampMonotonic,NRestarts,FragmentPath,MemoryCurrent,Description")

(defn origin-of
  "Where a unit comes from: claudia (claudia-*), container (podman's generated units, quadlets),
  os (the system's own, under /usr/lib/systemd or /lib/systemd), else admin (written by people)."
  [unit-name fragment]
  (let [f (str fragment)]
    (cond (str/starts-with? unit-name "claudia-") "claudia"
          (or (re-find #"/systemd/generator" f) (re-matches #"^(container|pod)-.+\.service$" unit-name)) "container"
          (re-find #"^/(usr/)?lib/systemd/" f) "os"
          :else "admin")))

(defn- number-or-nil [s]
  (when (re-matches #"\d+" (str s))
    (let [n (js/Number s)] (when (< n 9.2e18) n))))

(defn service-row
  "A Service from a listed unit u, its show props, the scope (and user), and the clock: now (ms)
  less uptime (ms) is the boot, where systemd's monotonic times start."
  [u props {:keys [scope user now uptime-ms]}]
  (let [mono (number-or-nil (get props "StateChangeTimestampMonotonic"))
        n (:unit u)]
    (cond-> {:name n :scope scope
             :state (or (not-empty (get props "ActiveState")) (:active u))
             :sub (or (not-empty (get props "SubState")) (:sub u))
             :since (when (and mono (pos? mono)) (js/Math.round (+ (- now uptime-ms) (/ mono 1000))))
             :restarts (number-or-nil (get props "NRestarts"))
             :desc (or (:description u) (get props "Description") "")
             :origin (origin-of n (get props "FragmentPath"))}
      user (assoc :user user)
      (number-or-nil (get props "MemoryCurrent")) (assoc :rss (number-or-nil (get props "MemoryCurrent"))))))

(defn services
  "list-units' and show's texts as Services of scope (and user)."
  [list-text show-text opts]
  (let [props (show-blocks show-text)]
    (mapv #(service-row % (get props (:unit %) {}) opts) (listed-units list-text))))

(defn launchctl-services
  "`launchctl list`'s text (PID, Status, Label per line) as Services: running ones and those whose
  last exit failed."
  [text]
  (vec (for [line (rest (str/split-lines (str text)))
             :let [[pid status label] (str/split line #"\t")
                   running? (re-matches #"\d+" (str pid))
                   failed? (and (not running?) (not= "0" status))]
             :when (and (seq label) (or running? failed?))]
         {:name label :scope "user" :state (if running? "active" "failed") :sub (if running? "running" "failed")
          :since nil :restarts nil :desc ""
          :origin (cond (str/starts-with? label "com.apple.") "os"
                        (str/includes? label "claudia") "claudia"
                        :else "admin")})))

;; ---- containers ----

(defn- podman-port [{:keys [host_ip host_port container_port protocol]}]
  (str (when (seq host_ip) (str host_ip ":")) host_port "->" container_port "/" protocol))

(defn podman-containers
  "`podman ps -a --format json`'s text as Containers."
  [text]
  (let [v (json-or text [])]
    (vec (for [c (if (sequential? v) v [])]
           {:engine "podman" :id (subs (str (:Id c)) 0 12) :name (str (first (:Names c)))
            :image (str (:Image c)) :state (str (:State c))
            :since (some-> (or (:StartedAt c) (:Created c)) (* 1000))
            :ports (mapv podman-port (:Ports c))}))))

(defn docker-time
  "docker's CreatedAt (`2026-11-16 20:07:04 +0100 CET`) as ms, nil when it is not one."
  [text]
  (when-let [[_ d t z] (re-find #"^(\d{4}-\d\d-\d\d) (\d\d:\d\d:\d\d) ([+-]\d{4})" (str text))]
    (let [ms (js/Date.parse (str d "T" t (subs z 0 3) ":" (subs z 3)))]
      (when-not (js/isNaN ms) ms))))

(defn docker-containers
  "`docker ps -a --format '{{json .}}'`'s text (one object a line) as Containers."
  [text]
  (vec (for [line (str/split-lines (str text))
             :let [c (json-or line nil)]
             :when (map? c)]
         {:engine "docker" :id (subs (str (:ID c)) 0 (min 12 (count (str (:ID c))))) :name (str (:Names c))
          :image (str (:Image c)) :state (str (:State c)) :since (docker-time (:CreatedAt c))
          :ports (vec (remove str/blank? (str/split (str (:Ports c)) #",\s*")))})))

(defn container-names
  "Containers as {id12 name}, what a container's cgroup names it by."
  [containers]
  (into {} (map (juxt :id :name)) containers))
