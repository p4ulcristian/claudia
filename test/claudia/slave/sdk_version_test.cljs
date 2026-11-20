(ns claudia.slave.sdk-version-test
  "Which Agent SDK a session runs on (README decision 86)."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.slave.sdk-version :as v]))

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

(deftest a-version-loads-from-the-store-copy-else-the-release
  (is (= "/r.sdk/1" (v/package-dir "1" [["/r.sdk/1" "1"] ["/r/nm" "1"]])))
  (testing "the release's own when the store lacks it (or holds another)"
    (is (= "/r/nm" (v/package-dir "1" [["/r.sdk/1" nil] ["/r/nm" "1"]])))
    (is (= "/r/nm" (v/package-dir "1" [["/r.sdk/1" "2"] ["/r/nm" "1"]]))))
  (testing "none when neither holds it, or the version is unknown"
    (is (nil? (v/package-dir "3" [["/r.sdk/3" nil] ["/r/nm" "1"]])))
    (is (nil? (v/package-dir nil [["/x" nil]])))))

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
    (is (false? (v/move-to-newest? (assoc idle-old :newest nil)))))
  (testing "a session on a version new sessions skip (rolled back) moves, even to an older one"
    (is (true? (v/move-to-newest? (assoc idle-old :sdk "0.3.328" :broken #{"0.3.328"}))))
    (is (false? (v/move-to-newest? (assoc idle-old :sdk "0.3.327" :broken #{"0.3.327"}))))
    (is (false? (v/move-to-newest? (assoc idle-old :sdk "0.3.328" :broken #{"0.3.328"} :running true))))))
