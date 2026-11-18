(ns claudia.web.views.pure.voice-back-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.voice-back :as vb]))

(def min-ms (* 60 1000))

(deftest sounds-expire-30-minutes-after-spoken
  (is (not (vb/expired? {:at 0} (* 30 min-ms))))
  (is (vb/expired? {:at 0} (inc (* 30 min-ms)))))

(deftest by-msg-groups-sent-sounds-in-spoken-order
  (let [sounds [{:id "b" :at 20 :secs 2 :text "B" :raw "b" :msg "m1" :blob :x}
                {:id "a" :at 10 :secs 1 :text "A" :raw "a" :msg "m1" :blob :x}
                {:id "c" :at 30 :secs 3 :text "C" :raw "C" :msg nil}
                {:id "d" :at 0 :secs 4 :text "D" :raw "D" :msg "m2"}]]
    (is (= {"m1" [{:id "a" :at 10 :secs 1 :text "A" :raw "a"} {:id "b" :at 20 :secs 2 :text "B" :raw "b"}]
            "m2" [{:id "d" :at 0 :secs 4 :text "D" :raw "D"}]}
           (vb/by-msg sounds 100)))
    (is (= ["m1"] (keys (vb/by-msg sounds (+ 5 (* 30 min-ms))))) "d's time is up; c never went out")))

(deftest heard-lines-mark-the-cleanup
  (is (= [{:secs 2 :raw "ask mission" :text "Ask Mishung." :cleaned? true}
          {:secs 1 :raw "Yes." :text "Yes." :cleaned? false}]
         (vb/heard-lines [{:secs 2 :raw "ask mission" :text "Ask Mishung."} {:secs 1 :raw "Yes." :text "Yes."}])))
  (is (= 4 (vb/total-secs [{:secs 1.4} {:secs 2.4}]))))
