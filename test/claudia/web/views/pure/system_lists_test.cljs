(ns claudia.web.views.pure.system-lists-test
  "System's lists (README decision 95): labels, owners, ports, services, processes, on plain data."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.views.pure.system-lists :as l]))

(def labels
  [{:id 1 :server "*" :kind "process" :match "node" :project "Everywhere node"}
   {:id 2 :server "box" :kind "process" :match "node" :project "Box node"}
   {:id 3 :server "box" :kind "unit" :match "spellbook.service" :project "Spellbook"}
   {:id 4 :server "*" :kind "port" :match "8080" :project "Proxy"}
   {:id 5 :server "box" :kind "container" :match "pg" :project "IR"}])

(deftest what-a-thing-can-be-labelled-by
  (is (= {:claudia? false :unit "web.service" :container nil :port "80" :process "nginx"}
         (l/label-keys {:port 80 :name "nginx" :owner {:kind "user-unit" :name "web.service@paul"}})))
  (is (:claudia? (l/label-keys {:name "node" :owner {:kind "claudia" :name "front"}})))
  (is (= "getty@tty1.service" (l/unit-base "getty@tty1.service")) "a template's instance stays")
  (is (= {:claudia? false :unit nil :container "pg" :port nil :process "postgres"}
         (l/label-keys {:name "postgres" :owner {:kind "container" :name "pg"}}))))

(deftest labels-name-projects
  (testing "a unit beats a port beats a program; this server's beats every server's"
    (is (= "Spellbook" (:project (l/label-for labels "box" {:unit "spellbook.service" :port "8080" :process "node"}))))
    (is (= "Proxy" (:project (l/label-for labels "box" {:port "8080" :process "node"}))))
    (is (= "Box node" (:project (l/label-for labels "box" {:process "node"}))))
    (is (= "Everywhere node" (:project (l/label-for labels "mac" {:process "node"})))))
  (is (= "IR" (:project (l/label-for labels "box" {:container "pg"}))))
  (is (nil? (l/label-for labels "mac" {:container "pg"})) "another server's label")
  (is (nil? (l/label-for labels "box" {:process "python3"})) "unknown")
  (is (nil? (l/label-for nil "box" {:process "node"}))))

(deftest an-admins-label-names-the-most-telling-key
  (is (= {:kind "unit" :match "a.service"} (l/label-target {:unit "a.service" :process "node" :port "1"})))
  (is (= {:kind "process" :match "node"} (l/label-target {:process "node" :port "1"})))
  (is (= {:kind "port" :match "1"} (l/label-target {:port "1"})))
  (is (nil? (l/label-target {}))))

(deftest owners-in-words
  (is (= "chat “Spellbook visits”" (l/owner-text {:kind "chat" :chat {:title "Spellbook visits"}})))
  (is (= "a chat of paul" (l/owner-text {:kind "chat" :profile "paul"})))
  (is (= "Claudia's runner" (l/owner-text {:kind "claudia" :name "runner"})))
  (is (= "nginx.service" (l/owner-text {:kind "unit" :name "nginx.service"})))
  (is (= "container pg" (l/owner-text {:kind "container" :name "pg"})))
  (is (= "paul's login" (l/owner-text {:kind "session" :name "paul"})))
  (is (nil? (l/owner-text {:kind "other"})))
  (is (nil? (l/owner-text nil)))
  (is (l/owned? {:kind "user-unit"}))
  (is (not (l/owned? {:kind "session"})))
  (is (not (l/owned? nil))))

(deftest numbers-in-words
  (is (= ["0 cores" "<0.1 cores" "1.0 core" "3.7 cores" "12 cores"] (map l/cores-text [0 0.01 1.02 3.68 12.3])))
  (is (= "0 cores" (l/cores-text nil)))
  (is (= ["1 minute old" "12 minutes old" "1 hour old" "3 days old"] (map l/old-text [5 720 3700 (* 3 86400)]))))

(deftest ports-one-row-per-listener-widest-reach
  (let [ports [{:proto "tcp" :port 8080 :addr "127.0.0.1" :reach "local" :pid 9}
               {:proto "tcp" :port 8080 :addr "::" :reach "all" :pid 9}
               {:proto "udp" :port 53 :addr "100.64.0.1" :reach "headscale" :pid 3}
               {:proto "tcp" :port 22 :addr "192.168.0.2" :reach "lan" :pid 1}]
        rows (l/port-rows ports)]
    (is (= [[22 "lan"] [53 "headscale"] [8080 "all"]] (map (juxt :port :reach) rows)))
    (is (= ["127.0.0.1" "::"] (:addrs (last rows))))
    (is (= [8080] (l/ports-of ports 9)))
    (is (= [] (l/ports-of ports nil))))
  (is (= ["all addresses" "LAN" "Headscale" "this machine"] (map l/reach-text ["all" "lan" "headscale" "local"]))))

(def services
  [{:name "claudia-front.service" :origin "claudia" :state "active"}
   {:name "spellbook.service" :origin "admin" :state "failed"}
   {:name "mystery.service" :origin "admin" :state "active"}
   {:name "container-pg.service" :origin "container" :state "active"}
   {:name "sshd.service" :origin "os" :state "active"}
   {:name "nginx.service" :origin "os" :state "activating"}])

(deftest services-grouped
  (let [gs (l/service-groups services labels "box")]
    (is (= ["Claudia" "Spellbook" "No project" "Containers" "The system's own"] (map :title gs)))
    (is (= [false false false false true] (map :folded? gs)) "only the system's own is folded")
    (is (= [:grouped :grouped :full :quiet :quiet] (map :cell gs)) "only No project's rows say unknown")
    (is (= ["nginx.service" "sshd.service"] (map :name (:services (last gs)))) "by name"))
  (is (= "1 running · 1 failed · 1 other" (l/group-counts (take-last 3 (assoc-in services [3 :state] "failed")))))
  (is (= "0 running" (l/group-counts []))))

(deftest a-services-dot-and-time
  (is (= ["green" "red" "amber" "grey"] (map l/service-tone ["active" "failed" "reloading" "inactive"])))
  (is (= "failed" (l/service-up-text {:state "failed" :since 0} 1000)))
  (is (= "2 h 0 m" (l/service-up-text {:state "active" :since 0} 7200000)))
  (is (= "" (l/service-up-text {:state "active"} 1000))))

(deftest processes-grouped-by-owner
  (let [gs [{:owner {:kind "unit" :name "a"} :count 2 :rss 10}
            {:owner {:kind "chat"} :count 5 :rss 5}
            {:owner {:kind "chat"} :count 1 :rss 50}
            {:owner {:kind "leftover"} :count 1 :rss 1}
            {:owner {:kind "kernel"} :count 90 :rss 0}]
        sections (l/process-groups gs)]
    (is (= ["chats" "services" "rest"] (map :id sections)) "leftovers have a section of their own")
    (is (= [50 5] (map :rss (:groups (first sections)))) "most memory first")
    (is (= 99 (l/process-count gs)))))

(deftest the-tabs-counts
  (is (nil? (l/tab-counts nil)))
  (is (= {"ports" 1 "services" 2 "processes" 3 "log" 0}
         (l/tab-counts {:ports [{:proto "tcp" :port 1 :pid 1 :reach "all"} {:proto "tcp" :port 1 :pid 1 :reach "local"}]
                        :services [{} {}] :groups [{:count 3}] :log []}))))
