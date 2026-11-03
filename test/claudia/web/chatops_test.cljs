(ns claudia.web.chatops-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.chatops :as ops]))

(deftest api-url-adds-the-view
  (is (= "/s/a%20b/api/me" (ops/api-url "a b" "me")))
  (is (= "/s/a/api/sessions?view=pat" (ops/api-url "a" "sessions" "pat")))
  (is (= "/s/a/api/search?q=x&view=pat" (ops/api-url "a" "search?q=x" "pat"))))

(def ^:private db
  {:me {:servers [{:name "one"} {:name "two"}]}
   :server-me {"one" {:profiles ["pat" "sam"] :profile "sam"} "two" {:profiles ["sam"] :profile "sam"}}})

(deftest view-servers-have-the-profile
  (is (= ["one" "two"] (ops/view-servers db nil)))
  (is (= ["one"] (ops/view-servers db "pat"))))

(deftest a-stored-view-connects-once-every-server-answered
  (is (true? (ops/view-due? (assoc db :view "pat"))))
  (is (false? (ops/view-due? db)))
  (is (false? (ops/view-due? (assoc db :view "pat" :view-connected true))))
  (is (false? (ops/view-due? (-> db (assoc :view "pat") (update :server-me dissoc "two")))))
  (is (= {:db (assoc db :view "pat" :view-connected true :servers {"one" {:status :connecting :rows {}}})
          :ws/reconnect {:view "pat" :servers ["one"]}}
         (ops/connect-view (assoc db :view "pat")))))

(deftest tick-server-keeps-the-last-one-shown
  (is (= #{"a"} (ops/tick-server [] ["a" "b"] "a")))
  (is (= #{} (ops/tick-server ["a"] ["a" "b"] "a")))
  (is (= #{"a"} (ops/tick-server ["a"] ["a" "b"] "b"))))

(deftest people-are-the-other-profiles
  (is (= ["pat"] (ops/people (:server-me db)))))

(deftest search-path-for-archived-chats
  (is (= "search?q=a%20b" (ops/search-path "a b" false)))
  (is (= "search?q=a&archived=1" (ops/search-path "a" true))))

(deftest github-prompt-once
  (is (= {:github {"s" "me"}} (ops/github-known {} "s" "p" "me" false)))
  (is (= {:github {"s" false} :github-prompt {:server "s" :profile "p"}} (ops/github-known {} "s" "p" nil false)))
  (is (= {:github {"s" false}} (ops/github-known {} "s" "p" nil true)))
  (testing "one prompt at a time"
    (is (= {:server "t" :profile "q"}
           (:github-prompt (ops/github-known {:github-prompt {:server "t" :profile "q"}} "s" "p" nil false))))))

(deftest uploads
  (is (= {:new-upload-id "u"} (ops/with-upload-id {} :new "u")))
  (is (= {:new-upload-id "old"} (ops/with-upload-id {:new-upload-id "old"} :new "u")))
  (is (= {} (ops/with-upload-id {} ["s" "c"] "u")))
  (is (= "chats/c/uploads?name=a%20b.txt" (ops/upload-path "c" "a b.txt")))
  (is (= {:kind "file" :file {:path "/p"} :busy false} (ops/upload-answer 200 {:path "/p"})))
  (is (= {:error "full"} (ops/upload-answer 507 {:error "full"})))
  (is (= {:error "Upload failed (HTTP 200)"} (ops/upload-answer 200 nil))))

(deftest attached-updates-one-item
  (is (= [{:id 1 :busy true :progress 0.5} {:id 2}] (ops/attached [{:id 1 :busy true} {:id 2}] 1 {:progress 0.5})))
  (is (= [{:id 1 :busy false :kind "media"}] (ops/attached [{:id 1 :busy true}] 1 {:kind "media"})))
  (is (= [{:id 1 :busy false :error "x"}] (ops/attached [{:id 1 :busy true}] 1 {:error "x"}))))

(deftest with-older-prepends-when-it-still-fits
  (is (= {:messages [{:id 1} {:id 2}] :more false :loading-older false}
         (ops/with-older {:messages [{:id 2}] :loading-older true} [{:id 1}] false 2)))
  (is (= {:messages [{:id 3}] :loading-older false}
         (ops/with-older {:messages [{:id 3}] :loading-older true} [{:id 1}] false 2))))
