(ns claudia.hub.main-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.hub.claudia :as hclaudia]
            [claudia.hub.config :as config]
            [claudia.hub.main :as main]
            [claudia.hub.pictures :as pictures]))

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

(deftest path-problem-keeps-chat-moves-the-hubs
  (is (= {:status 404 :error "not found"} (main/path-problem "/api/chat-move/export" false nil nil)))
  (is (= {:status 404 :error "not found"} (main/path-problem "/api/chat-move/commit" false nil nil))))

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
  (is (= 404 (:status (main/path-problem "/api/sentinel-key" false nil nil))) "a person's Sentinel key is the hub's to hand out")
  (is (= 404 (:status (main/path-problem "/api/github/service-token" false nil nil))) "a service's app token is the hub's own")
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

(deftest view-servers-are-the-ones-without-a-profile
  (is (= [{:name "destiny"} {:name "mishung"}]
         (config/others [{:name "box"} {:name "destiny"} {:name "mishung"}] ["box"])))
  (is (= [] (config/others [{:name "box"}] ["box"]))))

(deftest hub-json-may-name-each-person-s-profile
  (let [servers "\"servers\":[{\"name\":\"box\",\"address\":\"10.0.0.2:4540\"}]"
        person #(str "{\"email\":\"" %1 "\",\"servers\":[\"box\"]" (when %2 (str ",\"profile\":\"" %2 "\"")) "}")
        parse #(config/parse (str "{" servers ",\"people\":[" (apply str (interpose "," %&)) "]}"))]
    (is (= ["paul" nil] (map :profile (:people (parse (person "a@x" "paul") (person "b@x" nil))))))
    (is (thrown-with-msg? js/Error #"profile \"Paul\" must be" (parse (person "a@x" "Paul"))))
    (is (thrown-with-msg? js/Error #"a profile is given twice" (parse (person "a@x" "paul") (person "b@x" "paul"))))))

(deftest pictures-are-png-or-webp-by-their-bytes
  (is (= "image/png" (pictures/image-type (js/Buffer.from "89504e470d0a1a0a0000" "hex"))))
  (is (= "image/webp" (pictures/image-type (js/Buffer.from "RIFF\u0000\u0000\u0000\u0000WEBPVP8 " "latin1"))))
  (is (nil? (pictures/image-type (js/Buffer.from "<svg>" "utf8"))))
  (is (= 16 (count (pictures/picture-key "a@b.c"))))
  (is (= "/hub/picture/" (subs (pictures/url-of "a@b.c" 5) 0 13)))
  (is (nil? (pictures/url-of "a@b.c" nil))))

(deftest claudia-calls-need-the-servers-key-and-a-profile-there
  (is (= "no server named" (hclaudia/caller-problem "" "a@b" true true)))
  (is (= "not that server's key" (hclaudia/caller-problem "destiny" "a@b" false true)))
  (is (= "no person named" (hclaudia/caller-problem "destiny" "" true true)))
  (is (= "that person has no profile on that server" (hclaudia/caller-problem "destiny" "a@b" true false)))
  (is (nil? (hclaudia/caller-problem "destiny" "a@b" true true)))
  (is (= "/api/chat-ops/stop" (hclaudia/op-target "stop")))
  (is (nil? (hclaudia/op-target "delete")))
  (is (= {:server "box" :id "c" :ref "box/c" :title "T" :color "jade" :at 5}
         (hclaudia/row-of "box" {:id "c" :title "T" :scene "big" :color "jade" :at 5}))))
