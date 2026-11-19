(ns claudia.slave.github-services-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.slave.github-services :as g]))

(def ok {:name "bot" :token (str "ghs_" (apply str (repeat 36 "a"))) :login "claudia-1[bot]"})

(deftest a-good-body-passes
  (is (= ok (g/checked (assoc ok :expiresAt 1)))))

(deftest bad-bodies-are-refused
  (is (:error (g/checked (assoc ok :name "../etc"))) "no path in a name")
  (is (:error (g/checked (assoc ok :name "Bot"))))
  (is (:error (g/checked (assoc ok :token "ghu_aaaaaaaaaaaaaaaaaaaaaaaa"))) "a person's token is not a service's")
  (is (:error (g/checked (assoc ok :token "ghs_short"))))
  (is (:error (g/checked (assoc ok :token (str (:token ok) "\nx")))) "one line only")
  (is (:error (g/checked (assoc ok :login "someone"))) "a bot login")
  (is (:error (g/checked {}))))
