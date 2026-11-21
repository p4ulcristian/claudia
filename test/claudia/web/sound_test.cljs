(ns claudia.web.sound-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.sound :as sound]))

(deftest a-new-sound-stops-the-one-before
  (let [stopped (atom [])]
    (sound/claim! :a #(swap! stopped conj :a))
    (sound/claim! :a #(swap! stopped conj :a))
    (is (= [] @stopped) "the same player playing on stops nothing")
    (sound/claim! :b #(swap! stopped conj :b))
    (is (= [:a] @stopped) "another player stops it")
    (sound/release! :a)
    (sound/claim! :c #(swap! stopped conj :c))
    (is (= [:a :b] @stopped) "releasing someone else's id changes nothing")
    (sound/release! :c)
    (sound/claim! :d #(swap! stopped conj :d))
    (is (= [:a :b] @stopped) "a released sound is not stopped again")
    (sound/release! :d)))

(deftest a-stopped-context-needs-resume
  (is (sound/stopped-state? "suspended") "made before a touch, or paused")
  (is (sound/stopped-state? "interrupted") "iOS took the sound away (WebKit's own state)")
  (is (not (sound/stopped-state? "running")))
  (is (not (sound/stopped-state? "closed"))))
