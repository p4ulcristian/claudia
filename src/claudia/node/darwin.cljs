(ns claudia.node.darwin
  "The same numbers on macOS, which has no /proc (README decision 12): pure functions over what its
  own tools print (vm_stat, sysctl vm.swapusage, netstat -ibn, ioreg, mount, ps) and over Node's
  os.cpus(), shaped like claudia.node.proc's so one sampler serves both."
  (:require [clojure.string :as str]))

(defn- num-of [s] (let [n (js/Number s)] (if (js/isNaN n) 0 n)))

(defn cpu-times
  "os.cpus()'s per-core times (ms, as {:user :nice :sys :idle :irq}) as claudia.node.proc/cpu-times'
  map: \"cpu0\"… and \"cpu\" their sum (macOS counts no iowait or steal)."
  [cores]
  (let [per (into {} (map-indexed (fn [i {:keys [user nice sys idle irq]}]
                                    [(str "cpu" i) {:user user :nice nice :system sys :idle idle :irq irq
                                                    :iowait 0 :softirq 0 :steal 0}])
                                  cores))]
    (assoc per "cpu" (apply merge-with + (vals per)))))

(defn vm-stat
  "vm_stat's counts as {\"Pages free\" bytes, …}, by the page size its first line names."
  [text]
  (let [page (num-of (second (re-find #"page size of (\d+) bytes" text)))]
    (into {} (for [line (str/split-lines text)
                   :let [[_ k v] (re-matches #"^\"?([^:\"]+)\"?:\s+(\d+)\.?$" (str/trim line))]
                   :when k]
               [k (* page (num-of v))]))))

(defn swap-usage
  "sysctl vm.swapusage (\"total = 2048.00M  used = 1035.75M …\") as {:total :used} in bytes."
  [text]
  (let [mb #(* 1048576 (num-of (second (re-find (re-pattern (str % " = ([\\d.]+)M")) text))))]
    {:total (mb "total") :used (mb "used")}))

(defn memory
  "The card's memory as Activity Monitor counts it: used = app memory (anonymous less purgeable) +
  wired + compressed; available = the rest; cached = file-backed pages."
  [total vm swap]
  (let [app (max 0 (- (get vm "Anonymous pages" 0) (get vm "Pages purgeable" 0)))
        used (min total (+ app (get vm "Pages wired down" 0) (get vm "Pages occupied by compressor" 0)))]
    {:total total :available (- total used) :used used :cached (get vm "File-backed pages" 0)
     :swapTotal (:total swap) :swapUsed (:used swap)}))

(defn net-bytes
  "netstat -ibn's link rows as {interface {:rx :tx}} (bytes are the 5th and 2nd fields from the end:
  an interface without an address has one field less in the middle)."
  [text]
  (into {} (for [line (rest (str/split-lines text))
                 :let [vs (str/split (str/trim line) #"\s+")]
                 :when (and (>= (count vs) 8) (str/starts-with? (nth vs 2) "<Link#"))]
             [(first vs) {:rx (num-of (nth vs (- (count vs) 5))) :tx (num-of (nth vs (- (count vs) 2)))}])))

(defn physical-ifaces
  "The network cards: en0, en1… (not lo0, utun, awdl, llw, bridges)."
  [ifaces]
  (filterv #(re-matches #"^en\d+$" %) ifaces))

(defn disk-bytes
  "ioreg -r -c IOBlockStorageDriver -k Statistics: every drive's bytes read and written, summed, as
  {\"all\" {:read :written}}."
  [text]
  (let [total #(reduce + (map (comp num-of second) (re-seq (re-pattern (str "\"Bytes \\(" % "\\)\"=(\\d+)")) text)))]
    {"all" {:read (total "Read") :written (total "Write")}}))

(def real-fs #{"apfs" "hfs" "exfat" "msdos" "ntfs"})

(defn disk-mounts
  "mount's lines as the filesystems worth a row: one per APFS container or partition (the volumes of
  a container share its space), the shortest mount point kept."
  [text]
  (->> (for [line (str/split-lines text)
             :let [[_ source mount fs] (re-matches #"^(\S+) on (.+) \((\w+)[,)].*$" line)]
             :when (and fs (real-fs fs))]
         {:source source :mount mount :fs fs :key (or (re-find #"disk\d+" source) source)})
       (group-by :key)
       vals
       (map #(dissoc (first (sort-by (juxt (comp count :mount) :mount) %)) :key))
       (sort-by :mount)
       vec))

(defn ps-rows
  "ps -axo pid=,ppid=,rss=,command= as [{:pid :ppid :rss (bytes) :kind}], kind as
  claudia.node.proc/claudia-kind names it."
  [text]
  (vec (for [line (str/split-lines text)
             :let [[_ pid ppid rss command] (re-matches #"^\s*(\d+)\s+(\d+)\s+(\d+)\s+(.*)$" line)]
             :when pid]
         {:pid (js/parseInt pid 10) :ppid (js/parseInt ppid 10) :rss (* 1024 (num-of rss))
          :kind (second (re-find #"(?:^|[/\s])(front|runner|host|hub)\.js(?:\s|$)" command))})))
