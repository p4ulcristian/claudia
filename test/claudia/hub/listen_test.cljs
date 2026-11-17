(ns claudia.hub.listen-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.hub.listen :as listen]
            [claudia.hub.listen-words :as words]))

(deftest log-line-keeps-known-fields-only
  (is (= "mic: a@x.org {\"event\":\"start-failed\",\"steps\":{\"mic\":120},\"error\":\"nope\"}"
         (listen/log-line "a@x.org" {:event "start-failed" :steps {:mic 120 :evil "x"} :error "nope" :text "secret words"}))))

(deftest log-line-cuts-long-text
  (is (= 200 (count (re-find #"xxx+" (listen/log-line "a@x.org" {:ua (apply str (repeat 500 "x"))}))))))

(deftest heard-gives-clean-and-raw
  (is (= {:text "Hello, Dorka." :raw "hello mission"} (listen/heard #js {:text " Hello, Dorka. " :raw "hello mission "})))
  (is (= {:text "hello" :raw "hello"} (listen/heard #js {:text "hello"})) "an old service: raw is the text")
  (is (= {:text "" :raw ""} (listen/heard nil))))

(deftest checked-words-trims-dedupes-and-limits
  (is (= {:words ["Iris" "Canary 1B"]} (words/checked-words ["  Iris " "" "iris" "Canary   1B"])))
  (is (= {:words []} (words/checked-words [])))
  (is (:error (words/checked-words "Iris")))
  (is (:error (words/checked-words ["ok" 5])))
  (is (:error (words/checked-words [(apply str (repeat 61 "x"))])))
  (is (:error (words/checked-words (map str (range 501))))))

(deftest words-of-keeps-strings
  (is (= ["a" "b"] (words/words-of {:words ["a" 1 "b"]})))
  (is (= [] (words/words-of {}))))
