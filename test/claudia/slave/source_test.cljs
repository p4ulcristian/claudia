(ns claudia.slave.source-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.slave.source :as source]))

(deftest refs-and-paths
  (is (source/ref-ok? "origin/main"))
  (is (source/ref-ok? "v1.2.0"))
  (is (source/ref-ok? "HEAD~2"))
  (is (not (source/ref-ok? "--output=x")))
  (is (not (source/ref-ok? "a..b")))
  (is (not (source/ref-ok? "a b")))
  (is (not (source/ref-ok? nil)))
  (is (source/rel-ok? "src/a.clj"))
  (is (source/rel-ok? ""))
  (is (not (source/rel-ok? "../etc")))
  (is (not (source/rel-ok? "a/../../b")))
  (is (not (source/rel-ok? "/etc/passwd")))
  (is (not (source/rel-ok? "-x"))))

(deftest grep-counts
  (is (= [["src/a.clj" 12] ["b:c.txt" 3]] (source/parse-counts "origin/main:src/a.clj:12\norigin/main:b:c.txt:3\n" "origin/main")))
  (is (= [["src/a.clj" 12]] (source/parse-counts "src/a.clj:12\nBinary\n" nil)))
  (is (= [] (source/parse-counts nil nil))))
