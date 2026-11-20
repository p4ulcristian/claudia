(ns claudia.shared.sdk-channel-test
  "The Agent SDK channel's decisions (README decision 81)."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.hub.sdk-channel :as hub]
            [claudia.shared.sdk-channel :as c]
            [claudia.slave.sdk-cli :as cli]))

(deftest versions-compare-as-numbers
  (is (c/older? "0.3.99" "0.3.326"))
  (is (c/older? "0.3.326" "0.4.0"))
  (is (zero? (c/compare-versions "0.3.326" "0.3.326")))
  (is (not (c/older? "1.0.0" "0.9.999")))
  (testing "a pre-release is older than its release"
    (is (c/older? "1.0.0-beta.2" "1.0.0"))
    (is (c/older? "1.0.0-beta.1" "1.0.0-beta.2")))
  (testing "a missing part counts as 0"
    (is (zero? (c/compare-versions "1.2" "1.2.0")))))

(deftest the-newest-of-several
  (is (= "0.3.326" (c/newest ["0.3.99" "0.3.326" "0.3.300"])))
  (is (nil? (c/newest []))))

(deftest only-plain-versions
  (is (c/version? "0.3.329"))
  (is (not (c/version? "0.3.329-beta.1")))
  (is (not (c/version? "0.3.329; rm -rf /")))
  (is (not (c/version? "../0.3.329")))
  (is (not (c/version? nil))))

(deftest the-builder-publishes-a-newer-version-never-below-main
  (is (true? (c/publish? "0.3.330" "0.3.329" "0.3.326")))
  (testing "nothing published yet"
    (is (true? (c/publish? "0.3.330" nil "0.3.326")))
    (is (true? (c/publish? "0.3.330" "" "0.3.326"))))
  (testing "the one main pins is allowed, one below it never"
    (is (true? (c/publish? "0.3.326" nil "0.3.326")))
    (is (false? (c/publish? "0.3.325" nil "0.3.326"))))
  (testing "not again, and never an older one"
    (is (false? (c/publish? "0.3.330" "0.3.330" "0.3.326")))
    (is (false? (c/publish? "0.3.329" "0.3.330" "0.3.326"))))
  (testing "only plain versions, and only with main's pin known"
    (is (false? (c/publish? "0.4.0-beta.1" "0.3.330" "0.3.326")))
    (is (false? (c/publish? "0.3.331" "0.3.330" nil)))))

(deftest each-machine-fetches-its-platform
  (is (= "linux-x64" (c/platform "Linux" "x86_64")))
  (is (= "darwin-arm64" (c/platform "Darwin" "arm64")))
  (is (nil? (c/platform "Linux" "aarch64")))
  (is (nil? (c/platform "Darwin" "x86_64")))
  (is (= "claudia-sdk-0.3.330-linux-x64.tar.gz" (c/tarball "0.3.330" "linux-x64"))))

(deftest the-hub-keeps-only-channel-files
  (is (c/file-name? "claudia-sdk-0.3.330-linux-x64.tar.gz"))
  (is (c/file-name? "claudia-sdk-0.3.330-darwin-arm64.tar.gz.sig"))
  (is (not (c/file-name? "claudia-sdk-0.3.330-win32-x64.tar.gz")))
  (is (not (c/file-name? "claudia-sdk-../x-linux-x64.tar.gz")))
  (is (= "0.3.330" (c/file-version "claudia-sdk-0.3.330-linux-x64.tar.gz.sig")))
  (is (= 4 (count (hub/files-of "0.3.330"))))
  (is (some #{"claudia-sdk-0.3.330-darwin-arm64.tar.gz.sig"} (hub/files-of "0.3.330"))))

(deftest a-broken-file-names-versions
  (is (= #{"0.3.329" "0.3.330"} (c/parse-marks "0.3.329\n 0.3.330 \n\nnonsense\n0.3.331-x\n")))
  (is (= #{} (c/parse-marks nil))))

(deftest a-new-session-starts-on-the-newest-not-marked
  (is (= "0.3.330" (c/usable-newest "0.3.326" ["0.3.326" "0.3.329" "0.3.330"] #{})))
  (testing "a marked one is skipped"
    (is (= "0.3.329" (c/usable-newest "0.3.326" ["0.3.326" "0.3.329" "0.3.330"] #{"0.3.330"}))))
  (testing "the release's own counts, even when the store lacks it"
    (is (= "0.3.331" (c/usable-newest "0.3.331" ["0.3.330"] #{})))
    (is (= "0.3.326" (c/usable-newest "0.3.326" [] #{}))))
  (testing "every one marked: the release's own"
    (is (= "0.3.326" (c/usable-newest "0.3.326" ["0.3.330"] #{"0.3.326" "0.3.330"}))))
  (testing "no release copy: the store's"
    (is (= "0.3.330" (c/usable-newest nil ["0.3.330"] #{})))
    (is (nil? (c/usable-newest nil [] #{})))))

(deftest a-server-stores-a-new-unmarked-version
  (is (true? (c/install? "0.3.330" "0.3.326" ["0.3.326"] #{})))
  (is (false? (c/install? "0.3.330" "0.3.326" ["0.3.330"] #{})) "stored already")
  (is (false? (c/install? "0.3.330" "0.3.326" [] #{"0.3.330"})) "rolled back")
  (is (false? (c/install? "0.3.325" "0.3.326" [] #{})) "older than the release's own")
  (is (true? (c/install? "0.3.326" "0.3.326" [] #{})) "the release's own, not stored yet")
  (is (false? (c/install? "x; rm" "0.3.326" [] #{}))))

(deftest the-hub-names-the-newest-not-rolled-back
  (is (= "0.3.330" (c/current ["0.3.329" "0.3.330"] [])))
  (is (= "0.3.329" (c/current ["0.3.329" "0.3.330"] ["0.3.330"])))
  (is (nil? (c/current ["0.3.330"] ["0.3.330"]))))

(deftest the-hub-keeps-the-newest-published
  (let [p (reduce (fn [p [v t]] (c/with-published p v t)) []
                  [["0.3.320" 1] ["0.3.321" 2] ["0.3.322" 3] ["0.3.323" 4] ["0.3.324" 5] ["0.3.325" 6] ["0.3.324" 7]])]
    (is (= ["0.3.325" "0.3.324" "0.3.323" "0.3.322" "0.3.321"] (map :version p)))
    (is (= 7 (:at (second p))) "published again: once, the new time")
    (is (= ["claudia-sdk-0.3.320-linux-x64.tar.gz"]
           (c/stale-files ["claudia-sdk-0.3.320-linux-x64.tar.gz" "claudia-sdk-0.3.325-linux-x64.tar.gz"] p)))))

(deftest an-admin-rolls-back-a-published-version-once
  (let [s {:published [{:version "0.3.330" :at 1}] :broken []}]
    (is (= ["0.3.330"] (:broken (c/roll-back s "0.3.330"))))
    (is (nil? (c/roll-back s "0.3.331")) "not published")
    (is (nil? (c/roll-back (c/roll-back s "0.3.330") "0.3.330")) "rolled back already")))

(deftest the-hub-answers-newest-and-rolled-back
  (is (= {:newest "0.3.329" :broken ["0.3.330"] :published [{:version "0.3.330" :at 2} {:version "0.3.329" :at 1}]}
         (hub/answer {:published [{:version "0.3.330" :at 2} {:version "0.3.329" :at 1}] :broken ["0.3.330"]}))))

(deftest the-scripts-ask-the-same-decisions
  (is (true? (cli/answer ["publish" "0.3.330" "0.3.329" "0.3.326"])))
  (is (false? (cli/answer ["publish" "0.3.329" "0.3.329" "0.3.326"])))
  (is (= "darwin-arm64" (cli/answer ["platform" "Darwin" "arm64"])))
  (is (= "claudia-sdk-0.3.330-linux-x64.tar.gz" (cli/answer ["tarball" "0.3.330" "linux-x64"])))
  (is (nil? (cli/answer ["tarball" "0.3.330" "../x"])))
  (is (= "linux-x64\ndarwin-arm64" (cli/answer ["platforms"])))
  (is (nil? (cli/answer ["nope"]))))
