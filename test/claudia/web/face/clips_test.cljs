(ns claudia.web.face.clips-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.face.art :as art]
            [claudia.web.face.clips :as clips]
            [claudia.web.face.lens :as lens]
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

(deftest daytime-story
  (is (= "LUNCH" (clips/daytime-word 12)))
  (is (= "GO TO BED" (clips/daytime-word 3)))
  (is (= "ONE MORE COMMIT?" (clips/daytime-word 23))))

(deftest eyes-look
  (is (= {:cover 0 :line :lid :wide? false} (paint/eye-look :dot 0)))
  (is (= :happy (:line (paint/eye-look :happy 0))))
  (is (= :shut (:line (paint/eye-look :dot 0.9))) "a blink shuts plain eyes")
  (is (= 0.4 (:cover (paint/eye-look :narrow 0))))
  (is (:wide? (paint/eye-look :wide 0))))

(deftest glasses
  (is (not (paint/glasses-down? {:pose {:eyes :dot} :mood :idle})))
  (is (paint/glasses-down? {:pose {:eyes :happy} :mood :idle}) "eyes worth showing")
  (is (paint/glasses-down? {:pose {:eyes :dot} :mood :idle :ticker {:text "IR DONE"}}) "a chat's news")
  (is (paint/glasses-down? {:pose {:eyes :dot} :mood :idle :pointer [0.3 0.2]}) "the mouse near")
  (is (not (paint/glasses-down? {:pose {:eyes :dot} :mood :work :pointer [1 1]})) "the mouse far"))

(deftest lenses-show
  (is (= :dark (:kind (lens/show {:fx []} nil))))
  (is (= {:kind :rain :speed 1} (select-keys (lens/show {:fx [[:dots false]]} nil) [:kind :speed])))
  (is (= "HI!" (:text (lens/show {:fx [[:notes] [:word ["hi!" 0.5]]]} nil))) "words win over symbols")
  (is (= "?" (:text (lens/show {:fx [[:question 0.5]]} nil))))
  (is (= "IR DONE" (:text (lens/show {:fx [[:word ["hi" 0]]]} {:text "IR DONE" :start 5}))) "the ticker wins"))

(deftest lenses-draw
  (let [ls (art/lenses :up)]
    (doseq [shown [{:kind :rain :speed 1} {:kind :symbol :text "!"} {:kind :text :text "IR DONE" :start 0}
                   {:kind :dark}]]
      (is (re-find #"^<ellipse" (lens/markup ls (merge {:color "#3cff6a" :start 0} shown) 1500 false)) (str shown)))
    (testing "the ticker types 14 letters a second"
      (is (re-find #">IR DO<" (lens/ticker ls "IR DONE" 400))))))

(deftest painting
  (let [a (paint/attrs {:pose (clips/mood-pose :idle 0) :look [0 0] :spin 0 :mood :idle} 0 false)]
    (is (= "inline" (get-in a [:open-far :display])))
    (is (= 1 (get-in a [:up :opacity])))
    (is (= "face" (get-in a [:root :class])))
    (is (string? (get-in a [:body :transform])))
    (is (= "translate(0.00 0.00) rotate(0.00) scale(1.00 1.00) translate(0 0)" (paint/transform 0 0))))
  (testing "words on the lenses: she leans out, bigger"
    (is (= "face big" (get-in (paint/attrs {:pose {} :look [0 0] :spin 0 :ticker {:text "X" :start 0}} 0 false)
                              [:root :class])))))
