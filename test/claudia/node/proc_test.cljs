(ns claudia.node.proc-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.node.proc :as proc]))

(def stat-a "cpu  100 0 50 800 50 0 0 0 0 0\ncpu0 60 0 20 400 20 0 0 0 0 0\ncpu1 40 0 30 400 30 0 0 0 0 0\nintr 1 2 3\n")
(def stat-b "cpu  200 0 100 1000 60 0 0 40 0 0\ncpu0 160 0 20 420 20 0 0 0 0 0\ncpu1 40 0 80 580 40 0 0 40 0 0\nintr 1 2 3\n")

(deftest cpu-use-is-the-difference-of-two-reads-per-core
  (let [{:keys [all cores]} (proc/cpu-use (proc/cpu-times stat-a) (proc/cpu-times stat-b))]
    (testing "core 0: 100 user of 120 ticks"
      (is (= {:busy 83.3 :user 83.3 :sys 0 :iowait 0 :steal 0} (first cores))))
    (testing "core 1: system 50, steal 40, iowait 10 of 280; waiting on disk is not busy"
      (is (= {:busy 32.1 :user 0 :sys 17.9 :iowait 3.6 :steal 14.3} (second cores))))
    (is (= 2 (count cores)))
    (is (= 47.5 (:busy all)))))

(deftest no-time-no-share
  (let [t (proc/cpu-times stat-a)]
    (is (nil? (proc/cpu-share (get t "cpu0") (get t "cpu0"))))))

(deftest cores-sort-by-number
  (is (= ["cpu0" "cpu2" "cpu10"] (proc/core-order {"cpu10" {} "cpu" {} "cpu2" {} "cpu0" {}}))))

(def meminfo "MemTotal:       32000000 kB\nMemFree:         2000000 kB\nMemAvailable:   20000000 kB\nBuffers:          100000 kB\nCached:          9000000 kB\nSwapTotal:       8000000 kB\nSwapFree:        7000000 kB\nHugePages_Total:       0\n")

(deftest memory-used-is-total-less-available
  (is (= {:total 32768000000 :available 20480000000 :used 12288000000 :cached 9318400000
          :swapTotal 8192000000 :swapUsed 1024000000}
         (proc/memory (proc/meminfo meminfo)))))

(deftest load-and-uptime
  (is (= [1.5 0.75 0.2] (proc/loadavg "1.50 0.75 0.20 2/900 12345\n")))
  (is (= 3600.5 (proc/uptime "3600.50 7000.00\n"))))

(def netdev "Inter-|   Receive                                                |  Transmit\n face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n    lo: 500 5 0 0 0 0 0 0 500 5 0 0 0 0 0 0\n  eth0: 1000 10 0 0 0 0 0 0 2000 20 0 0 0 0 0 0\n")

(deftest network-and-disk-rates
  (let [a (proc/net-bytes netdev)
        b (update a "eth0" #(-> % (update :rx + 4000) (update :tx + 1000)))]
    (is (= {:rx 1000 :tx 2000} (get a "eth0")))
    (is (= 2000 (proc/rate a b ["eth0"] :rx 2)))
    (testing "only the names given count; a counter going back counts 0"
      (is (= 0 (proc/rate a (assoc-in b ["eth0" :tx] 0) ["eth0"] :tx 2)))
      (is (= 0 (proc/rate a b ["lo"] :rx 2)))))
  (is (= {"nvme0n1" {:read 512000 :written 1024000}}
         (proc/disk-bytes " 259       0 nvme0n1 10 0 1000 5 20 0 2000 7 0 9 12\n"))))

(def mountinfo
  (str "22 1 259:2 / / rw,relatime shared:1 - btrfs /dev/nvme0n1p2 rw\n"
       "23 22 259:3 /home /home rw shared:2 - btrfs /dev/nvme0n1p2 rw\n"
       "24 22 0:5 / /proc rw - proc proc rw\n"
       "25 22 8:1 / /mnt/my\\040disk rw - ext4 /dev/sda1 rw\n"
       "26 22 0:30 / /tmp rw - tmpfs tmpfs rw\n"))

(deftest real-filesystems-once-per-device
  (is (= [{:dev "259:2" :root "/" :mount "/" :fs "btrfs" :source "/dev/nvme0n1p2"}
          {:dev "8:1" :root "/" :mount "/mnt/my disk" :fs "ext4" :source "/dev/sda1"}]
         (proc/disk-mounts mountinfo)))
  (is (= {:size 4096000 :used 1024000 :avail 2048000}
         (proc/disk-space {:bsize 4096 :blocks 1000 :bfree 750 :bavail 500}))))

(deftest processes
  (is (= {:comm "node (x) y" :ppid 7} (proc/proc-stat "42 (node (x) y) S 7 42 42 0 -1")))
  (is (= "runner" (proc/claudia-kind "node\u0000/srv/claudia/out/runner.js\u0000")))
  (is (= "hub" (proc/claudia-kind "node\u0000hub.js\u0000")))
  (is (nil? (proc/claudia-kind "node\u0000/srv/other/server.js\u0000")))
  (is (= #{3 4 5} (proc/descendants-of {1 0 2 1 3 2 4 3 5 2 6 1} [2])))
  (is (= 123904 (proc/vm-rss "Name:\tnode\nVmRSS:\t     121 kB\n")))
  (is (= [{:kind "front" :count 1 :rss 5} {:kind "chats" :count 2 :rss 7}]
         (proc/group-rss [["chats" 3] ["front" 5] ["chats" 4]]))))

(deftest sensors-and-cards
  (is (= {:name "CPU" :label "Tctl" :c 45.1} (proc/temp-row {:name "k10temp" :label "Tctl" :milli 45125 :device "0000:00:18.3"})))
  (is (= {:name "nvme0" :label "Composite" :c 38} (proc/temp-row {:name "nvme" :label "Composite" :milli 38000 :device "nvme0"})))
  (is (nil? (proc/temp-row {:name "iwlwifi_1" :milli 40000})))
  (is (= [{:index 0 :name "NVIDIA GeForce RTX 4090" :util 7 :memUsed 1048576000 :memTotal 25757220864 :temp 41 :power 30.5}
          {:index 1 :name "Tesla" :util nil :memUsed nil :memTotal nil :temp nil :power nil}]
         (proc/gpu-rows "0, NVIDIA GeForce RTX 4090, 7, 1000, 24564, 41, 30.50\n1, Tesla, [N/A], [N/A], [N/A], [N/A], [N/A]\n"))))
