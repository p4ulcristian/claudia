(ns claudia.web.views.pure.system-feed-test
  "Where System asks (README decision 96, PROTOCOL.md), on plain data."
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.system-feed :as feed]))

(def me {:admin true :servers [{:name "box" :label "Box"} {:name "mac"}]})

(deftest a-tab-per-server-then-the-gate-for-admins
  (is (= [{:name "box" :label "Box"} {:name "mac" :label "mac"} {:name "gate" :label "gate" :gate? true}]
         (feed/tabs me)))
  (is (= ["box" "mac"] (map :name (feed/tabs (dissoc me :admin)))))
  (is (= [] (feed/tabs nil))))

(deftest it-opens-on-the-open-chats-server
  (is (= "mac" (feed/opening-tab (feed/tabs me) "mac")))
  (is (= "box" (feed/opening-tab (feed/tabs me) "gone")) "a server not listed: the first")
  (is (nil? (feed/opening-tab [] "box"))))

(deftest the-hub-routes
  (is (= "/hub/servers/my%20box/system" (feed/base-url {:name "my box"})))
  (is (= "/hub/gate/system/details" (feed/details-url {:name "gate" :gate? true})))
  (is (= "/hub/servers/box/system/history?hours=24" (feed/history-url {:name "box"} 24)))
  (is (= "/hub/servers/box/system/unit-log?unit=web%40paul.service&scope=user&user=paul&n=50"
         (feed/unit-log-url {:name "box"} {:unit "web@paul.service" :scope "user" :user "paul"} 50)))
  (is (= "/hub/servers/box/system/unit-log?unit=nginx.service&scope=system&n=5"
         (feed/unit-log-url {:name "box"} {:unit "nginx.service"} 5)))
  (is (nil? (feed/unit-log-url {:name "gate" :gate? true} {:unit "x.service"} 5)) "the gate has no journal route"))

(deftest proposals-about-one-server-from-every-runner
  (let [by {"box" [{:id "2" :server "box" :at 20} {:id "3" :server "mac" :at 5}]
            "mac" [{:id "1" :at 10}]}]
    (is (= [["2" "box"]] (map (juxt :id :runner) (feed/proposals-for by "box"))))
    (is (= [["3" "box"] ["1" "mac"]] (map (juxt :id :runner) (feed/proposals-for by "mac")))
        "oldest first; one without a server is about its own runner's")
    (is (= [] (feed/proposals-for nil "box")))))
