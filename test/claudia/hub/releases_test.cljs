(ns claudia.hub.releases-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.hub.releases :as r]))

(def t (apply str (repeat 40 "a")))
(def d (apply str (repeat 20 "b")))
(def secret (apply str (repeat 43 "x")))

(deftest a-builder-key-names-its-host-and-secret
  (is (= {:host "box-1" :secret secret} (r/parse-builder-key (str " claudia-builder:box-1:" secret "\n"))))
  (is (nil? (r/parse-builder-key (str "claudia-pair:box-1:" secret))))
  (is (nil? (r/parse-builder-key "claudia-builder:box-1:short")))
  (is (nil? (r/parse-builder-key (str "claudia-builder:bad host:" secret)))))

(deftest only-release-files-are-kept
  (is (r/file-name? (str "claudia-code-" t ".tar.gz")))
  (is (r/file-name? (str "claudia-code-" t ".tar.gz.sig")))
  (is (r/file-name? (str "claudia-deps-" d ".tar.gz")))
  (is (r/file-name? (str "claudia-deps-" d "-darwin-arm64.tar.gz.sig")))
  (is (not (r/file-name? (str "claudia-tree-" t ".tar.gz"))))
  (is (not (r/file-name? "../hub.db")))
  (is (not (r/file-name? (str "claudia-code-" t ".tar.gz/x")))))

(deftest a-release-needs-code-and-both-node-modules-each-signed
  (is (= 6 (count (r/needed-files t d))))
  (is (some #{(str "claudia-deps-" d "-darwin-arm64.tar.gz.sig")} (r/needed-files t d))))

(deftest the-queue-keeps-one-item-per-key-newest-first
  (let [q (-> [] (r/with-wanted {:key "main" :commit "1"}) (r/with-wanted {:key "pr-7" :commit "2"})
              (r/with-wanted {:key "main" :commit "3"}))]
    (is (= ["3" "2"] (map :commit q)))
    (testing "a built commit or a closed pull request leaves it"
      (is (= ["2"] (map :commit (r/without-commit q {:commit "3"}))))
      (is (= ["3"] (map :commit (r/without-commit q {:key "pr-7"})))))
    (is (= 12 (count (reduce #(r/with-wanted %1 {:key (str "pr-" %2) :commit (str %2)}) [] (range 30)))))))

(deftest old-files-go-with-their-signatures
  (let [code (fn [i] (str "claudia-code-" (.padStart (.toString i 16) 40 "0") ".tar.gz"))
        files (concat (for [i (range 22)] [(code i) i]) (for [i (range 22)] [(str (code i) ".sig") i]))
        stale (set (map first (r/stale-files (vec files))))]
    (is (contains? stale (code 0)))
    (is (contains? stale (str (code 0) ".sig")))
    (is (not (contains? stale (code 21))))))
