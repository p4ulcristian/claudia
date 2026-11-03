(ns claudia.web.api-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.api :as api]))

(deftest url-leaves-out-nil-values
  (is (= "/s/a%20b/api/files?path=x%2Fy" (api/url "a b" "files" {:path "x/y" :fp nil})))
  (is (= "/s/a/api/me" (api/url "a" "me" {}))))

(deftest call-opts-send-json-only-with-a-body
  (is (= {:method "GET" :headers {"accept" "application/json"}} (api/call-opts "GET" nil)))
  (is (= {:method "PUT" :headers {"accept" "application/json" "content-type" "application/json"}
          :body "{\"a\":1}"}
         (api/call-opts "PUT" {:a 1}))))
