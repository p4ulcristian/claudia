(ns claudia.hub.apns-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.hub.apns :as apns]))

(deftest short-token-shows-the-start
  (is (= "abcdef…" (apns/short-token "abcdef0123")))
  (is (= "ab…" (apns/short-token "ab")))
  (is (= "…" (apns/short-token nil))))

(deftest reason-of-reads-the-body
  (is (= "BadDeviceToken" (apns/reason-of "{\"reason\":\"BadDeviceToken\"}")))
  (is (= "" (apns/reason-of "{}")))
  (is (= "" (apns/reason-of ""))))

(deftest answer-of-sorts-apples-answers
  (is (= {:status :ok} (apns/answer-of 200 "")))
  (is (= {:status :gone :detail "410 Unregistered"} (apns/answer-of 410 "Unregistered")))
  (is (= {:status :gone :detail "400 BadDeviceToken"} (apns/answer-of 400 "BadDeviceToken")))
  (is (= {:status :retry :detail "403 ExpiredProviderToken" :renew true} (apns/answer-of 403 "ExpiredProviderToken")))
  (is (= {:status :failed :detail "403 InvalidProviderToken" :renew true} (apns/answer-of 403 "InvalidProviderToken")))
  (is (= {:status :retry :detail "429 TooManyRequests"} (apns/answer-of 429 "TooManyRequests")))
  (is (= {:status :retry :detail "503"} (apns/answer-of 503 "")))
  (is (= {:status :retry :detail "0"} (apns/answer-of 0 "")))
  (is (= {:status :failed :detail "400 BadTopic"} (apns/answer-of 400 "BadTopic"))))
