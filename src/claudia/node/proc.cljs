(ns claudia.node.proc
  "The kernel's own numbers, read as text from /proc and /sys and turned into data (README decision
  12): pure functions, no reads. CPU use is never since boot: a share is the difference between two
  reads of /proc/stat, as the kernel counted it per core."
  (:require [clojure.string :as str]))

(defn- num-of [s] (let [n (js/Number s)] (if (js/isNaN n) 0 n)))

(defn- words [line] (str/split (str/trim line) #"\s+"))

;; ---- /proc/stat: CPU time per core ----

(defn cpu-times
  "/proc/stat as {\"cpu\" ticks, \"cpu0\" ticks, …}, ticks = {:user :nice :system :idle :iowait :irq
  :softirq :steal} in clock ticks since boot (guest time is already inside user and nice)."
  [text]
  (into {} (for [line (str/split-lines text)
                 :let [[k & vs] (words line)]
                 :when (and k (re-matches #"^cpu\d*$" k))]
             [k (zipmap [:user :nice :system :idle :iowait :irq :softirq :steal] (map num-of (concat vs (repeat "0"))))])))

(defn- round1 [x] (/ (js/Math.round (* 10 x)) 10))

(defn cpu-share
  "One CPU's use between two reads (ticks a, then b) in percent of its time: :busy (user, system and
  steal: not idle, not waiting on disk), :user, :sys (system, irq, softirq), :iowait, :steal. nil when no
  time passed (no new tick)."
  [a b]
  (let [d #(max 0 (- (get b % 0) (get a % 0)))
        total (reduce + (map d [:user :nice :system :idle :iowait :irq :softirq :steal]))
        pct #(round1 (* 100 (/ % total)))]
    (when (pos? total)
      (let [user (+ (d :user) (d :nice)) sys (+ (d :system) (d :irq) (d :softirq))]
        {:busy (pct (+ user sys (d :steal))) :user (pct user) :sys (pct sys)
         :iowait (pct (d :iowait)) :steal (pct (d :steal))}))))

(defn core-order
  "The per-core keys of cpu-times in core order (cpu0, cpu1, … cpu10)."
  [times]
  (sort-by #(js/parseInt (subs % 3) 10) (filter #(re-matches #"^cpu\d+$" %) (keys times))))

(defn cpu-use
  "Between two cpu-times reads: {:all share :cores [share …]} (a core that went offline is left out)."
  [a b]
  {:all (cpu-share (get a "cpu") (get b "cpu"))
   :cores (vec (keep #(when (contains? a %) (cpu-share (get a %) (get b %))) (core-order b)))})

;; ---- memory, load, uptime ----

(defn meminfo
  "/proc/meminfo as {\"MemTotal\" bytes, …} (the kernel's kB are 1024 bytes)."
  [text]
  (into {} (for [line (str/split-lines text)
                 :let [[_ k v] (re-matches #"^([\w()]+):\s+(\d+)(?:\s+kB)?$" (str/trim line))]
                 :when k]
             [k (* 1024 (num-of v))])))

(defn memory
  "What the card shows from meminfo: used = total − available (MemAvailable: what can be had without
  swapping, so the page cache does not count as used)."
  [m]
  (let [total (get m "MemTotal" 0)
        available (get m "MemAvailable" (+ (get m "MemFree" 0) (get m "Cached" 0)))]
    {:total total :available available :used (max 0 (- total available))
     :cached (+ (get m "Cached" 0) (get m "Buffers" 0))
     :swapTotal (get m "SwapTotal" 0)
     :swapUsed (max 0 (- (get m "SwapTotal" 0) (get m "SwapFree" 0)))}))

(defn loadavg
  "/proc/loadavg's 1, 5 and 15 minute load."
  [text]
  (mapv num-of (take 3 (words text))))

(defn uptime
  "/proc/uptime's seconds since boot."
  [text]
  (num-of (first (words text))))

;; ---- network and disk I/O ----

(defn net-bytes
  "/proc/net/dev as {interface {:rx bytes :tx bytes}}."
  [text]
  (into {} (for [line (drop 2 (str/split-lines text))
                 :let [[iface rest-part] (map str/trim (str/split line #":" 2))
                       vs (when rest-part (words rest-part))]
                 :when (and (seq iface) (>= (count vs) 9))]
             [iface {:rx (num-of (nth vs 0)) :tx (num-of (nth vs 8))}])))

(defn disk-bytes
  "/proc/diskstats as {device {:read bytes :written bytes}} (the kernel counts 512-byte sectors,
  whatever the disk's own sector size)."
  [text]
  (into {} (for [line (str/split-lines text)
                 :let [vs (words line)]
                 :when (>= (count vs) 10)]
             [(nth vs 2) {:read (* 512 (num-of (nth vs 5))) :written (* 512 (num-of (nth vs 9)))}])))

(defn rate
  "Per second, summed over the names in keep-names, of field k between two reads a and b secs apart
  (a counter that went back, a reset, counts 0)."
  [a b keep-names k secs]
  (if (pos? secs)
    (js/Math.round (/ (reduce + (for [n keep-names :when (and (get a n) (get b n))]
                                  (max 0 (- (get-in b [n k]) (get-in a [n k])))))
                      secs))
    0))

;; ---- filesystems ----

(def real-fs
  "Filesystems that hold files on a disk (not proc, tmpfs, overlay, squashfs…)."
  #{"ext2" "ext3" "ext4" "xfs" "btrfs" "zfs" "f2fs" "bcachefs" "vfat" "exfat" "ntfs" "ntfs3" "fuseblk" "jfs" "reiserfs"})

(defn- unescape
  "A mountinfo path: the kernel writes space, tab, newline and backslash as \\040, \\011, \\012, \\134."
  [s]
  (str/replace s #"\\([0-7]{3})" (fn [[_ o]] (js/String.fromCharCode (js/parseInt o 8)))))

(defn mount-line
  "One /proc/self/mountinfo line as {:dev \"maj:min\" :root :mount :fs :source}, or nil."
  [line]
  (let [[before after] (str/split line #" - " 2)
        [_ _ dev root mount] (words (or before ""))
        [fs source] (words (or after ""))]
    (when (and mount fs)
      {:dev dev :root (unescape root) :mount (unescape mount) :fs fs :source (unescape (or source ""))})))

(defn disk-mounts
  "The filesystems worth a row: real ones only, one per device (a disk mounted twice, or btrfs
  subvolumes of one partition, report the same space), the shortest mount point kept."
  [text]
  (->> (keep mount-line (str/split-lines text))
       (filter #(real-fs (:fs %)))
       (group-by #(if (str/starts-with? (:source %) "/dev/") (:source %) (:dev %)))
       vals
       (map #(first (sort-by (juxt (comp count :mount) :mount) %)))
       (sort-by :mount)
       vec))

(defn disk-space
  "A mount's space from fs.statfs's numbers: size, used (taken from the whole, as df counts it) and
  avail (what an ordinary user may still write; root's reserve is in neither)."
  [{:keys [bsize blocks bfree bavail]}]
  {:size (* bsize blocks) :used (* bsize (- blocks bfree)) :avail (* bsize bavail)})

;; ---- processes ----

(defn proc-stat
  "/proc/<pid>/stat as {:comm :ppid}; the name is in parentheses and may hold spaces or parentheses."
  [text]
  (let [open (str/index-of text "(") close (str/last-index-of text ")")]
    (when (and open close)
      {:comm (subs text (inc open) close)
       :ppid (js/parseInt (nth (words (subs text (inc close))) 1) 10)})))

(defn claudia-kind
  "Which Claudia program a /proc/<pid>/cmdline (NUL-separated) runs: \"front\", \"runner\", \"host\",
  \"hub\", or nil."
  [cmdline]
  (some #(second (re-find #"(?:^|/)(front|runner|host|hub)\.js$" %)) (str/split cmdline #"\u0000")))

(defn descendants-of
  "Every pid under the roots, by parents (pid -> ppid)."
  [parent-of roots]
  (let [children (group-by parent-of (keys parent-of))]
    (loop [todo (vec roots) seen #{}]
      (if-let [p (peek todo)]
        (let [kids (remove seen (get children p))]
          (recur (into (pop todo) kids) (into seen kids)))
        seen))))

(defn vm-rss
  "/proc/<pid>/status's resident memory in bytes (VmRSS), 0 when absent (a kernel thread)."
  [text]
  (* 1024 (num-of (second (re-find #"(?m)^VmRSS:\s+(\d+)" text)))))

(defn group-rss
  "[[kind rss] …] as [{:kind :count :rss}] in the order front, runner, host, chats, hub."
  [pairs]
  (let [by (group-by first pairs)]
    (vec (for [k ["front" "runner" "host" "chats" "hub"] :let [ps (get by k)] :when ps]
           {:kind k :count (count ps) :rss (reduce + (map second ps))}))))

;; ---- sensors ----

(def cpu-sensors #{"k10temp" "coretemp" "zenpower" "cpu_thermal" "soc_thermal"})

(defn temp-row
  "A hwmon device's first temperature as a row, for CPUs and drives only: {:name :label :c}, or nil."
  [{sensor :name :keys [device label milli]}]
  (when (and milli (or (cpu-sensors sensor) (#{"nvme" "drivetemp"} sensor)))
    {:name (if (cpu-sensors sensor) "CPU" device)
     :label (or (not-empty label) sensor)
     :c (round1 (/ milli 1000))}))

(defn gpu-rows
  "nvidia-smi --query-gpu=index,name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw
  --format=csv,noheader,nounits as rows (MiB as bytes; a field the card does not report is nil)."
  [text]
  (vec (for [line (str/split-lines text)
             :let [[i gpu-name util used total temp power] (map str/trim (str/split line #","))
                   n #(let [v (js/Number %)] (when-not (or (str/blank? %) (js/isNaN v)) v))]
             :when (seq gpu-name)]
         {:index (n i) :name gpu-name :util (n util)
          :memUsed (some-> (n used) (* 1048576)) :memTotal (some-> (n total) (* 1048576))
          :temp (n temp) :power (n power)})))
