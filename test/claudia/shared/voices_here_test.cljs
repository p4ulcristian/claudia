(ns claudia.shared.voices-here-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.shared.order :as order]
            [claudia.shared.protocol :as p]
            [claudia.shared.voices :as voices]
            [claudia.web.here :as here]))

(deftest voices-are-ids-or-off
  (is (= "nora" (voices/voice-id "nora")))
  (is (= "" (voices/voice-id "nobody")))
  (is (= "" (voices/voice-id nil)))
  (is (= voices/default-id (:id (first voices/all))))
  (is (apply distinct? (map :id voices/all))))

(deftest the-page-touched-last-speaks
  (let [pages [{:n 1 :at 100} {:n 2 :at 300} {:n 3 :at 0}]]
    (is (= 2 (:n (p/last-touched pages :at))))
    (is (nil? (p/last-touched [{:at 0}] :at)) "never touched: none")
    (is (nil? (p/last-touched [] :at)))))

(deftest here-frames-say-when
  (is (= 900 (p/touched-at 1000 100)))
  (is (= 0 (p/touched-at 1000 -5)) "from the future: never wins")
  (is (= 0 (p/touched-at 1000 "x")))
  (is (= 0 (p/touched-at 1000 nil)))
  (is (= {:type "here" :ago 50} (here/frame-at 950 1000)))
  (is (nil? (here/frame-at 0 1000)) "never touched")
  (is (nil? (here/frame-at 1 (* 2 24 60 60 1000))) "too long ago"))

(deftest group-picks-keep-the-voice
  (is (= {:voice "otto" :bell false} (order/clean-picks {:voice "otto" :bell false :x 1})))
  (is (= {:voice "nora"} (order/clean-picks {:talk true})) "a speaker on before voices: the first voice")
  (is (= {:voice ""} (order/clean-picks {:talk true :voice ""})))
  (is (= {} (order/clean-picks nil))))
