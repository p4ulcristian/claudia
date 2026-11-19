(ns claudia.slave.sdk-version-test
  "Which Agent SDK a session runs on (README decision 80)."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.slave.sdk-version :as v]))

(deftest versions-compare-as-numbers
  (is (v/older? "0.3.99" "0.3.326"))
  (is (v/older? "0.3.326" "0.4.0"))
  (is (zero? (v/compare-versions "0.3.326" "0.3.326")))
  (is (not (v/older? "1.0.0" "0.9.999")))
  (testing "a pre-release is older than its release"
    (is (v/older? "1.0.0-beta.2" "1.0.0"))
    (is (v/older? "1.0.0-beta.1" "1.0.0-beta.2")))
  (testing "a missing part counts as 0"
    (is (zero? (v/compare-versions "1.2" "1.2.0")))))

(deftest the-newest-of-several
  (is (= "0.3.326" (v/newest ["0.3.99" "0.3.326" "0.3.300"])))
  (is (nil? (v/newest []))))

(deftest a-failed-version-falls-back
  (testing "to the one the host started with"
    (is (= "0.3.325" (v/fallback "0.3.327" "0.3.325" ["0.3.325" "0.3.326" "0.3.327"]))))
  (testing "to the newest other loaded one when the start one is the failed one or not loaded"
    (is (= "0.3.326" (v/fallback "0.3.327" "0.3.327" ["0.3.325" "0.3.326" "0.3.327"])))
    (is (= "0.3.326" (v/fallback "0.3.327" nil ["0.3.325" "0.3.326"]))))
  (testing "to none when nothing else is loaded"
    (is (nil? (v/fallback "0.3.327" "0.3.327" ["0.3.327"])))))

(deftest new-sessions-start-on-the-installed-one-unless-it-broke
  (is (= "0.3.327" (v/start-version "0.3.327" {} "0.3.326" ["0.3.326"])))
  (is (= "0.3.326" (v/start-version "0.3.327" {"0.3.327" "spawn failed"} "0.3.326" ["0.3.326" "0.3.327"])))
  (testing "a broken one is never a fallback"
    (is (= "0.3.325" (v/start-version "0.3.327" {"0.3.327" "x" "0.3.326" "y"} "0.3.326" ["0.3.325" "0.3.326" "0.3.327"]))))
  (is (nil? (v/start-version "0.3.327" {"0.3.327" "x"} "0.3.327" ["0.3.327"]))))

(deftest the-store-copy-of-a-version
  (is (= "/srv/claudia.sdk/0.3.326/node_modules/@anthropic-ai/claude-agent-sdk"
         (v/stored-package "/srv/claudia" "0.3.326"))))

(def ^:private idle-old
  {:live true :running false :settling false :background false :sdk "0.3.326" :newest "0.3.327"})

(deftest an-idle-chat-on-an-older-sdk-moves
  (is (true? (v/move-to-newest? idle-old)))
  (testing "never mid-turn, while settling, or with background work"
    (is (false? (v/move-to-newest? (assoc idle-old :running true))))
    (is (false? (v/move-to-newest? (assoc idle-old :settling true))))
    (is (false? (v/move-to-newest? (assoc idle-old :background true)))))
  (testing "not when it already runs the newest (or a newer one), or has no session"
    (is (false? (v/move-to-newest? (assoc idle-old :sdk "0.3.327"))))
    (is (false? (v/move-to-newest? (assoc idle-old :sdk "0.3.328"))))
    (is (false? (v/move-to-newest? (assoc idle-old :live false)))))
  (testing "not when a version is unknown"
    (is (false? (v/move-to-newest? (assoc idle-old :sdk nil))))
    (is (false? (v/move-to-newest? (assoc idle-old :newest nil))))))
