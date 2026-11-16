(ns claudia.hub.github-app-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.hub.github-app :as g]))

(deftest settings-need-all-three
  (let [e {"HUB_GITHUB_APP_SLUG" "claudia-x" "HUB_GITHUB_APP_CLIENT_ID" "Iv1.abc"}]
    (is (= {:slug "claudia-x" :client-id "Iv1.abc" :secret "s3"} (g/settings e "s3\n")))
    (is (nil? (g/settings e nil)))
    (is (nil? (g/settings (dissoc e "HUB_GITHUB_APP_SLUG") "s3")))))

(deftest authorize-url-carries-client-redirect-and-state
  (is (= "https://github.com/login/oauth/authorize?client_id=Iv1.a&redirect_uri=https%3A%2F%2Fhub.example%2Fhub%2Fgithub%2Fcallback&state=s1"
         (g/authorize-url "Iv1.a" "https://hub.example/hub/github/callback" "s1"))))

(deftest token-record-from-github
  (is (= {:access "ghu_1" :access-expires-at 28801000 :refresh "ghr_1" :refresh-expires-at 15897601000}
         (g/token-record {:access_token "ghu_1" :expires_in 28800 :refresh_token "ghr_1" :refresh_token_expires_in 15897600} 1000)))
  (is (= {:access "ghu_1" :access-expires-at nil :refresh nil :refresh-expires-at nil}
         (g/token-record {:access_token "ghu_1"} 1000)) "a token that does not run out")
  (is (nil? (g/token-record {:error "bad_verification_code"} 1000))))

(deftest renew-only-near-the-end-and-with-a-live-refresh-token
  (let [t {:access-expires-at (* 20 60000) :refresh "r" :refresh-expires-at (* 1000 60000)}]
    (is (true? (g/renew? t 0)) "20 minutes left")
    (is (false? (g/renew? t (* -60 60000))) "80 minutes left")
    (is (false? (g/renew? (dissoc t :refresh) 0)))
    (is (false? (g/renew? (assoc t :refresh-expires-at -1) 0)) "the refresh token ran out")
    (is (false? (g/renew? (assoc t :access-expires-at nil) 0)) "a token that does not run out")))

(deftest installations-as-the-page-lists-them
  (is (= {:id 7 :account "p" :type "User" :selection "all" :configureUrl "https://github.com/settings/installations/7"}
         (g/installation-row {:id 7 :account {:login "p"} :target_type "User" :repository_selection "all"
                              :html_url "https://github.com/settings/installations/7"}))))

(deftest only-githubs-return-pages-pass-the-cross-site-guard
  (is (true? (g/return-path? "/hub/github/callback")))
  (is (true? (g/return-path? "/hub/github/installed")))
  (is (false? (g/return-path? "/hub/github/connection")))
  (is (false? (g/return-path? "/hub/github/connect"))))

(deftest state-for-the-page
  (is (= {:app {:slug "c" :installUrl "https://github.com/apps/c/installations/new"}
          :user {:login "p" :accessExpiresAt 5 :refreshExpiresAt 9}
          :installations [] :servers [{:name "box" :ok true}]}
         (g/state-of {:slug "c"} {:login "p" :access-expires-at 5 :refresh-expires-at 9} [] [{:name "box" :ok true}])))
  (is (nil? (:app (g/state-of nil nil [] [])))))
