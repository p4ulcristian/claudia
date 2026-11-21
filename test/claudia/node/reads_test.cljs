(ns claudia.node.reads-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.node.reads :as reads]))

(deftest only-the-fixed-reads
  (is (nil? (reads/read-problem {:read "ports"})))
  (is (some? (reads/read-problem {:read "shell"})))
  (is (some? (reads/read-problem nil)))
  (is (some? (reads/read-problem "ports"))))

(deftest unit-names-users-and-counts-are-checked
  (is (nil? (reads/read-problem {:read "unit-log" :unit "claudia-runner@paul.service" :scope "system" :n 50})))
  (is (nil? (reads/read-problem {:read "unit-log" :unit "worker.service" :scope "user" :user "paul" :n 1})))
  (testing "refused"
    (doseq [bad [{:unit "x.service; rm -rf /" :scope "system" :n 5}
                 {:unit "--all.service" :scope "system" :n 5}
                 {:unit "sshd" :scope "system" :n 5}
                 {:unit "sshd.service" :scope "machine" :n 5}
                 {:unit "worker.service" :scope "user" :user "../root" :n 5}
                 {:unit "worker.service" :scope "user" :n 5}
                 {:unit "sshd.service" :scope "system" :n 0}
                 {:unit "sshd.service" :scope "system" :n 201}
                 {:unit "sshd.service" :scope "system" :n 2.5}]]
      (is (some? (reads/read-problem (assoc bad :read "unit-log"))) (pr-str bad)))))

(deftest the-commands
  (is (= ["journalctl" "--unit=sshd.service" "-n" "20" "-o" "json" "--no-pager" "--output-fields=MESSAGE,PRIORITY"]
         (reads/unit-log-argv {:unit "sshd.service" :scope "system" :n 20} nil)))
  (is (= ["journalctl" "--user-unit=worker.service" "_UID=1000" "-n" "5" "-o" "json" "--no-pager" "--output-fields=MESSAGE,PRIORITY"]
         (reads/unit-log-argv {:unit "worker.service" :scope "user" :user "paul" :n 5} 1000)))
  (is (= ["systemctl" "--user" "-M" "paul@" "show" "-p" "Id" "--" "a.service"] (reads/show-argv ["a.service"] "paul" "Id")))
  (is (= ["systemctl" "--user" "show" "-p" "Id" "--" "a.service"] (reads/show-argv ["a.service"] :own "Id")))
  (is (= ["systemctl" "show" "-p" "Id" "--" "a.service"] (reads/show-argv ["a.service"] nil "Id")))
  (is (= "MESSAGE_ID=d989611b15e44c9dbf31e3c81256e4ed" (nth reads/journal-argv 6))))

(deftest each-kind-logged-once-in-ten-minutes
  (is (reads/log-read? {} "ports" 1000))
  (is (not (reads/log-read? {"ports" 1000} "ports" 2000)))
  (is (reads/log-read? {"ports" 1000} "ports" (+ 1001 reads/log-every-ms)))
  (is (reads/log-read? {"unit-log" 1000} "unit-log" 1001))
  (is (= {:kind "read" :read "unit-log" :unit "a.service" :scope "system" :user nil :n 5}
         (reads/read-entry {:read "unit-log" :unit "a.service" :scope "system" :n 5}))))
