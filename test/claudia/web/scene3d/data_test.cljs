(ns claudia.web.scene3d.data-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.scene3d.data :as data]))

(deftest numbers-and-lists-made-safe
  (is (= [] (data/lst nil)))
  (is (= [1] (data/lst [1])))
  (is (= [1 0 3] (data/vec3 [1 "x" "3" 9])))
  (is (= [0 0 0] (data/vec3 [1 2])))
  (is (nil? (data/vec3 "no" nil)))
  (is (= [2 2 2] (data/size-of 2)))
  (is (= [1 1 1] (data/size-of nil)))
  (is (= "a.b" (data/id-str :a.b)))
  (is (= "7" (data/id-str 7)))
  (is (= 0.08 (data/radius-of {})))
  (is (= 0.08 (data/radius-of {:radius -1})))
  (is (= 0.2 (data/radius-of {:radius "0.2"}))))

(deftest a-person-is-scaled-to-their-height
  (is (= 2 (data/person-scale 3.5 1.75)))
  (is (= 1 (data/person-scale [1 3.5 1] 3.5)))
  (is (= 1 (data/person-scale nil 1.75)))
  (is (= 1 (data/person-scale -2 1.75))))

(deftest joint-references
  (is (= ["anna" "handR"] (data/joint-ref "anna.handR")))
  (is (nil? (data/joint-ref "anna")))
  (is (nil? (data/joint-ref ".x"))))

(deftest what-a-part-gets
  (is (data/outline? {:shape "box"} "diagram"))
  (is (not (data/outline? {:shape "box"} "real")))
  (is (not (data/outline? {:shape "person"} "diagram")))
  (is (not (data/outline? {:shape "box" :outline false} "diagram")))
  (is (not (data/outline? {:shape "sphere" :ghost true} "diagram")))
  (is (data/edges? {:shape "box"} "real" false))
  (is (not (data/edges? {:shape "box"} "real" true)))
  (is (not (data/edges? {:shape "rod"} "real" false)))
  (is (not (data/edges? {:shape "box" :wire true} "real" false))))

(deftest reading-the-form
  (is (= [{:label "a"}] (data/legend-of {:legend [{:label "a"} {:color "x"} "y"]})))
  (is (= "x()" (data/script-of {:script "x()"})))
  (is (nil? (data/script-of {:script "  "})))
  (is (= 4 (data/scan-seconds-of {:scan "4"} 12)))
  (is (= 12 (data/scan-seconds-of {:scan 0} 12)))
  (is (= "a × b|c × d" (data/hits-key [["a" "b"] ["c" "d"]])))
  (is (= "\"stand\"" (data/pose-key "stand")))
  (is (= ["hi" "there"] (data/voice-lines [{:say "hi"} {:say ""} {:say "there"} {:say "hi"}]))))

(deftest the-part-popup
  (is (= [["id" "r"] ["shape" "rod"] ["color" "blue"] ["size" "length 2.00"] ["at" "1.00, 2.00, 3.00"]
          ["part of" "g"] ["solid" "yes, overlapping now"]]
         (data/part-rows "r" {:shape "rod" :parent "g"} [1 2 3] 2 true true)))
  (is (= [["id" "b"] ["shape" "box"] ["color" "red"] ["size" "[1,2,3]"] ["at" "0.00, 0.00, 0.00"]
          ["solid" "no (ghost/housing)"]]
         (data/part-rows "b" {:color "red" :size [1 2 3]} [0 0 0] nil false false)))
  (is (= {:id "b" :title "Box" :x 5 :y 6 :rows [] :note nil :shape "box" :color "blue" :parent nil}
         (data/picked "b" {:label "Box"} [] 5 6))))

(deftest allowed-overlaps
  (let [pairs (data/allow-pairs [["pin" "rod*"] ["x"] "y"])]
    (is (= [["pin" "rod*"]] pairs))
    (is (data/allowed? pairs "pin" "rod1"))
    (is (data/allowed? pairs "rod1" "pin"))
    (is (not (data/allowed? pairs "pin" "cap")))
    (is (= #{[0 2]} (data/skipped-pairs ["pin" "cap" "rod2"] pairs)))))

(deftest clearance
  (is (= 0.8 (data/clearance-scale 0.1 0.02)))
  (is (= 0.5 (data/clearance-scale 0.01 0.02)))
  (is (= 1 (data/clearance-scale 0 0.02))))

(deftest a-step-reveals-parts
  (testing "a part already where it goes is just written down"
    (is (= {:k-of {"a" 1} :fades [] :jump [] :order 0}
           (data/reveal-plan ["a"] #{"a"} {} [] true 0))))
  (testing "without animation, changes jump"
    (is (= {:k-of {"a" 0} :fades [] :jump [["a" 0]] :order 0}
           (data/reveal-plan ["a"] #{} {} [] false 0))))
  (testing "animated, they fade one after another (35 ms apart), in 700 ms or out in 400"
    (is (= [{:id "a" :from 1 :to 0 :at 100 :dur 400} {:id "b" :from 0 :to 1 :at 135 :dur 700}]
           (:fades (data/reveal-plan ["a" "b"] #{"b"} {"b" 0} [] true 100)))))
  (testing "a running fade is replaced"
    (is (= [{:id "a" :from 0.5 :to 1 :at 0 :dur 700}]
           (:fades (data/reveal-plan ["a"] #{"a"} {"a" 0.5} [{:id "a" :from 0 :to 1 :at -50 :dur 700}] true 0))))))

(deftest fades-run-out
  (let [f {:from 0 :to 1 :at 100 :dur 100}]
    (is (= 0 (data/fade-value f 50)))
    (is (= 0.875 (data/fade-value f 150)))
    (is (= 1 (data/fade-value f 400)))
    (is (= [] (data/running-fades [f] 200)))
    (is (= [f] (data/running-fades [f] 150)))))

(deftest the-spotlight-and-the-glow
  (is (= {:lit true :notes true :dim 1} (data/spot true #{"a"} "a" false)))
  (is (= {:lit false :notes false :dim 0.16} (data/spot true #{"a"} "b" false)))
  (is (= {:lit false :notes true :dim 1} (data/spot nil #{} "b" true)))
  (is (= {:hex 0xff2a2a :strength 0.9 :person-strength 0.9} (data/paint true true true 0.5)))
  (is (= {:hex 0x2f6dff :strength 0.7 :person-strength 0.35} (data/paint false true false 0.5)))
  (is (= {:hex 0xffc247 :strength 0.8 :person-strength 0.8} (data/paint false false true 1)))
  (is (= {:hex 0 :strength 0.7 :person-strength 0.7} (data/paint false false false 0.5))))

(deftest the-story-clock
  (let [c {:playing true :dur 2 :elapsed 1 :last 1000 :idx 0 :n 2}]
    (is (= (assoc c :elapsed 1.5 :last 1500 :prog 0.75 :then nil) (data/tick c 1500)))
    (is (= :next (:then (data/tick c 2000))))
    (is (= :end (:then (data/tick (assoc c :idx 1) 2000))))
    (testing "paused: the clock stands"
      (is (= 1 (:elapsed (data/tick (assoc c :playing false) 5000)))))
    (testing "a step without a length yet has no progress"
      (is (nil? (:prog (data/tick (assoc c :dur 0) 1500)))))))

(deftest play-starts-over
  (is (data/restart? -1 3 0 0))
  (is (data/restart? 2 3 2 2))
  (is (not (data/restart? 2 3 2 1)))
  (is (not (data/restart? 1 3 2 2))))
