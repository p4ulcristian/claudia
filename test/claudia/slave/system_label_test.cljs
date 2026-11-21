(ns claudia.slave.system-label-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.node.crypto :as crypto]
            [claudia.slave.system-front :as system-front]
            [claudia.slave.system-label :as system-label]))

(deftest a-proposal-from-the-tool
  (is (= {:chat "c1" :server "home" :kind "port" :match "5432" :project "Shop" :why "its compose file" :at 7}
         (system-label/proposal {:kind "port" :match "5432" :project "Shop" :why "its compose file"} "c1" "home" 7)))
  (testing "a runner that does not know its server's name keeps \"\": the page reads it as the server it asked"
    (is (= "" (:server (system-label/proposal {:kind "unit" :match "a.service" :project "p" :why ""} "c1" "" 7)))))
  (is (= "*" (:server (system-label/proposal {:server "*" :kind "unit" :match "a.service" :project "p" :why ""} "c1" "home" 7))))
  (doseq [bad [{:kind "file" :match "a" :project "p"} {:kind "unit" :match "" :project "p"}
               {:kind "unit" :match "a" :project ""} {:kind "unit" :match (apply str (repeat 121 "a")) :project "p"}]]
    (is (:error (system-label/proposal bad "c1" "home" 7)) (pr-str bad)))
  (is (= "Proposed: unit a.service on this server belongs to p. The user accepts it or says no on System."
         (system-label/proposed-text {:server "" :kind "unit" :match "a.service" :project "p"}))))

(deftest proposals-for-the-page
  (is (= [{:id 1 :server "" :kind "unit" :match "a" :project "p" :why "w" :at 3 :chat {:id "c1" :title "T" :colour "sky"}}
          {:id 2 :server "" :kind "unit" :match "b" :project "p" :why "w" :at 4 :chat {:id "gone" :title "" :colour nil}}]
         (system-label/listing [{:id 1 :chat "c1" :server "" :kind "unit" :match "a" :project "p" :why "w" :at 3}
                                {:id 2 :chat "gone" :server "" :kind "unit" :match "b" :project "p" :why "w" :at 4}]
                               {"c1" {:id "c1" :title "T" :colour "sky"}}))))

(deftest chats-by-their-keys-hash
  (let [h (crypto/token-hash "key-1")]
    (is (= {h {:id "c1" :title "T" :colour "sky" :running true}}
           (system-label/chats-by-hash [{:id "c1" :title "T" :color "sky" :chat-key "key-1"}
                                        {:id "c2" :title "U" :color "ash" :chat-key "key-2"}
                                        {:id "c3" :title "V" :color "ash" :chat-key nil}]
                                       [h "nothing"] #{"c1"})))))

(deftest the-fronts-side
  (is (= {"aaaa" {:id "c1" :running false :profile "paul"}}
         (system-front/chats-answer {:chats {:aaaa {:id "c1" :running false} :bad 3}} "paul")))
  (is (= {:read "unit-log" :unit "a.service" :scope "system" :n 50} (system-front/unit-log-ask {:unit "a.service"})))
  (is (= {:read "unit-log" :unit "a.service" :scope "user" :user "paul" :n 5}
         (system-front/unit-log-ask {:unit "a.service" :scope "user" :user "paul" :n "5"}))))
