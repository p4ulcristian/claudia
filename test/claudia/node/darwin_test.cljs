(ns claudia.node.darwin-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.node.darwin :as darwin]
            [claudia.node.proc :as proc]
            [claudia.node.system :as system]))

(deftest cores-from-os-cpus
  (let [a (darwin/cpu-times [{:user 100 :nice 0 :sys 50 :idle 850 :irq 0} {:user 0 :nice 0 :sys 0 :idle 1000 :irq 0}])
        b (darwin/cpu-times [{:user 400 :nice 0 :sys 50 :idle 1550 :irq 0} {:user 0 :nice 0 :sys 0 :idle 2000 :irq 0}])
        {:keys [all cores]} (proc/cpu-use a b)]
    (is (= {:user 100 :nice 0 :system 50 :idle 1850 :irq 0 :iowait 0 :softirq 0 :steal 0} (get a "cpu")))
    (is (= [30 0] (map :busy cores)))
    (is (= 15 (:busy all)))))

(def vm-stat
  (str "Mach Virtual Memory Statistics: (page size of 16384 bytes)\n"
       "Pages free:                                5000.\n"
       "Pages wired down:                         100000.\n"
       "Pages purgeable:                           2000.\n"
       "\"Translation faults\":                  123456789.\n"
       "File-backed pages:                        200000.\n"
       "Anonymous pages:                          300000.\n"
       "Pages occupied by compressor:              50000.\n"))

(deftest memory-as-activity-monitor-counts-it
  (let [vm (darwin/vm-stat vm-stat)
        swap (darwin/swap-usage "vm.swapusage: total = 2048.00M  used = 1024.50M  free = 1023.50M  (encrypted)\n")
        total (* 16 1073741824)]
    (is (= (* 16384 300000) (get vm "Anonymous pages")))
    (is (= (* 16384 123456789) (get vm "Translation faults")))
    (is (= {:total 2147483648 :used 1074266112} swap))
    (is (= {:total total :used (* 16384 448000) :available (- total (* 16384 448000)) :cached (* 16384 200000)
            :swapTotal 2147483648 :swapUsed 1074266112}
           (darwin/memory total vm swap)))))

(deftest network-and-disks
  (let [net (darwin/net-bytes (str "Name       Mtu   Network       Address            Ipkts Ierrs     Ibytes    Opkts Oerrs     Obytes  Coll\n"
                                   "lo0        16384 <Link#1>                         100     0      5000      100     0      5000     0\n"
                                   "en0        1500  <Link#11>   aa:bb:cc:dd:ee:ff    900     0    800000      700     0    300000     0\n"
                                   "en0        1500  192.168.0     192.168.0.5          900     -    800000      700     -    300000     -\n"))]
    (is (= {"lo0" {:rx 5000 :tx 5000} "en0" {:rx 800000 :tx 300000}} net))
    (is (= ["en0" "en12"] (darwin/physical-ifaces ["lo0" "en0" "utun3" "awdl0" "en12" "bridge0"])))
    (is (= {"all" {:read 3000 :written 40}}
           (darwin/disk-bytes "\"Statistics\" = {\"Bytes (Read)\"=1000,\"Bytes (Write)\"=40}\n\"Statistics\" = {\"Bytes (Read)\"=2000,\"Bytes (Write)\"=0}")))))

(deftest one-row-per-container
  (is (= [{:source "/dev/disk3s1s1" :mount "/" :fs "apfs"}
          {:source "/dev/disk5s1" :mount "/Volumes/My Disk" :fs "exfat"}]
         (darwin/disk-mounts (str "/dev/disk3s1s1 on / (apfs, sealed, local, read-only, journaled)\n"
                                  "devfs on /dev (devfs, local, nobrowse)\n"
                                  "/dev/disk3s5 on /System/Volumes/Data (apfs, local, journaled, nobrowse)\n"
                                  "/dev/disk5s1 on /Volumes/My Disk (exfat, local, nodev, nosuid)\n")))))

(deftest claudia-processes-from-ps
  (let [rows (darwin/ps-rows (str "  10     1  20000 node /Users/x/claudia/out/front.js\n"
                                  "  11     1  30000 /usr/local/bin/node /Users/x/claudia/out/host.js\n"
                                  "  12    11  50000 claude --output-format stream-json\n"
                                  "  13     1   1000 /bin/zsh\n"))]
    (is (= "front" (:kind (first rows))))
    (is (= [{:kind "front" :count 1 :rss 20480000} {:kind "host" :count 1 :rss 30720000} {:kind "chats" :count 1 :rss 51200000}]
           (system/darwin-memory rows)))))
