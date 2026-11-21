(ns claudia.web.move-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.move :as move]))

(def ^:private servers [{:name "home" :label "Home"} {:name "work" :label "Work"} {:name "mac" :label "Mac"}])

(deftest other-servers-leave-out-the-chats-own
  (is (= ["work" "mac"] (map :name (move/other-servers servers "home"))))
  (is (= [] (move/other-servers [{:name "home"}] "home"))))

(deftest ask-state-starts-on-the-first-other-server-and-the-same-folder
  (is (= {:k ["home" "c1"] :to "work" :folder "~/Work/app"}
         (move/ask-state ["home" "c1"] {:folder "/home/paul-claudia-agent/Work/app"} servers nil)))
  (is (= "" (:folder (move/ask-state ["home" "c1"] {:folder nil} servers nil))) "no folder: the target's work folder"))

(deftest ask-state-takes-the-server-the-header-picked
  (is (= "mac" (:to (move/ask-state ["home" "c1"] {} servers "mac"))))
  (is (= "work" (:to (move/ask-state ["home" "c1"] {} servers "home"))) "its own server: the first other")
  (is (= "work" (:to (move/ask-state ["home" "c1"] {} servers "gone")))))

(deftest request-body-names-the-chat-both-servers-and-the-folder
  (is (= {:chat "c1" :from "home" :to "mac" :folder "~/x"}
         (move/request-body {:k ["home" "c1"] :to "mac" :folder "~/x" :busy false}))))
