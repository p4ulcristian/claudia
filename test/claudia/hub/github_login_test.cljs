(ns claudia.hub.github-login-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.hub.github-login :as gl]))

(deftest a-github-token-is-cleaned-or-refused
  (is (= "ghp_abcdefghijklmnopqrstuvwxyz" (gl/clean-token " ghp_abcdefghij\nklmnopqrstuvwxyz ")))
  (is (= "github_pat_11AAAAAAAAAAAAAAAAAAAA_bbb" (gl/clean-token "github_pat_11AAAAAAAAAAAAAAAAAAAA_bbb")))
  (is (nil? (gl/clean-token "sk-ant-abcdefghijklmnopqrstuvwxyz")))
  (is (nil? (gl/clean-token "ghp_short")))
  (is (nil? (gl/clean-token nil))))

(deftest a-sealed-login-is-bound-to-its-person
  (is (= "github:ann@example.com" (gl/aad "ann@example.com"))))

(deftest a-look-at-a-server-is-recorded
  (is (= {:ok true :login "ann" :error nil :checked 5} (gl/sync-record [200 {:user {:login "ann"}}] 5)))
  (is (= {:ok false :login nil :error "answered 200" :checked 5} (gl/sync-record [200 {:user nil}] 5)))
  (is (= {:ok false :login nil :error "GitHub refused" :checked 5} (gl/sync-record [400 {:error "GitHub refused"}] 5)))
  (is (= {:ok false :login nil :error "no answer in 5 s" :checked 5} (gl/sync-record [0 {:error "no answer in 5 s"}] 5))))

(deftest the-page-sees-the-login-and-each-server
  (is (= {:on true :login "ann" :savedAt 9
          :servers [{:name "a" :label "A" :ok true :login "ann" :error nil}
                    {:name "b" :label "B" :ok false :login nil :error nil}]}
         (gl/state-of true {:login "ann" :came-from "a" :saved-at 9}
                      [{:name "a" :label "A"} {:name "b" :label "B"}]
                      {"a" {:ok true :login "ann"}})))
  (is (= {:on false :login nil :savedAt nil :servers []} (gl/state-of false nil [] {}))))
