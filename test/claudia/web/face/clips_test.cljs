(ns claudia.web.face.clips-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.face.art :as art]
            [claudia.web.face.clips :as clips]
            [claudia.web.face.fx :as fx]
            [claudia.web.face.paint :as paint]))

(deftest every-clip-poses-from-start-to-end
  (doseq [[id {:keys [ms prio]}] clips/clips
          now [0 (/ ms 2) ms]]
    (is (pos? ms) id)
    (is (int? prio) id)
    (is (map? (clips/clip-pose {:id id :start 0 :arg [0.5 -0.5]} now)) (str id " at " now))))

(deftest every-mood-poses
  (doseq [m [:idle :sleepy :work :busy :wait :offline :sleep]]
    (is (keyword? (:eyes (clips/mood-pose m 1234))) m)))

(deftest picking-by-weight
  (is (= :a (clips/pick-weighted {:a 1 :b 1} 0)))
  (is (= :b (clips/pick-weighted {:a 1 :b 1} 0.99)))
  (is (= :b (clips/pick-weighted {:a 1 :b 3} 0.5)))
  (testing "the idle tables name only real clips"
    (doseq [[_ weights] clips/idle-weights k (keys weights)]
      (is (contains? clips/clips k) k))))

(deftest hops-and-bumps
  (is (zero? (clips/bump 0)))
  (is (= 1 (clips/bump 0.5)))
  (is (neg? (clips/hops 0.25 1 10)))
  (is (zero? (clips/shake 1 10))))

(deftest eyes-and-mouths-draw
  (doseq [kind [:dot :wide :narrow :half :sad :happy :shut :squeeze :spiral :star :heart] side [:far :near]]
    (let [{:keys [dot line fill]} (art/eye kind side)]
      (is (or dot line fill) (str kind side))))
  (is (:filled? (art/mouth :grin)))
  (is (= (art/mouth :smile) (art/mouth :no-such))))

(deftest every-effect-draws
  (doseq [e [[:dots true] [:sweat] [:bang 0.5] [:question 0.5] [:z] [:sparkles 0.5] [:hearts 0.5] [:notes]
             [:orbit] [:cloud] [:poof 0.5] [:confetti 0.5] [:swirl 0.5] [:word ["hi!" 0.5]]]]
    (is (re-find #"^<" (fx/draw [e] 1000)) (str e)))
  (is (= "" (fx/draw [[:no-such]] 0))))

(deftest painting
  (let [a (paint/attrs {:pose (clips/mood-pose :idle 0) :look [0 0] :tilt 0 :spin 0} 0 false)]
    (is (= "inline" (get-in a [:eye-far-dot :display])))
    (is (= "none" (get-in a [:eye-far-line :display])))
    (is (string? (get-in a [:body :transform])))
    (is (= "translate(0.00 0.00) rotate(0.00) scale(1.00 1.00) translate(0 0)" (paint/transform 0 0)))))
