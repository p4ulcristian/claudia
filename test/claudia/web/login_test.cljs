(ns claudia.web.login-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.login :as login]))

(deftest only-the-gates-redirect-means-logged-out
  (is (login/redirected? "opaqueredirect"))
  (is (not (login/redirected? "basic")) "a same-origin answer")
  (is (not (login/redirected? "cors")))
  (is (not (login/redirected? nil))))
