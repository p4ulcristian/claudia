(ns claudia.slave.stall-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.slave.stall :as stall]))

(def ^:private m 60000)

(deftest a-sign-is-a-step-or-a-shells-output
  (is (= 5 (stall/sign {:shell false :active 5 :start 1})))
  (is (= 7 (stall/sign {:shell true :heard 7 :active 9 :start 1})) "a shell: its output, not progress")
  (is (= 1 (stall/sign {:shell true :start 1})) "no output yet: its start")
  (is (= 9 (stall/quiet-since {:active 2 :start 1} [{:shell true :heard 9} {:active 4}]))))

(deftest an-idle-agent-whose-work-is-quiet-stalls
  (let [agent {:status "running" :shell false :start 0 :active (* 1 m)}
        silent-shell {:status "running" :shell true :start (* 1 m)}]
    (is (= (* 1 m) (stall/stalled-since agent [silent-shell] (* 13 m)))
        "2026-11-21: the agent waits, its shell runs but prints nothing")
    (is (nil? (stall/stalled-since agent [silent-shell] (* 12 m))) "under the quiet time")
    (is (= (* 1 m) (stall/stalled-since agent [] (* 13 m))) "nothing of its own runs")
    (is (nil? (stall/stalled-since agent [(assoc silent-shell :heard (* 10 m))] (* 13 m))) "its shell printed lately")
    (is (nil? (stall/stalled-since (assoc agent :active (* 11 m)) [] (* 13 m))) "it took a step lately")
    (is (nil? (stall/stalled-since (assoc agent :status "completed") [] (* 60 m))))
    (is (nil? (stall/stalled-since silent-shell [] (* 60 m))) "a shell alone never stalls")))

(deftest the-note-names-the-agent-and-its-work
  (is (= (str "The agent \"Build PR\" has shown no sign of work for 16 min: no step of its own, and its shell"
              " `ssh box 'while pgrep -f x'` still runs but has printed nothing. Check that its work still runs"
              " (a process alive, a log growing); if it does not, stop the agent or tell it what happened.")
         (stall/note-text "Build PR" [{:shell true :name "ssh box 'while pgrep -f x'"}] 16)))
  (is (re-find #"and nothing of its own still runs\." (stall/note-text "A" [] 12)))
  (is (re-find #"its agent \"Tests\" has taken no step" (stall/note-text "A" [{:shell false :name "Tests"}] 12)))
  (is (= 16 (stall/minutes (* 16.9 m)))))
