(ns claudia.node.system
  "This machine's numbers for the Systems popup (README decision 12), read from the kernel by
  claudia.node.proc: a read every 2 s while someone asked in the last 30 s, then none. Each sample is
  the difference of the last two reads, so CPU, disk and network use cover exactly that window.
  The front answers it for its server, the hub for the gate. On macOS (no /proc) the same shape
  comes from its own tools, read by claudia.node.darwin."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.node.darwin :as darwin]
            [claudia.node.proc :as proc]))

(def ^:private fsp (.-promises fs))
(def ^:private first-window 1000)
(def ^:private every 2000)
(def ^:private idle-after 30000)

(defn- text-of [p] (-> (.readFile fsp p "utf8") (.catch (fn [_] nil))))
(defn- names-in [p] (-> (.readdir fsp p) (.then js->clj) (.catch (fn [_] []))))
(defn- all [ps] (js/Promise.all (into-array ps)))

(defn- output-of
  "Promise of [error stdout] of a program run with args (1.5 s at most)."
  [program args]
  (js/Promise. (fn [done] (cp/execFile program (clj->js args) #js {:timeout 1500 :maxBuffer 8388608}
                                       (fn [err out] (done [err out]))))))

(defn- text-from [program & args] (.then (output-of program args) (fn [[err out]] (if err "" out))))

(defn- hardware
  "Names in a /sys/class folder that sit on a real device (a `device` link): network cards and disks,
  not lo, WireGuard, bridges, loops, device-mapper or zram."
  [dir]
  (.then (names-in dir) (fn [names] (filterv #(fs/existsSync (path/join dir % "device")) names))))

(defn- hwmon-one [dir]
  (-> (all [(text-of (path/join dir "name")) (text-of (path/join dir "temp1_label")) (text-of (path/join dir "temp1_input"))
            (-> (.realpath fsp (path/join dir "device")) (.catch (fn [_] "")))])
      (.then (fn [[n label milli device]]
               (proc/temp-row {:name (some-> n str/trim) :label (some-> label str/trim)
                               :milli (some-> milli str/trim js/Number) :device (path/basename device)})))))

(defn- temps []
  (-> (names-in "/sys/class/hwmon")
      (.then #(all (map (fn [n] (hwmon-one (path/join "/sys/class/hwmon" n))) %)))
      (.then #(vec (keep identity %)))))

(defonce ^:private no-nvidia (atom false))

(def ^:private gpu-query
  "--query-gpu=index,name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw")

(defn- gpus
  "NVIDIA cards by nvidia-smi; [] where it is missing (asked once) or does not answer in 1.5 s."
  []
  (if @no-nvidia
    (js/Promise.resolve [])
    (.then (output-of "nvidia-smi" [gpu-query "--format=csv,noheader,nounits"])
           (fn [[err out]]
             (when (and err (= "ENOENT" (.-code err))) (reset! no-nvidia true))
             (if err [] (proc/gpu-rows out))))))

(defn- space [{:keys [mount] :as m}]
  (-> (.statfs fsp mount)
      (.then (fn [^js s] (merge (select-keys m [:mount :fs :source])
                                (proc/disk-space {:bsize (.-bsize s) :blocks (.-blocks s) :bfree (.-bfree s) :bavail (.-bavail s)}))))
      (.catch (fn [_] nil))))

(defn- spaces [mounts] (.then (all (map space mounts)) #(vec (keep identity %))))

(defn- disks [] (.then (text-of "/proc/self/mountinfo") #(spaces (proc/disk-mounts (or % "")))))

(defn- pid-info [pid]
  (-> (all [(text-of (str "/proc/" pid "/stat")) (text-of (str "/proc/" pid "/cmdline"))])
      (.then (fn [[st cmd]] (when-let [s (and st (proc/proc-stat st))] (assoc s :pid pid :kind (proc/claudia-kind (or cmd ""))))))))

(defn kinds-of
  "pid -> kind for Claudia's programs and, as \"chats\", everything the hosts started (Claude Code
  and what it runs), from the processes' {:pid :ppid :kind}."
  [infos]
  (let [own (into {} (for [i infos :when (:kind i)] [(:pid i) (:kind i)]))
        hosts (for [[p k] own :when (= k "host")] p)
        chats (proc/descendants-of (into {} (map (juxt :pid :ppid) infos)) hosts)]
    (merge (zipmap chats (repeat "chats")) own)))

(defn- claudia-memory []
  (-> (names-in "/proc")
      (.then #(all (map pid-info (keep (fn [n] (when (re-matches #"^\d+$" n) (js/parseInt n 10))) %))))
      (.then #(let [kinds (kinds-of (keep identity %))]
                (all (for [[p k] kinds] (.then (text-of (str "/proc/" p "/status")) (fn [t] [k (proc/vm-rss (or t ""))]))))))
      (.then #(proc/group-rss (filterv (comp pos? second) %)))))

(defn- read-linux
  "Promise of one read of everything: the counters (CPU, disk, network) and what needs no
  difference (memory, load, disks, sensors, cards, Claudia's own processes)."
  []
  (-> (all [(text-of "/proc/stat") (text-of "/proc/meminfo") (text-of "/proc/loadavg") (text-of "/proc/uptime")
            (text-of "/proc/net/dev") (text-of "/proc/diskstats") (hardware "/sys/class/net") (hardware "/sys/block")
            (disks) (temps) (gpus) (claudia-memory)])
      (.then (fn [[st mem lavg up net dio ifaces blocks ds ts gs own]]
               {:t (js/Date.now) :cpu (proc/cpu-times (or st "")) :memory (proc/memory (proc/meminfo (or mem "")))
                :load (proc/loadavg (or lavg "")) :uptime (proc/uptime (or up ""))
                :net (proc/net-bytes (or net "")) :dio (proc/disk-bytes (or dio "")) :ifaces ifaces :blocks blocks
                :disks ds :temps ts :gpus gs :claudia own}))))

(defn darwin-memory
  "Claudia's own processes on macOS from ps-rows, grouped like on Linux."
  [rows]
  (let [rss (into {} (map (juxt :pid :rss) rows))]
    (proc/group-rss (filterv (comp pos? second) (for [[p k] (kinds-of rows)] [k (get rss p 0)])))))

(defn- read-darwin
  "Promise of one read on macOS: os.cpus() for the cores, its tools for the rest; no sensors."
  []
  (-> (all [(text-from "vm_stat") (text-from "sysctl" "vm.swapusage") (text-from "netstat" "-ibn")
            (text-from "ioreg" "-r" "-c" "IOBlockStorageDriver" "-k" "Statistics")
            (.then (text-from "mount") #(spaces (darwin/disk-mounts %)))
            (text-from "ps" "-axo" "pid=,ppid=,rss=,command=")])
      (.then (fn [[vm swap net dio ds ps]]
               (let [nets (darwin/net-bytes net)]
                 {:t (js/Date.now) :cpu (darwin/cpu-times (map (fn [^js c] (js->clj (.-times c) :keywordize-keys true)) (os/cpus)))
                  :memory (darwin/memory (os/totalmem) (darwin/vm-stat vm) (darwin/swap-usage swap))
                  :load (vec (os/loadavg)) :uptime (os/uptime)
                  :net nets :ifaces (darwin/physical-ifaces (keys nets)) :dio (darwin/disk-bytes dio) :blocks ["all"]
                  :disks ds :temps [] :gpus [] :claudia (darwin-memory (darwin/ps-rows ps))})))))

(defn- read-all []
  (if (= "darwin" (os/platform)) (read-darwin) (read-linux)))

(defn sample-of
  "The sample a page sees from two reads a then b: use over the window between them, the rest from b."
  [a b model]
  (let [secs (/ (- (:t b) (:t a)) 1000)
        cpu (proc/cpu-use (:cpu a) (:cpu b))]
    {:at (:t b) :window (- (:t b) (:t a))
     :cpu (assoc cpu :model model :load (:load b))
     :uptime (:uptime b) :memory (:memory b) :disks (:disks b) :temps (:temps b) :gpus (:gpus b) :claudia (:claudia b)
     :io {:read (proc/rate (:dio a) (:dio b) (:blocks b) :read secs) :write (proc/rate (:dio a) (:dio b) (:blocks b) :written secs)}
     :net {:rx (proc/rate (:net a) (:net b) (:ifaces b) :rx secs) :tx (proc/rate (:net a) (:net b) (:ifaces b) :tx secs)}}))

;; {:asked ms :sample s :starting promise-or-nil}: the sampler's state; :starting is set while it runs.
(defonce ^:private state (atom {:asked 0 :sample nil :starting nil}))

(defn- cpu-model [] (some-> (first (os/cpus)) .-model str/trim))

(defn- tick!
  "Reads, keeps the sample against the read before (prev), and reads again in 2 s, or stops when
  nobody asked for 30 s. Promise of the sample."
  [prev]
  (.then (read-all)
         (fn [now]
           (let [s (sample-of prev now (cpu-model))
                 idle? (> (- (:t now) (:asked @state)) idle-after)]
             (swap! state (fn [st] (if idle? (assoc st :sample nil :starting nil) (assoc st :sample s))))
             (when-not idle? (js/setTimeout #(tick! now) every))
             s))))

(defn- start! []
  (let [p (-> (read-all)
              (.then (fn [r] (js/Promise. (fn [done] (js/setTimeout #(done (tick! r)) first-window))))))]
    (swap! state assoc :starting p)
    (.catch p (fn [_] (swap! state assoc :starting nil)))
    p))

(defn latest!
  "Promise of the newest sample, as {sample… :age ms since it was taken}; the first ask after a
  quiet spell starts the reads and waits for the first window (1 s)."
  []
  (swap! state assoc :asked (js/Date.now))
  (let [{:keys [sample starting]} @state]
    (.then (cond sample (js/Promise.resolve sample) starting starting :else (start!))
           #(assoc % :age (- (js/Date.now) (:at %))))))
