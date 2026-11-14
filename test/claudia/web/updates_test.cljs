(ns claudia.web.updates-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.updates :as up]))

(deftest only-another-known-commit-is-new
  (is (up/newer? "abc1234" "def5678"))
  (is (not (up/newer? "abc1234" "abc1234")))
  (is (not (up/newer? nil "def5678")) "a hub that named no commit at load: never")
  (is (not (up/newer? "abc1234" nil))))

(deftest a-reload-waits-for-what-it-would-lose
  (is (not (up/reload-blocked? {:pending-new {} :outbox {["s" "c"] []} :attach {:new []}})))
  (is (up/reload-blocked? {:pending-new {"u" {:server "s"}}}))
  (is (up/reload-blocked? {:outbox {["s" "c"] ["hi"]}}))
  (is (up/reload-blocked? {:attach {:new [{:name "a.png"}]}})))

(deftest drafts-survive-the-round-trip
  (let [drafts {:new "a new one" ["s" "c"] "half a thought" ["s" "d"] ""}]
    (is (= {:new "a new one" ["s" "c"] "half a thought"} (up/json-drafts (up/drafts-json drafts))))))

(deftest bad-kept-drafts-are-none
  (is (= {} (up/json-drafts nil)))
  (is (= {} (up/json-drafts "not json")))
  (is (= {} (up/json-drafts "{\"k\": 1}")))
  (is (= {} (up/json-drafts "[{\"k\": [\"s\"], \"text\": \"x\"}]"))))
