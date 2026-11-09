(ns claudia.web.shiki-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.shiki :as shiki]))

(def ^:private index {:javascript "javascript" :js "javascript" :clojure "clojure" :clj "clojure"
                      :typescript "typescript" :ts "typescript" :c "c"})

(deftest lang-file-names-the-grammar-file
  (is (= "javascript" (shiki/lang-file index "js")))
  (is (= "javascript" (shiki/lang-file index "JavaScript")) "any case")
  (testing "names Shiki doesn't know but files use"
    (is (= "clojure" (shiki/lang-file index "cljs")))
    (is (= "typescript" (shiki/lang-file index "mts")))
    (is (= "c" (shiki/lang-file index "h"))))
  (testing "unknown or no language: plain text"
    (is (nil? (shiki/lang-file index "brainfudge")))
    (is (nil? (shiki/lang-file index "")))
    (is (nil? (shiki/lang-file index nil)))))

(deftest clojure?-names-every-clojure-language
  (is (every? shiki/clojure? ["clj" "cljs" "cljc" "edn" "bb" "clojure" "Clojure" "CLJ"]))
  (is (not-any? shiki/clojure? ["js" "sql" "md" "" nil])))
