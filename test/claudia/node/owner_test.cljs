(ns claudia.node.owner-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.node.owner :as owner]))

(deftest the-cgroup-path
  (is (= "/init.scope" (owner/cgroup-path "0::/init.scope\n")))
  (is (= "/system.slice/sshd.service" (owner/cgroup-path "12:pids:/x\n1:name=systemd:/system.slice/sshd.service\n")))
  (is (nil? (owner/cgroup-path ""))))

;; Real cgroup paths from a desktop running Claudia for one person, and a shared server's units.
(def ctx {:users {1000 "paul" 0 "root"} :containers {"f3751a26761f" "test-vm"}
          :keys {500 "aaaa" 501 "bbbb"}
          :chats {"aaaa" {:id "c1" :title "Fix the build" :colour "sky" :running true :profile "paul"}
                  "bbbb" {:id "c2" :title "Old" :colour "ash" :running false :profile "paul"}}})

(defn- owner-at [path & {:as more}] (owner/owner-of (merge {:pid 100 :ppid 1 :cgroup path} more) ctx))

(deftest owners-from-cgroups
  (is (= {:kind "kernel" :name "kernel"} (owner-at "/" :ppid 2)))
  (is (= {:kind "claudia" :name "front"} (owner-at "/whatever" :kind "front")))
  (is (= {:kind "container" :name "test-vm"}
         (owner-at "/system.slice/docker-f3751a26761f62a7aa0ff30444d3a270835f31209c64a4cba1cb5a64bf191946.scope")))
  (is (= {:kind "container" :name "20b817b79b6f"}
         (owner-at "/user.slice/user-1000.slice/user@1000.service/user.slice/docker-20b817b79b6f500f459009a53c61d25c426d451499ad8565be032bee66dba2c6.scope")))
  (is (= {:kind "claudia" :name "root"} (owner-at "/system.slice/claudia-root.service")))
  (is (= {:kind "claudia" :name "runner"} (owner-at "/system.slice/system-claudia\\x2drunner.slice/claudia-runner@paul.service")))
  (is (= {:kind "unit" :name "postgresql.service"} (owner-at "/system.slice/postgresql.service")))
  (is (= {:kind "unit" :name "claudia-release-builder.service"} (owner-at "/claudia_release_builder.slice/claudia-release-builder.service")))
  (is (= {:kind "unit" :name "init.scope"} (owner-at "/init.scope")))
  (is (= {:kind "user-unit" :name "agent.service@paul"}
         (owner-at "/user.slice/user-1000.slice/user@1000.service/app.slice/agent.service")))
  (is (= {:kind "session" :name "paul"}
         (owner-at "/user.slice/user-1000.slice/user@1000.service/app.slice/app-graphical.slice/app-Hyprland-vesktop-9ff609a4.scope")))
  (is (= {:kind "session" :name "paul"} (owner-at "/user.slice/user-1000.slice/session-3.scope")))
  (is (= {:kind "session" :name "1001"} (owner-at "/user.slice/user-1001.slice/user@1001.service/init.scope")))
  (is (= {:kind "other" :name "other"} (owner-at "/"))))

(deftest chats-and-leftovers-in-a-claudia-host
  (let [one "/user.slice/user-1000.slice/user@1000.service/app.slice/claudia-host.service"
        shared "/system.slice/system-claudia\\x2dhost.slice/claudia-host@ana.service"]
    (testing "the key names a running chat: that chat"
      (is (= {:kind "chat" :chat {:id "c1" :title "Fix the build" :colour "sky"} :profile "paul"} (owner-at one :pid 500))))
    (testing "the key names a chat that no longer runs: left behind"
      (is (= {:kind "leftover" :name "paul"} (owner-at one :pid 501))))
    (testing "the runners did not answer: never called left behind"
      (is (= {:kind "chat"} (owner/owner-of {:pid 501 :ppid 1 :cgroup one} (assoc ctx :chats nil)))))
    (testing "no key known: a chat, by its host's profile"
      (is (= {:kind "chat" :profile "ana"} (owner-at shared :pid 502)))
      (is (= {:kind "chat"} (owner-at one :pid 502))))
    (testing "the host itself is Claudia's"
      (is (= {:kind "claudia" :name "host"} (owner-at one :pid 503 :kind "host"))))))

(deftest which-service-and-host
  (is (= "system/postgresql.service" (owner/service-key "/system.slice/postgresql.service")))
  (is (= "user/1000/agent.service" (owner/service-key "/user.slice/user-1000.slice/user@1000.service/app.slice/agent.service")))
  (is (nil? (owner/service-key "/user.slice/user-1000.slice/session-3.scope")))
  (is (= "" (owner/host-profile "/user.slice/user-1000.slice/user@1000.service/app.slice/claudia-host.service")))
  (is (= "ana" (owner/host-profile "/system.slice/system-claudia\\x2dhost.slice/claudia-host@ana.service")))
  (is (nil? (owner/host-profile "/system.slice/claudia-front.service"))))

(deftest macos-owners-by-parents
  (is (= {:kind "kernel" :name "kernel"} (owner/parent-owner {:pid 0} {})))
  (is (= {:kind "claudia" :name "host"} (owner/parent-owner {:pid 5 :kind "host"} {})))
  (is (= {:kind "chat"} (owner/parent-owner {:pid 6} {6 "chats"})))
  (is (= {:kind "other" :name "other"} (owner/parent-owner {:pid 7} {}))))

(deftest users-and-keys
  (is (= {0 "root" 1000 "paul"} (owner/passwd-users "root:x:0:0::/root:/bin/bash\npaul:x:1000:1000::/home/paul:/bin/zsh\n# x\n")))
  (is (= "k3y" (owner/chat-key-of "PATH=/bin\u0000CLAUDIA_CHAT_KEY=k3y\u0000HOME=/h")))
  (is (nil? (owner/chat-key-of "PATH=/bin"))))

(deftest processes-grouped-by-owner
  (let [chat {:kind "chat" :chat {:id "c1"}}
        ps [{:name "claude" :cores 0.5 :rss 300 :owner chat} {:name "node" :cores 0.25 :rss 100 :owner chat}
            {:name "postgres" :cores 0 :rss 1000 :owner {:kind "unit" :name "postgresql.service"}}]]
    (is (= [{:owner {:kind "unit" :name "postgresql.service"} :count 1 :cores 0 :rss 1000 :names ["postgres"]}
            {:owner chat :count 2 :cores 0.75 :rss 400 :names ["claude" "node"]}]
           (owner/groups ps)))))
