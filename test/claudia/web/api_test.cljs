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

(deftest share-of-a-download
  (is (= 50 (api/share-of 50 100)))
  (is (= 99 (api/share-of 100 100)) "100 only once the body is in")
  (is (= 99 (api/share-of 150 100)) "a compressed body runs past its length")
  (is (nil? (api/share-of 10 0))))
