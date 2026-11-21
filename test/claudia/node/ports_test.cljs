(ns claudia.node.ports-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.node.ports :as ports]))

;; Real `ss -Htlnup` lines (addresses replaced by documentation ones; the columns as ss pads them).
(def ss-text
  (str "udp UNCONN 0      0                       192.0.2.10:50833 0.0.0.0:* users:((\"vesktop\",pid=224824,fd=42))\n"
       "udp UNCONN 0      0                             0.0.0.0:57431 0.0.0.0:*\n"
       "udp UNCONN 0      0                       127.0.0.53%lo:53    0.0.0.0:*\n"
       "udp UNCONN 0      0                         224.0.0.251:5353  0.0.0.0:* users:((\"chrome\",pid=3819826,fd=40))\n"
       "udp UNCONN 0      0                         224.0.0.251:5353  0.0.0.0:* users:((\"chrome\",pid=3819826,fd=32))\n"
       "tcp LISTEN 0      4096            100.100.1.1%tailscale0:58436 0.0.0.0:*\n"
       "tcp LISTEN 0      4096                    192.0.2.10:4101  0.0.0.0:* users:((\"caddy\",pid=2572979,fd=4))\n"
       "tcp LISTEN 0      128                           [::]:22      [::]:* users:((\"sshd\",pid=900,fd=4),(\"sshd\",pid=901,fd=4))\n"
       "tcp LISTEN 0      511                          [::1]:4100     [::]:*\n"
       "tcp LISTEN 0      4096                              *:9090        *:*\n"))

(deftest ports-from-ss
  (is (= [{:proto "tcp" :port 22 :addr "::" :reach "all" :name "sshd" :pid 900}
          {:proto "udp" :port 53 :addr "127.0.0.53" :reach "local"}
          {:proto "tcp" :port 4100 :addr "::1" :reach "local"}
          {:proto "tcp" :port 4101 :addr "192.0.2.10" :reach "lan" :name "caddy" :pid 2572979}
          {:proto "udp" :port 5353 :addr "224.0.0.251" :reach "lan" :name "chrome" :pid 3819826}
          {:proto "tcp" :port 9090 :addr "*" :reach "all"}
          {:proto "udp" :port 50833 :addr "192.0.2.10" :reach "lan" :name "vesktop" :pid 224824}
          {:proto "udp" :port 57431 :addr "0.0.0.0" :reach "all"}
          {:proto "tcp" :port 58436 :addr "100.100.1.1" :reach "headscale"}]
         (ports/ss-ports ss-text))))

(deftest who-an-address-reaches
  (is (= ["all" "all" "headscale" "headscale" "lan" "local" "local" "local" "lan"]
         (mapv ports/reach-of ["0.0.0.0" "::" "100.64.0.0" "fd7a:115c:a1e0::1" "100.128.0.1" "127.0.0.1" "::1"
                               "::ffff:127.0.0.1" "fe80::1"]))))

(deftest addresses-split
  (is (= ["::" 22] (ports/split-address "[::]:22")))
  (is (= ["127.0.0.53" 53] (ports/split-address "127.0.0.53%lo:53")))
  (is (nil? (ports/split-address "0.0.0.0:*"))))

(deftest ports-from-lsof
  (is (= [{:pid 300 :name "rapportd" :proto "udp" :port 3722 :addr "*" :reach "all"}
          {:pid 120 :name "node" :proto "tcp" :port 4540 :addr "192.0.2.2" :reach "lan"}]
         (ports/lsof-ports "p120\ncnode\nf21\nPTCP\nn192.0.2.2:4540\n"
                           "p300\ncrapportd\nf5\nPUDP\nn*:3722\nf6\nPUDP\nn192.0.2.2:5000->192.0.2.9:6000\n"))))
