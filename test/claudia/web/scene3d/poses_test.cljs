(ns claudia.web.scene3d.poses-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.scene3d.poses :as poses]))

;; A rig with round numbers (left side).
(def ^:private rig
  {:pelvis [0 1 0] :neck [0 1.5 0] :head [0 1.6 0] :shoulder [0.2 1.45 0] :elbow [0.2 1.15 0]
   :wrist [0.2 0.85 0] :hip [0.1 0.95 0] :knee [0.1 0.5 0] :ankle [0.1 0.08 0] :ball [0.1 0.02 0.12]
   :top 1.75 :arm 0.6 :upper 0.3 :fore 0.3 :thigh 0.45 :shin 0.42})

(deftest every-named-pose-is-whole
  (is (= 14 (count poses/names)))
  (doseq [n poses/names]
    (let [p (poses/pose-of n rig)]
      (is (every? #(vector? (get p %)) [:hips :chest :handL :handR :footL :footR]) n))))

(deftest poses-are-mirrored-left-to-right
  (let [p (poses/pose-of "stand" rig)]
    (is (= (poses/mirror (:handL p)) (:handR p)))
    (is (= [-1 2 3] (poses/mirror [1 2 3])))))

(deftest a-scene-overrides-a-base
  (let [p (poses/pose-of {:base "sit" :handL "cup" :hips [0 "0.6" 0] :twist "20" :footL "x"} rig)]
    (is (= "cup" (:handL p)))
    (is (= [0 0.6 0] (:hips p)))
    (is (= 20 (:twist p)))
    (is (= (:kneeL (poses/pose-of "sit" rig)) (:kneeL p))))
  (testing "an unknown name or no pose stands"
    (is (= (poses/pose-of "stand" rig) (poses/pose-of "dance" rig) (poses/pose-of nil rig)))))

(deftest reaching-for-parts
  (is (poses/refs? {:handR "cup"} rig))
  (is (not (poses/refs? "wave" rig))))

(deftest chest-and-toes
  (is (= [0.1 1.1 0.2] (poses/chest-over [0.1 0.6 0.2] rig)))
  (testing "toes as given, else flat ahead of a foot on the floor"
    (is (= [1 1 1] (poses/toes [1 1 1] [0 0 0] rig)))
    (is (= [0.1 0.03 0.12] (map #(/ (js/Math.round (* % 1000)) 1000) (poses/toes nil [0.1 0.09 0] rig))))
    (is (nil? (poses/toes nil [0.1 0.5 0] rig)))))

(deftest blending-poses
  (is (= {:a 5 :v [1 1 1] :s "b"} (poses/blend {:a 0 :v [0 0 0] :s "a"} {:a 10 :v [2 2 2] :s "b"} 0.5)))
  (is (= "a" (:s (poses/blend {:s "a"} {:s "b"} 0.4)))))

(deftest a-new-pose-glides
  (let [st (poses/start-move {:cur {:a 0}} "wave" 2 1000)]
    (is (= {:cur {:a 0} :spec "wave" :from {:a 0} :t0 1000 :dur 2000 :dirty true} st))
    (testing "halfway there (eased), still gliding"
      (let [g (poses/glide st {:a 10} 2000)]
        (is (= {:a 5} (:cur g)))
        (is (= {:a 0} (:from g)))
        (is (false? (:dirty g)))))
    (testing "at the end it is the goal"
      (let [g (poses/glide st {:a 10} 3000)]
        (is (= {:a 10} (:cur g)))
        (is (nil? (:from g))))))
  (testing "at once with 0 seconds, or before the first pose"
    (is (nil? (:from (poses/start-move {:cur {:a 0}} "t" 0 0))))
    (is (nil? (:from (poses/start-move {} "t" 2 0))))))
