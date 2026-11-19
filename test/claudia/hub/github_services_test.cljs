(ns claudia.hub.github-services-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.hub.github-services :as g]))

(deftest services-from-the-setting
  (is (= {:services [{:name "bot" :server "box" :owner "acme" :repos ["app" "infra"]
                      :permissions {:contents "write" :secrets "read"}}
                     {:name "ci" :server "other.one" :owner "acme" :repos ["app"]
                      :permissions {:administration "write"}}]
          :bad []}
         (g/parse-services "bot@box=acme/app,acme/infra|contents:write,secrets:read; ci@other.one=acme/app|administration:write\n"))))

(deftest services-not-understood-are-named
  (is (= ["bot@box=acme/app,other/x|contents:read" "Bad@box=acme/app|contents:read" "bot@box=acme/app"
          "bot@box=acme/app|contents:admin" "plain"]
         (:bad (g/parse-services (str "bot@box=acme/app,other/x|contents:read Bad@box=acme/app|contents:read"
                                      " bot@box=acme/app bot@box=acme/app|contents:admin plain"))))
      "two owners, a bad name, no permissions, a level that is not read or write")
  (is (= {:services [] :bad []} (g/parse-services nil))))

(deftest a-token-is-replaced-within-15-minutes-of-its-end
  (is (true? (g/due? nil 0)))
  (is (true? (g/due? {:token "t" :expires-at (* 14 60000)} 0)))
  (is (false? (g/due? {:token "t" :expires-at (* 16 60000)} 0))))

(deftest what-the-front-is-handed
  (is (= {:name "bot" :token "ghs_x" :expiresAt 5 :login "claudia-1[bot]"}
         (g/token-body {:name "bot"} {:token "ghs_x" :expires-at 5} "claudia-1"))))
