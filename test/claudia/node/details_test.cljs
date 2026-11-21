(ns claudia.node.details-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.node.details :as details]))

(deftest the-reads-texts
  (is (= {500 "aaaa"} (details/key-hashes "{\"500\":\"aaaa\",\"x\":\"b\"}")))
  (is (= {} (details/key-hashes "broken")))
  (is (= [{:engine "podman" :id "0123456789ab" :name "db" :image "i" :state "running" :since nil :ports []}]
         (details/containers-of "{\"podman\":\"[{\\\"Id\\\":\\\"0123456789abcdef\\\",\\\"Names\\\":[\\\"db\\\"],\\\"Image\\\":\\\"i\\\",\\\"State\\\":\\\"running\\\"}]\",\"docker\":\"\"}")))
  (is (= [{:name "worker.service" :scope "user" :user "paul" :state "active" :sub "running" :since nil :restarts nil :desc "Worker" :origin "admin"}]
         (details/user-services "{\"paul\":{\"units\":\"[{\\\"unit\\\":\\\"worker.service\\\",\\\"active\\\":\\\"active\\\",\\\"sub\\\":\\\"running\\\",\\\"description\\\":\\\"Worker\\\"}]\",\"show\":\"\"}}"
                                {:now 0 :uptime-ms 0}))))

(def procs
  [{:pid 10 :name "postgres" :cores 0.5 :rss 100 :start 1000 :owner {:kind "unit" :name "postgresql.service"} :service "system/postgresql.service"}
   {:pid 11 :name "postgres" :cores 0.25 :rss 50 :start 1000 :owner {:kind "unit" :name "postgresql.service"} :service "system/postgresql.service"}
   {:pid 12 :name "claude" :cores 0 :rss 70 :start 5000 :owner {:kind "leftover" :name "paul"} :service "system/claudia-host@paul.service"}
   {:pid 13 :name "worker" :cores 1 :rss 10 :start 1 :owner {:kind "user-unit" :name "worker.service@paul"} :service "user/1000/worker.service"}])

(deftest services-get-their-processes-use
  (is (= [{:name "postgresql.service" :scope "system" :rss 7 :cores 0.75}
          {:name "worker.service" :scope "user" :user "paul" :cores 1 :rss 10}
          {:name "sshd.service" :scope "system"}]
         (details/service-usage [{:name "postgresql.service" :scope "system" :rss 7}
                                 {:name "worker.service" :scope "user" :user "paul"}
                                 {:name "sshd.service" :scope "system"}]
                                procs {"paul" 1000}))))

(deftest leftovers-with-their-age
  (is (= [{:pid 12 :name "claude" :cores 0 :rss 70 :owner {:kind "leftover" :name "paul"} :age 950}]
         (details/leftovers procs 1000))))

(deftest ports-get-their-owner
  (is (= [{:port 5432 :pid 10 :owner {:kind "unit" :name "postgresql.service"}} {:port 22}]
         (details/port-owners [{:port 5432 :pid 10} {:port 22}] procs))))

(deftest with-owners-from-cgroups
  (is (= [{:pid 20 :ppid 1 :owner {:kind "unit" :name "sshd.service"} :service "system/sshd.service"}]
         (details/with-owners [{:pid 20 :ppid 1}] {20 "/system.slice/sshd.service"} {}))))
