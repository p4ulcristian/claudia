(ns claudia.hub.listen-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.hub.listen :as listen]))

(deftest log-line-keeps-known-fields-only
  (is (= "mic: a@x.org {\"event\":\"start-failed\",\"steps\":{\"mic\":120},\"error\":\"nope\"}"
         (listen/log-line "a@x.org" {:event "start-failed" :steps {:mic 120 :evil "x"} :error "nope" :text "secret words"}))))

(deftest log-line-cuts-long-text
  (is (= 200 (count (re-find #"xxx+" (listen/log-line "a@x.org" {:ua (apply str (repeat 500 "x"))}))))))
