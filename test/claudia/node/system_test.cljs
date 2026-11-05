(ns claudia.node.system-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.node.proc :as proc]
            [claudia.node.system :as system]))

(deftest kinds-cover-what-the-hosts-started
  (is (= {1 "front" 2 "host" 3 "chats" 4 "chats" 6 "runner"}
         (system/kinds-of [{:pid 1 :ppid 0 :kind "front"} {:pid 2 :ppid 0 :kind "host"} {:pid 3 :ppid 2}
                           {:pid 4 :ppid 3} {:pid 5 :ppid 0} {:pid 6 :ppid 0 :kind "runner"}]))))

(deftest a-sample-is-the-window-between-two-reads
  (let [a {:t 1000 :cpu (proc/cpu-times "cpu  0 0 0 100 0 0 0 0\ncpu0 0 0 0 100 0 0 0 0\n")
           :net {"eth0" {:rx 0 :tx 0}} :dio {"sda" {:read 0 :written 0}}}
        b {:t 3000 :cpu (proc/cpu-times "cpu  50 0 0 150 0 0 0 0\ncpu0 50 0 0 150 0 0 0 0\n")
           :net {"eth0" {:rx 4000 :tx 2000}} :dio {"sda" {:read 1024 :written 0}} :ifaces ["eth0"] :blocks ["sda"]
           :load [1 2 3] :uptime 9 :memory {:total 1} :disks [] :temps [] :gpus [] :claudia []}
        s (system/sample-of a b "CPU X")]
    (is (= 3000 (:at s)))
    (is (= 2000 (:window s)))
    (is (= 50 (get-in s [:cpu :all :busy])))
    (is (= "CPU X" (get-in s [:cpu :model])))
    (is (= {:rx 2000 :tx 1000} (:net s)))
    (is (= {:read 512 :write 0} (:io s)))))
