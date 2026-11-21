(ns claudia.web.views.pure.stretch-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.stretch :as s]))

(deftest apple-devices-bounce-by-themselves
  (is (s/native-bounce? "iPhone" "Mozilla/5.0 (iPhone; CPU iPhone OS 26_0)" 5))
  (is (s/native-bounce? "MacIntel" "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)" 0))
  (is (s/native-bounce? "MacIntel" "Mozilla/5.0 (Macintosh)" 5) "an iPad asking for the desktop site")
  (is (not (s/native-bounce? "Linux x86_64" "Mozilla/5.0 (X11; Linux x86_64) Chrome/140" 0)))
  (is (not (s/native-bounce? "Win32" "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" 10)))
  (is (not (s/native-bounce? "Linux armv8l" "Mozilla/5.0 (Linux; Android 15) Chrome/140" 5))))

(deftest ends-from-the-scroll-numbers
  (is (= #{:top} (s/ends {:top 0 :height 2000 :client 500})))
  (is (= #{:bottom} (s/ends {:top 1500 :height 2000 :client 500})))
  (is (= #{:bottom} (s/ends {:top 1499.5 :height 2000 :client 500})) "a fraction of a pixel short counts")
  (is (= #{} (s/ends {:top 700 :height 2000 :client 500})))
  (is (= #{:top :bottom} (s/ends {:top 0 :height 300 :client 500})) "a short column stands at both"))

(deftest a-push-past-an-end-adds-to-the-pull
  (is (= 30 (s/next-pull 0 -30 #{:top})) "up at the top: the messages move down")
  (is (= 50 (s/next-pull 30 -20 #{:top})))
  (is (= -40 (s/next-pull 0 40 #{:bottom})) "down at the bottom: the messages move up")
  (is (= 0 (s/next-pull 0 40 #{:top})) "down at the top just scrolls")
  (is (= 0 (s/next-pull 0 -40 #{})) "no end, no pull"))

(deftest a-scroll-back-takes-from-the-pull-never-past-zero
  (is (= 10 (s/next-pull 30 20 #{:top})))
  (is (= 0 (s/next-pull 30 50 #{:top})))
  (is (= -20 (s/next-pull -50 -30 #{:bottom})))
  (is (= 0 (s/next-pull -50 -80 #{:bottom})))
  (is (= 10 (s/next-pull 30 20 #{:top :bottom})) "a short column: back from the top, not a new pull at the bottom"))

(deftest the-band-follows-first-then-gives-less-and-never-reaches-the-limit
  (is (= 0 (s/offset 0)))
  (is (< 9 (s/offset 20) 20) "close to the push at first")
  (is (< (s/offset 200) (s/offset 400) s/limit-px))
  (is (< (s/offset 100000) s/limit-px))
  (is (= (- (s/offset 80)) (s/offset -80)) "the bottom mirrors the top"))

(deftest wheel-steps-in-pixels
  (is (= 53 (s/wheel-px 53 0 600)))
  (is (= 48 (s/wheel-px 3 1 600)) "lines")
  (is (= -600 (s/wheel-px -1 2 600)) "pages"))

(deftest an-inner-box-takes-a-step-it-can-still-scroll
  (is (s/can-scroll? {:top 100 :height 1000 :client 300} -10))
  (is (s/can-scroll? {:top 0 :height 1000 :client 300} 10))
  (is (not (s/can-scroll? {:top 0 :height 1000 :client 300} -10)) "at its top, up is the column's")
  (is (not (s/can-scroll? {:top 700 :height 1000 :client 300} 10)) "at its bottom, down is the column's")
  (is (not (s/can-scroll? {:top 0 :height 300 :client 300} 10)) "not taller than its window"))
