(ns claudia.hub.main-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.hub.main :as main]))

(defn- req [headers] #js {:headers (clj->js headers)})

(deftest split-url-takes-the-query-off
  (is (= ["/a/b" "x=1"] (main/split-url "/a/b?x=1")))
  (is (= ["/a/b" nil] (main/split-url "/a/b")))
  (is (= ["/a" ""] (main/split-url "/a?"))))

(deftest take-view-takes-the-view-out
  (is (= {:view nil :query "" :error nil} (main/take-view nil)))
  (is (= {:view "paul" :query "?a=1&b=%20" :error nil} (main/take-view "a=1&view=paul&b=%20")))
  (is (= "view: give it once" (:error (main/take-view "view=a&view=b"))))
  (is (= "view: a profile name" (:error (main/take-view "view=../x"))))
  (is (= "view: a profile name" (:error (main/take-view "view=")))))

(deftest safe-rest-stays-under-the-path
  (is (main/safe-rest? "/api/chats/1"))
  (is (not (main/safe-rest? "/api/../secret")))
  (is (not (main/safe-rest? "/api/%2e%2e/x")))
  (is (not (main/safe-rest? "/api/a%2Fb")))
  (is (not (main/safe-rest? "/api/%E0"))))

(deftest path-problem-checks-what-passes
  (is (nil? (main/path-problem "/api/chats" false nil nil)))
  (is (nil? (main/path-problem "/ws" true "paul" nil)))
  (is (= 404 (:status (main/path-problem "/api/x" true nil nil))))
  (is (= 426 (:status (main/path-problem "/ws" false nil nil))))
  (is (= 404 (:status (main/path-problem "/other" false nil nil))))
  (is (= 404 (:status (main/path-problem nil false nil nil))))
  (is (= 400 (:status (main/path-problem "/api/../x" false nil nil))))
  (is (= 404 (:status (main/path-problem "/api/update-check" false nil nil))))
  (is (= 404 (:status (main/path-problem "/api/github" false nil nil))))
  (is (= 404 (:status (main/path-problem "/api/github/token" false nil nil))))
  (is (= {:status 400 :error "view: give it once"} (main/path-problem "/api/x" false nil "view: give it once")))
  (is (= 403 (:status (main/path-problem "/api/secret/k" false "paul" nil))))
  (is (nil? (main/path-problem "/api/secret/k" false nil nil))))

(deftest caller-is-the-gates-email
  (is (= "ann@example.com" (main/caller (req {"x-auth-request-email" " Ann@Example.com "}))))
  (is (nil? (main/caller (req {"x-auth-request-email" "not an email"}))))
  (is (nil? (main/caller (req {})))))

(deftest cross-site-requests-are-told-apart
  (is (false? (main/cross-site? (req {}))))
  (is (false? (main/cross-site? (req {"sec-fetch-site" "same-origin"}))))
  (is (true? (main/cross-site? (req {"sec-fetch-site" "cross-site"}))))
  (is (false? (main/cross-site? (req {"host" "hub.test" "origin" "https://hub.test"}))))
  (is (true? (main/cross-site? (req {"host" "hub.test" "origin" "https://other.test"}))))
  (testing "the forwarded host wins"
    (is (false? (main/cross-site? (req {"host" "inner" "x-forwarded-host" "Hub.test, x" "origin" "https://hub.test"}))))))

(deftest settings-from-the-environment
  (let [s (main/settings (fn [k default] (get {"HUB_PORT" "5000" "HOME" "/h"} k default)))]
    (is (= {:host "127.0.0.1" :port 5000 :web-root "out/web" :config-file nil
            :data-dir "/h/.local/share/claudia-hub"}
           s))))
