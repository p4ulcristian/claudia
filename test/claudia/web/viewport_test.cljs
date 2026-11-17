(ns claudia.web.viewport-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.viewport :as viewport]))

(def ^:private box
  {:overflow-x "visible" :overflow-y "auto"
   :scroll-top 0 :scroll-left 0 :scroll-width 100 :scroll-height 500
   :client-width 100 :client-height 200})

(deftest scrolls-by-only-where-there-is-room
  (testing "a vertical move"
    (is (true? (viewport/scrolls-by? box 0 10)))
    (is (false? (viewport/scrolls-by? box 0 -10)))
    (is (true? (viewport/scrolls-by? (assoc box :scroll-top 50) 0 -10)))
    (is (false? (viewport/scrolls-by? (assoc box :scroll-top 300) 0 10)))
    (is (false? (viewport/scrolls-by? (assoc box :overflow-y "hidden") 0 10))))
  (testing "a sideways move"
    (is (false? (viewport/scrolls-by? box 10 0)))
    (is (true? (viewport/scrolls-by? (assoc box :overflow-x "scroll" :scroll-width 300) 10 0)))
    (is (false? (viewport/scrolls-by? (assoc box :overflow-x "scroll" :scroll-width 300) -10 0)))))

(deftest a-selecting-finger-is-never-held
  (is (viewport/selecting? 0 50 true) "a selection is held: its handles move freely")
  (is (viewport/selecting? 0 400 false) "a long press: iOS picks and widens a selection")
  (is (not (viewport/selecting? 0 50 false)) "a quick move with no selection may still be held"))
