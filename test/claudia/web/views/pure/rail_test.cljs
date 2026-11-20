(ns claudia.web.views.pure.rail-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.views.pure.rail :as rail]))

(deftest the-rail-snaps-between-circles-and-card-widths
  (is (false? (rail/cards? 88)))
  (is (true? (rail/cards? 170)))
  (testing "let go under the threshold: back to circles"
    (is (= 88 (rail/snap 150 900))))
  (testing "past it: the nearest card width that fits"
    (is (= 260 (rail/snap 180 900)))
    (is (= 260 (rail/snap 290 900)))
    (is (= 340 (rail/snap 320 900)))
    (is (= 340 (rail/snap 600 900)))
    (is (= 260 (rail/snap 600 300))))
  (testing "no card width fits the window: circles"
    (is (= 88 (rail/snap 300 200)))))

(deftest the-window-limits-the-rail
  (is (= 640 (rail/max-width 1240)))
  (is (= 88 (rail/max-width 500)))
  (is (= 120 (rail/dragged 10 130.4 640)))
  (is (= 88 (rail/dragged 10 40 640)))
  (is (= 640 (rail/dragged 10 900 640)))
  (testing "a kept width shows as the window allows"
    (is (= 340 (rail/shown 340 1400)))
    (is (= 260 (rail/shown 340 900)))
    (is (= 88 (rail/shown 340 700)))
    (is (= 88 (rail/shown 88 1400)))))

(deftest a-kept-width-is-read-back
  (is (= 260 (rail/parse-stored "260")))
  (is (= 88 (rail/parse-stored nil)))
  (is (= 88 (rail/parse-stored "x")))
  (is (= 88 (rail/parse-stored "-4"))))

(deftest every-server-gets-its-own-mark
  (testing "first letters when they differ"
    (is (= {"destiny" "D" "box" "P" "mish" "M"}
           (rail/server-marks [{:name "destiny" :label "destiny"} {:name "box" :label "Peter's box"}
                               {:name "mish" :label "mishung"}]))))
  (testing "a shared first letter: initials, else the first letters"
    (is (= {"a" "PB" "b" "PL"}
           (rail/server-marks [{:name "a" :label "Peter's box"} {:name "b" :label "Peter laptop"}])))
    (is (= {"a" "Sm" "b" "Se"}
           (rail/server-marks [{:name "a" :label "Smoke"} {:name "b" :label "Second"}]))))
  (testing "no label: the name; the same label twice: numbered"
    (is (= {"gate" "G"} (rail/server-marks [{:name "gate"}])))
    (is (= {"a" "X1" "b" "X2"} (rail/server-marks [{:name "a" :label "x"} {:name "b" :label "x"}])))))

(deftest a-card-says-how-the-chat-stands
  (is (= :waiting (rail/card-state {:waiting true :running true})))
  (is (= :running (rail/card-state {:running true})))
  (is (= :running (rail/card-state {:agents 2})))
  (is (= :failed (rail/card-state {:ended 5 :lastTurn {:failed true}})))
  (is (= :stopped (rail/card-state {:ended 5 :lastTurn {:stopped true}})))
  (is (= :done (rail/card-state {:ended 5 :lastTurn {}})))
  (is (= :new (rail/card-state {})))
  (let [now (* 1000 60 60 24 400)]
    (is (= "4:12" (rail/card-when {:running true :runningSince (- now 252000)} :running now)))
    (is (= "3m" (rail/card-when {:askedAt (- now 180000)} :waiting now)))
    (is (= "12m" (rail/card-when {:ended (- now 720000)} :done now)))
    (is (= "2h" (rail/card-when {:at (- now 7200000)} :new now)))
    (is (nil? (rail/card-when {} :new now)))))

(deftest a-card-names-where-and-with-what
  (is (= "Smoke · claudia · Opus 5.5"
         (rail/card-meta {:server-label "Smoke" :project "claudia" :model "Opus 5.5" :multi? true})))
  (is (= "work · Opus 5.5" (rail/card-meta {:server-label "Smoke" :folder "/home/x/work/" :model "Opus 5.5"})))
  (is (= "" (rail/card-meta {})))
  (is (= "Opus" (rail/model-name [{:value "opus" :name "Opus"}] "opus" nil)))
  (is (= "Opus 5.5" (rail/model-name [] "claude-opus-5-5" nil)))
  (is (= "Sonnet 5" (rail/model-name [] nil "claude-sonnet-5")))
  (is (nil? (rail/model-name [] nil nil))))

(deftest a-group-says-its-most-pressing-chat
  (let [rows [{:ended 5} {:running true} {:waiting true}]]
    (is (= :waiting (rail/group-state rows)))
    (is (= ["asks" "? 1 ask"] (rail/group-status rows))))
  (is (= ["running" "2 running"] (rail/group-status [{:running true} {:agents 1} {}])))
  (is (= ["quiet" "2 chats"] (rail/group-status [{} {:ended 3}])))
  (is (= :failed (rail/group-state [{:ended 3 :lastTurn {:failed true}} {:ended 4}]))))
