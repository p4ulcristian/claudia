(ns claudia.hub.ops-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.hub.ops :as ops]))

(deftest field-trims-and-cuts-strings-only
  (is (= "abc" (ops/field "  abc  ")))
  (is (= "ab" (ops/field " abcdef" 2)))
  (is (= "" (ops/field 42)))
  (is (= "" (ops/field nil 10)))
  (is (= 200 (count (ops/field (apply str (repeat 300 "x")))))))

(deftest admin-route-names-the-operation
  (is (= [:state] (ops/admin-route "GET" "/hub/admin")))
  (is (= [:save-server "box one"] (ops/admin-route "PUT" "/hub/admin/servers/box%20one")))
  (is (= [:drop-server "box"] (ops/admin-route "DELETE" "/hub/admin/servers/box")))
  (is (= [:add-person] (ops/admin-route "POST" "/hub/admin/people")))
  (is (= [:save-person "ann@example.com"] (ops/admin-route "PUT" "/hub/admin/people/Ann%40Example.com")))
  (is (= [:drop-person "ann@example.com"] (ops/admin-route "DELETE" "/hub/admin/people/ann@example.com")))
  (is (= [:set-access] (ops/admin-route "PUT" "/hub/admin/access")))
  (is (= [:pair] (ops/admin-route "POST" "/hub/admin/pair")))
  (testing "anything else is nothing"
    (is (nil? (ops/admin-route "POST" "/hub/admin")))
    (is (nil? (ops/admin-route "GET" "/hub/admin/servers/box")))
    (is (nil? (ops/admin-route "GET" "/hub/admin/servers/a/b"))))
  (testing "a badly encoded name throws (the call answers 500)"
    (is (thrown? js/Error (ops/admin-route "GET" "/hub/admin/servers/%E0")))))

(deftest server-entry-keeps-the-old-values-for-blank-ones
  (is (= {:name "box" :label "Old" :address "10.0.0.2:4540"}
         (ops/server-entry "box" {:label "Old" :address "10.0.0.2:4540"} {:label " " :address ""})))
  (is (= {:name "box" :label "box" :address ""} (ops/server-entry "box" nil {})))
  (is (= {:name "box" :label "New" :address "h:1"} (ops/server-entry "box" nil {:label "New" :address " h:1 "}))))

(deftest server-problem-checks-name-and-address
  (is (nil? (ops/server-problem {:name "box" :address "10.0.0.2:4540"})))
  (is (nil? (ops/server-problem {:name "box" :address "[fd00::2]:4540"})))
  (is (re-find #"server name" (ops/server-problem {:name "Box" :address "10.0.0.2:4540"})))
  (is (re-find #"Address" (ops/server-problem {:name "box" :address "10.0.0.2"})))
  (is (re-find #"Address" (ops/server-problem {:name "box" :address "h:99999"}))))

(deftest pair-names-fall-back-to-the-host
  (is (= ["my-box" "My.Box"] (ops/pair-names {:host "My.Box"} {})))
  (is (= ["given" "Given label"] (ops/pair-names {:host "h"} {:name "given" :label "Given label"}))))

(def device-key "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")

(deftest device-entry-and-problem
  (let [token (apply str (repeat 64 "a"))
        b {:token (str " " (.toUpperCase token)) :env "production" :topic "com.example.app" :key device-key}
        d (ops/device-entry b "ann@example.com")]
    (is (= {:token token :email "ann@example.com" :env "production" :topic "com.example.app"
            :key device-key :live-start nil}
           d))
    (is (nil? (ops/device-problem d)))
    (is (re-find #"^token" (ops/device-problem (assoc d :token "xyz"))))
    (is (re-find #"^env" (ops/device-problem (assoc d :env "test"))))
    (is (re-find #"^topic" (ops/device-problem (assoc d :topic "app"))))
    (is (re-find #"^key" (ops/device-problem (assoc d :key nil))))
    (is (re-find #"^liveStartToken" (ops/device-problem (assoc d :live-start "zz"))))
    (is (= (apply str (repeat 32 "b"))
           (:live-start (ops/device-entry (assoc b :liveStartToken (apply str (repeat 32 "B"))) "x"))))))

(deftest live-report-and-problem
  (let [r (ops/live-report {:device "ABC" :activity "act-1" :token (apply str (repeat 32 "F"))})]
    (is (= {:device "abc" :id "act-1" :token (apply str (repeat 32 "f"))} r))
    (is (nil? (ops/live-report-problem r)))
    (is (re-find #"^activity" (ops/live-report-problem (assoc r :id ""))))
    (is (re-find #"^token" (ops/live-report-problem (assoc r :token "abc"))))))

(deftest person-route-names-the-call
  (is (= [:register] (ops/person-route "POST" "/hub/push/devices")))
  (is (= [:drop-device "ab%20c"] (ops/person-route "DELETE" "/hub/push/devices/ab%20c")))
  (is (= [:report-live] (ops/person-route "POST" "/hub/push/live")))
  (is (= [:end-live "a1"] (ops/person-route "DELETE" "/hub/push/live/a1")))
  (is (= [:presence] (ops/person-route "POST" "/hub/presence")))
  (is (nil? (ops/person-route "GET" "/hub/push/devices")))
  (is (nil? (ops/person-route "POST" "/hub/push/live/a1"))))
