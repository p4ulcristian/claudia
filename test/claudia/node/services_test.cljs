(ns claudia.node.services-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.node.services :as services]))

;; Real `systemctl list-units --type=service --output=json` and `systemctl show` output (trimmed).
(def list-text
  (str "[{\"unit\":\"bluetooth.service\",\"load\":\"loaded\",\"active\":\"active\",\"sub\":\"running\",\"description\":\"Bluetooth service\"},"
       "{\"unit\":\"web-firewall.service\",\"load\":\"loaded\",\"active\":\"failed\",\"sub\":\"failed\",\"description\":\"A firewall\"},"
       "{\"unit\":\"claudia-front.service\",\"load\":\"loaded\",\"active\":\"active\",\"sub\":\"running\",\"description\":\"Claudia front\"},"
       "{\"unit\":\"old.service\",\"load\":\"loaded\",\"active\":\"inactive\",\"sub\":\"dead\",\"description\":\"Old\"},"
       "{\"unit\":\"dev-sda.device\",\"load\":\"loaded\",\"active\":\"active\",\"sub\":\"plugged\",\"description\":\"A disk\"}]"))

(def show-text
  (str "Id=bluetooth.service\nDescription=Bluetooth service\nActiveState=active\nSubState=running\n"
       "FragmentPath=/usr/lib/systemd/system/bluetooth.service\nStateChangeTimestampMonotonic=958534799438\nNRestarts=0\nMemoryCurrent=1720320\n\n"
       "Id=web-firewall.service\nDescription=A firewall\nActiveState=failed\nSubState=failed\n"
       "FragmentPath=/etc/systemd/system/web-firewall.service\nStateChangeTimestampMonotonic=0\nNRestarts=3\nMemoryCurrent=[not set]\n\n"
       "Id=claudia-front.service\nActiveState=active\nSubState=running\nFragmentPath=/etc/systemd/system/claudia-front.service\n"
       "StateChangeTimestampMonotonic=1000000000\nNRestarts=0\nMemoryCurrent=18446744073709551615\n"))

(deftest services-from-systemctl
  (let [rows (services/services list-text show-text {:scope "system" :now 2000000 :uptime-ms 1500000})]
    (is (= ["bluetooth.service" "web-firewall.service" "claudia-front.service"] (mapv :name rows)))
    (testing "since: the boot (now less uptime) plus systemd's monotonic µs"
      (is (= 1500000 (:since (nth rows 2))))
      (is (nil? (:since (second rows)))))
    (is (= {:name "bluetooth.service" :scope "system" :state "active" :sub "running" :since 959034799
            :restarts 0 :desc "Bluetooth service" :origin "os" :rss 1720320}
           (first rows)))
    (is (= [3 nil] [(:restarts (second rows)) (:rss (second rows))]))
    (is (= ["os" "admin" "claudia"] (mapv :origin rows)))
    (is (not (contains? (nth rows 2) :rss)))))

(deftest where-a-unit-comes-from
  (is (= "container" (services/origin-of "web.service" "/run/systemd/generator/web.service")))
  (is (= "container" (services/origin-of "container-db.service" "/etc/systemd/system/container-db.service")))
  (is (= "os" (services/origin-of "sshd.service" "/lib/systemd/system/sshd.service")))
  (is (= "admin" (services/origin-of "worker.service" "/home/a/.config/systemd/user/worker.service")))
  (is (= "admin" (services/origin-of "run-u1.service" ""))))

(deftest services-from-launchctl
  (is (= [{:name "com.apple.Finder" :scope "user" :state "active" :sub "running" :since nil :restarts nil :desc "" :origin "os"}
          {:name "local.claudia.front" :scope "user" :state "failed" :sub "failed" :since nil :restarts nil :desc "" :origin "claudia"}]
         (services/launchctl-services "PID\tStatus\tLabel\n512\t0\tcom.apple.Finder\n-\t78\tlocal.claudia.front\n-\t0\tcom.apple.idle\n"))))

(deftest containers-from-docker-and-podman
  ;; A real `docker ps -a --format '{{json .}}'` line, its labels and mounts left out.
  (is (= [{:engine "docker" :id "c10045ebc245" :name "test-vm" :image "example/vm" :state "running"
           :since 1794856024000 :ports ["0.0.0.0:8006->8006/tcp" "[::]:8006->8006/tcp"]}]
         (services/docker-containers
          "{\"CreatedAt\":\"2026-11-16 20:07:04 +0100 CET\",\"ID\":\"c10045ebc2451234\",\"Image\":\"example/vm\",\"Names\":\"test-vm\",\"Ports\":\"0.0.0.0:8006->8006/tcp, [::]:8006->8006/tcp\",\"State\":\"running\"}\n")))
  (is (= [{:engine "podman" :id "0123456789ab" :name "db" :image "docker.io/library/postgres:16" :state "running"
           :since 1700000000000 :ports ["127.0.0.1:5432->5432/tcp"]}]
         (services/podman-containers
          "[{\"Id\":\"0123456789abcdef\",\"Names\":[\"db\"],\"Image\":\"docker.io/library/postgres:16\",\"State\":\"running\",\"StartedAt\":1700000000,\"Ports\":[{\"host_ip\":\"127.0.0.1\",\"container_port\":5432,\"host_port\":5432,\"protocol\":\"tcp\"}]}]")))
  (is (= {"0123456789ab" "db"} (services/container-names [{:id "0123456789ab" :name "db"}])))
  (is (= [] (services/podman-containers ""))))
