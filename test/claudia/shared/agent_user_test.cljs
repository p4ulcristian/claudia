(ns claudia.shared.agent-user-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.shared.agent-user :as agent-user]))

(deftest an-agent-user-is-a-profile-and-the-suffix
  (is (= "paul" (agent-user/profile-of "paul-claudia-agent")))
  (is (= "a-b2" (agent-user/profile-of "a-b2-claudia-agent")))
  (is (agent-user/agent-user? "peter-claudia-agent"))
  (is (= "paul-claudia-agent" (agent-user/of-profile "paul"))))

(deftest anything-else-is-not
  (is (nil? (agent-user/profile-of "paul")))
  (is (nil? (agent-user/profile-of "-claudia-agent")))
  (is (nil? (agent-user/profile-of "root")))
  (is (nil? (agent-user/profile-of "Paul-claudia-agent")))
  (is (nil? (agent-user/profile-of "paul-claudia-agent2")))
  (is (nil? (agent-user/profile-of "../x-claudia-agent")))
  (is (nil? (agent-user/profile-of nil)))
  (is (not (agent-user/agent-user? "claudia-paul"))))

(deftest a-profile-name-fits-a-linux-user-name
  (is (agent-user/profile-name? "paul"))
  (is (agent-user/profile-name? (apply str (repeat 18 "a"))))
  (is (= 32 (count (agent-user/of-profile (apply str (repeat 18 "a"))))))
  (is (not (agent-user/profile-name? (apply str (repeat 19 "a")))))
  (is (not (agent-user/profile-name? "1paul")))
  (is (not (agent-user/profile-name? "paul-")))
  (is (not (agent-user/profile-name? "pa ul")))
  (is (not (agent-user/profile-name? ""))))
