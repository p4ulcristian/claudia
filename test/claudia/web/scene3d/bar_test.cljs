(ns claudia.web.scene3d.bar-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.scene3d.bar :as bar]))

(deftest the-stage-height
  (is (= 220 (bar/clamp-height 100 800)))
  (is (= 1200 (bar/clamp-height 5000 800)))
  (testing "a small window still allows 600"
    (is (= 600 (bar/clamp-height 900 300))))
  (is (= 440 (bar/stored-height nil 800)))
  (is (= 500 (bar/stored-height "500" 800))))

(deftest explain-asks-about-the-part
  (is (= (str "Explain the \"Pump\" in the 3D view: a blue box. What it is, what it does, how it works, "
              "and how it connects to the parts around it.")
         (bar/explain-text {:id "Pump" :title "Pump" :color "blue" :shape "box"} nil)))
  (is (= (str "Explain the \"Pump (id p1)\" in the 3D view (block b2): a red sphere, part of g, noted as \"wet\". "
              "What it is, what it does, how it works, and how it connects to the parts around it.")
         (bar/explain-text {:id "p1" :title "Pump" :color "red" :shape "sphere" :parent "g" :note "wet"} "b2"))))

(deftest the-story-track
  (is (= 100 (bar/seg-fill 0 2 0.5)))
  (is (= 50 (bar/seg-fill 2 2 0.5)))
  (is (= 0 (bar/seg-fill 3 2 0.5)))
  (is (= 12.5 (bar/tip-left [1 1 2] 0)))
  (is (= 75 (bar/tip-left [1 1 2] 2))))

(deftest the-overlap-button
  (is (= "bad" (bar/check-class ["a"] [])))
  (is (= "warn" (bar/check-class [] ["a"])))
  (is (= "ok" (bar/check-class [] [])))
  (is (= " 1 overlap" (bar/check-text ["a × b"] [] false)))
  (is (= " 2 overlaps" (bar/check-text ["a" "b"] [] false)))
  (is (= " clear now · 1 seen" (bar/check-text [] ["a"] false)))
  (is (= " no overlaps in the run" (bar/check-text [] [] true)))
  (is (= " no overlaps" (bar/check-text [] [] false))))

(deftest legend-and-popup
  (is (= "var(--green)" (bar/swatch "green")))
  (is (= "#123" (bar/swatch "#123")))
  (is (= {:left 112 :top 90} (bar/popup-at {:x 100 :y 100} 400 440)))
  (testing "kept inside the view"
    (is (= {:left 150 :top 190} (bar/popup-at {:x 390 :y 430} 400 440)))
    (is (= 4 (:top (bar/popup-at {:x 0 :y 0} 400 440))))))
