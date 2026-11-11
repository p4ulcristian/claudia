(ns claudia.web.world.plan-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.world.plan :as plan]))

(def servers [{:name "home" :label "Home"} {:name "work" :label "Work"} {:name "mac"}])

(def rows
  [{:server "home" :id "a" :title "A" :color "violet" :running true :project {:root "/w/ir" :name "ir"} :started 1}
   {:server "home" :id "b" :title "B" :color "blue" :waiting true :project {:root "/w/ir" :name "ir"} :started 2}
   {:server "home" :id "c" :title "C" :color "green" :unseen true :started 3}
   {:server "work" :id "d" :title "D" :color "ash" :started 4}
   {:server "work" :id "e" :title "E" :color "red" :archived true :started 5}
   {:server "mac" :id "f" :title "F" :color "amber" :off true :project {:root "/m/x" :name "x"} :started 6}])

(defn- dist [[ax _ az] [bx _ bz]] (js/Math.hypot (- ax bx) (- az bz)))

(deftest hash-is-stable-and-in-range
  (is (= (plan/hash01 "home") (plan/hash01 "home")))
  (is (not= (plan/hash01 "home") (plan/hash01 "work")))
  (is (every? #(<= 0 % 0.9999999) (map plan/hash01 ["" "a" "home" "/w/ir"]))))

(deftest robot-states
  (is (= [:working :waiting :done :off :idle]
         (map plan/robot-state [{:running true} {:waiting true :running true} {:unseen true}
                                {:off true :running true} {}])))
  (testing "agents still working count as working"
    (is (= :working (plan/robot-state {:runningFor 1200})))))

(deftest archived-and-ash-chats-get-no-robot
  (is (= ["a" "b" "c" "f"] (map :id (plan/shown-rows rows)))))

(deftest islands-ring-the-gate-without-touching
  (let [isl (:islands (plan/world servers rows))
        [gate & ss] isl]
    (is (= ["gate" "home" "work" "mac"] (map :id isl)))
    (is (= [0 0 0] (:pos gate)))
    (is (= "Work" (:label (second ss))))
    (is (= "mac" (:label (last ss))) "a server without a label shows its name")
    (doseq [a isl b isl :when (not= a b)]
      (is (> (dist (:pos a) (:pos b)) (+ (:radius a) (:radius b) 3)) (str (:id a) " / " (:id b))))))

(deftest a-building-per-project-on-its-island
  (let [w (plan/world servers rows)]
    (is (= ["home|/w/ir" "mac|/m/x"] (map :id (:buildings w))))
    (is (= ["ir" "x"] (map :label (:buildings w))))
    (doseq [b (:buildings w)
            :let [isl (first (filter #(= (:island b) (:id %)) (:islands w)))]]
      (is (< (dist (:pos b) (:pos isl)) (:radius isl))))))

(deftest buildings-leave-the-bridge-side-free
  (let [a 1.0
        toward-gate (+ a js/Math.PI)]
    (doseq [i (range 5)
            :let [d (js/Math.abs (- (mod (- (plan/building-angle a i 5) toward-gate) (* 2 js/Math.PI)) 0))]]
      (is (< 1.0 d (- (* 2 js/Math.PI) 1.0))))))

(deftest a-bridge-from-the-gate-to-each-server
  (let [w (plan/world servers rows)]
    (is (= ["home" "work" "mac"] (map :to (:bridges w))))
    (doseq [b (:bridges w)]
      (is (< (dist (:from-pos b) [0 0 0]) plan/gate-radius)))))

(deftest robots-stand-at-their-places-apart
  (let [w (plan/world servers rows)
        rs (plan/robots w rows)
        by-id (into {} (map (juxt :id identity) rs))]
    (is (= ["home/a" "home/b" "home/c" "mac/f"] (map :id rs)))
    (is (= "home|/w/ir" (:building (by-id "home/a"))))
    (is (= "home" (:building (by-id "home/c"))) "no project: the island's square")
    (is (= ["home" "a"] (:key (by-id "home/a"))))
    (is (> (dist (:spot (by-id "home/a")) (:spot (by-id "home/b"))) 0.9))
    (is (= #{"home|/w/ir"} (plan/working-buildings rs)))))

(deftest spots-fill-rows-of-four
  (let [ss (map #(plan/spot [0 0 0] 0 % 6) (range 6))]
    (is (= 6 (count (distinct ss))))
    (testing "the first row is centred across the place"
      (is (< (js/Math.abs (+ (last (first ss)) (last (nth ss 3)))) 1e-9)))))

(deftest sky-blends-between-hours
  (is (= (plan/sky 0) (plan/sky 24)))
  (is (= [0x4f8fe0 0xbfe0f5] (plan/sky 10)))
  (is (not= (plan/sky 6) (plan/sky 5))))
