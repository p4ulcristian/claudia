(ns claudia.slave.voice-messages-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.slave.voice-messages :as vm]))

(deftest a-speak-is-kept-as-a-message
  (is (= {:chat "c" :id "t1" :voice "nora" :at 5 :text "Done."}
         (vm/message {:type "speak" :chat "c" :id "t1" :voice "nora" :text "Done."} 5)))
  (is (= {:chat "c" :id "ask-a" :voice "otto" :at 5 :ask {:questions [{:question "Which?"}]}}
         (vm/message {:chat "c" :id "ask-a" :voice "otto" :ask {:questions [{:question "Which?"}]}} 5))))

(deftest the-frame-holds-a-day
  (let [now (* 3 vm/keep-ms)
        fresh {:chat "c" :id "1" :at (- now 1000)}
        old {:chat "c" :id "0" :at (- now vm/keep-ms)}]
    (is (= {:type "voices" :messages [fresh]} (vm/frame [old fresh] now)))))
