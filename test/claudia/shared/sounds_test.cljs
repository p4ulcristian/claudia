(ns claudia.shared.sounds-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.shared.sounds :as sounds]))

(deftest every-sound-has-an-id-and-a-name
  (is (= ["bell" "marimba" "chime" "drop" "knock"] (map :id sounds/all)))
  (is (every? (comp seq :name) sounds/all)))

(deftest an-unknown-or-missing-sound-is-the-bell
  (is (= "marimba" (sounds/sound-id "marimba")))
  (is (= "bell" (sounds/sound-id nil)))
  (is (= "bell" (sounds/sound-id "gong")))
  (is (nil? (sounds/by-id "gong"))))

(deftest partials-above-10-khz-are-dropped
  (is (= [[1 1] [4 0.2]] (sounds/partials 440 [[1 1] [4 0.2]])))
  (is (= [[1 1]] (sounds/partials 2000 [[1 1] [10 0.04]]))))

(deftest octave-under-keeps-the-pitch-under-the-top
  (is (= 440 (sounds/octave-under 440 1000)))
  (is (= 550 (sounds/octave-under 2200 1000)))
  (is (= 0 (sounds/octave-under 0 1000))))

(deftest the-notify-pill-names-the-sound-or-off
  (is (= "Drop" (sounds/notify-name "drop")))
  (is (= "Off" (sounds/notify-name ""))))
