(ns claudia.node.events-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.node.events :as events]))

;; Real journal lines (`journalctl -o json --output-fields=MESSAGE,MESSAGE_ID,UNIT,USER_UNIT`; the
;; cursor and boot fields cut), one OOM line in the shape systemd writes it, and noise.
(def journal-text
  (str "{\"__REALTIME_TIMESTAMP\":\"1794623480031058\",\"MESSAGE_ID\":\"d9b373ed55a64feb8242e02dbe79a49c\",\"MESSAGE\":\"backup.service: Failed with result 'exit-code'.\",\"USER_UNIT\":\"backup.service\"}\n"
       "{\"__REALTIME_TIMESTAMP\":\"1794623651888700\",\"MESSAGE_ID\":\"fe6faa94e7774663a0da52717891d8ef\",\"MESSAGE\":\"web-dev.service: A process of this unit has been killed by the OOM killer.\",\"UNIT\":\"web-dev.service\"}\n"
       "{\"__REALTIME_TIMESTAMP\":\"1794623651888701\",\"MESSAGE_ID\":\"0000\",\"MESSAGE\":\"other\"}\n"
       "not json\n"))

(deftest events-from-the-journal
  (is (= [{:at 1794623480031 :kind "failed" :text "backup.service: Failed with result 'exit-code'." :unit "backup.service"}
          {:at 1794623651889 :kind "oom" :text "web-dev.service: A process of this unit has been killed by the OOM killer." :unit "web-dev.service"}]
         (events/journal-events journal-text))))

;; A real `coredumpctl list --json=short` entry, its home folder replaced.
(def coredumps-text
  "[{\"time\":1794625544391928,\"pid\":3043848,\"uid\":1000,\"gid\":1000,\"sig\":11,\"corefile\":\"present\",\"exe\":\"/home/me/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome\",\"size\":5130324}]")

(deftest crashes-from-coredumpctl
  (is (= [{:at 1794625544392 :kind "crash" :name "chrome" :pid 3043848 :text "chrome crashed (signal 11)"}]
         (events/coredump-events coredumps-text)))
  (testing "coredumpctl found none (it prints nothing)"
    (is (= [] (events/coredump-events "")))))

(deftest a-reboot-is-needed-when-the-kernel-went
  (is (= {:at 5 :kind "reboot" :text "A reboot is needed: the running kernel 6.1.0-1 was replaced by an update"}
         (events/reboot-event "6.1.0-1" ["6.1.0-2"] 5)))
  (is (nil? (events/reboot-event "6.1.0-1" ["6.1.0-1" "6.1.0-2"] 5)))
  (is (nil? (events/reboot-event "6.1.0-1" [] 5))))

(deftest the-log-is-the-last-week-newest-first
  (let [now (* 30 24 3600000)]
    (is (= [3 2] (mapv :pid (events/newest [{:at (- now 1000) :pid 2} {:at (- now 10) :pid 3} {:at (- now (* 8 24 3600000)) :pid 1}] now))))))

(deftest a-units-lines
  (is (= [{:at 1795226397668 :prio 6 :text "started"}]
         (events/log-lines "{\"__REALTIME_TIMESTAMP\":\"1795226397668438\",\"PRIORITY\":\"6\",\"MESSAGE\":\"started\"}\n"))))
