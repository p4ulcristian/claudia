(ns claudia.web.views.pure.system-look-test
  "\"Needs a look\" and the servers' dots (README decision 95), on plain data."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.views.pure.system-look :as look]))

(def now 100000000)
(def gib (* 1024 1024 1024))

(def calm-sample
  {:memory {:used (* 8 gib) :total (* 32 gib)}
   :disks [{:mount "/" :used 10 :avail 90 :source "/dev/nvme0n1p2"}]
   :pressure {:cpu {:some 1} :memory {:some 0} :io {:some 0.5}}})

(def calm {:sample calm-sample :details {:root true :services [] :ports [] :leftovers [] :log []}
           :labels [] :server "box" :now now})

(deftest calm-is-green
  (is (= [] (look/problems calm)))
  (is (= "green" (look/health calm)))
  (is (= "grey" (look/health (dissoc calm :sample))) "not reached")
  (is (= "grey" (look/health (assoc calm :error "HTTP 502"))) "the last ask failed")
  (is (= "green" (look/health (dissoc calm :details))) "no details yet: what the sample says"))

(deftest red-a-failed-unit-a-crash-or-an-oom-kill-in-the-last-hour
  (let [failed {:name "spellbook.service" :scope "system" :state "failed" :since (- now 60000) :restarts 3 :desc "Spellbook"}]
    (is (= "red" (look/health (assoc-in calm [:details :services] [failed]))))
    (is (= {:tone "red" :subject "spellbook.service" :text "failed" :unit {:unit "spellbook.service" :scope "system" :user nil}
            :facts ["system unit" "restarted 3 times" "Spellbook"]}
           (select-keys (first (look/failed-rows [failed])) [:tone :subject :text :unit :facts])))
    (testing "its failed event is not a second row"
      (is (= 1 (count (look/problems (-> calm (assoc-in [:details :services] [failed])
                                         (assoc-in [:details :log] [{:at (- now 1000) :kind "failed" :unit "spellbook.service"}]))))))))
  (is (= "red" (look/health (assoc-in calm [:details :log] [{:at (- now 1000) :kind "oom" :name "node" :text "killed"}]))))
  (is (= "green" (look/health (assoc-in calm [:details :log] [{:at (- now 7200000) :kind "crash" :name "node"}])))
      "two hours ago: in the Log, not on the dot")
  (is (= "green" (look/health (assoc-in calm [:details :log] [{:at (- now 1000) :kind "reboot" :text "reboot needed"}])))
      "a reboot needed is in the Log only"))

(deftest amber-unowned-ports-leftovers-caps-and-waiting
  (let [open {:proto "tcp" :port 8080 :addr "0.0.0.0" :reach "all" :pid 7 :name "python3" :owner {:kind "session" :name "paul"}}]
    (is (= "amber" (look/health (assoc-in calm [:details :ports] [open]))))
    (is (= ":8080" (:subject (first (look/port-rows [open] [] "box" true)))))
    (is (empty? (look/port-rows [(assoc open :owner {:kind "unit" :name "web.service"})] [] "box" true)) "a unit owns it")
    (is (empty? (look/port-rows [(assoc open :reach "local")] [] "box" true)) "this machine only")
    (is (empty? (look/port-rows [open] [{:server "*" :kind "port" :match "8080" :project "P"}] "box" true)) "a label names it")
    (is (empty? (look/port-rows [(dissoc open :pid :name :owner)] [] "box" false)) "without root no process says nothing")
    (is (= 1 (count (look/port-rows [(dissoc open :pid :name :owner)] [] "box" true))) "with root: nothing owns it"))
  (is (= "amber" (look/health (assoc-in calm [:details :leftovers] [{:pid 4 :name "node" :rss 1 :age 7200}]))))
  (is (= "Memory" (:subject (first (look/cap-rows (assoc-in calm-sample [:memory :used] (* 29 gib)))))))
  (is (= "/" (:subject (first (look/cap-rows (assoc calm-sample :disks [{:mount "/" :used 90 :avail 10}]))))))
  (is (= ["Programs wait for disk" "12% of the time"]
         ((juxt :subject :text) (first (look/cap-rows (assoc-in calm-sample [:pressure :io :some] 12))))))
  (is (= [] (look/cap-rows (assoc calm-sample :pressure nil))) "macOS"))

(deftest red-first-then-amber-then-proposals
  (let [feed (-> calm (assoc-in [:details :leftovers] [{:pid 4 :name "node" :rss 1}])
                 (assoc-in [:details :services] [{:name "a.service" :state "failed"}]))
        rows (look/attention feed [{:id "p1" :runner "box" :chat {:title "Spellbook visits" :colour "sky"}
                                    :kind "unit" :match "a.service" :project "Spellbook" :why "it runs the app"}])]
    (is (= ["red" "amber" "violet"] (map :tone rows)))
    (is (= "proposes the project “Spellbook” for unit a.service" (:text (last rows))))
    (is (= "red" (look/health feed)) "a proposal never colours the dot")))

(deftest ask-a-chat-puts-the-facts
  (is (= "System on Box: spellbook.service failed (system unit · restarted 3 times · since 14:02)."
         (look/ask-text "Box" {:subject "spellbook.service" :text "failed" :at-word "since"
                               :facts ["system unit" "restarted 3 times"]} "14:02")))
  (is (= "System on Box: Memory 91% used." (look/ask-text "Box" {:subject "Memory" :text "91% used" :facts []} nil))))
