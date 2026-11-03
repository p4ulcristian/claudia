(ns claudia.shared.shapes-test
  "The shared namespaces whose locals were renamed: files, forms, git-layout."
  (:require [cljs.test :refer [deftest is]]
            [claudia.shared.files :as files]
            [claudia.shared.forms :as forms]
            [claudia.shared.git-layout :as layout]))

(deftest a-file-tag-splits-back
  (let [f {:path "/tmp/a \"b\".txt" :name "a <b>.txt" :size 12 :type "text/plain"}
        sent (files/with-files "Look" [f])]
    (is (= "<claudia-file path=\"/tmp/a &quot;b&quot;.txt\" name=\"a &lt;b&gt;.txt\" size=\"12\" type=\"text/plain\">The user attached this file; it is saved at the path.</claudia-file>"
           (files/tag f)))
    (is (= {:text "Look" :files [f]} (files/split-files sent)))))

(deftest help-line-says-when-and-what
  (let [n (first forms/names)
        line (forms/help-line n)]
    (is (re-find (re-pattern (str "^- " n ": .+ Data: .+\\.$")) line))))

(deftest lanes-follow-first-parents
  (is (= [0 0 1 0]
         (map :col (layout/assign-lanes [{:hash "d" :parents ["c" "b"]}
                                         {:hash "c" :parents ["a"]}
                                         {:hash "b" :parents ["a"]}
                                         {:hash "a" :parents []}]))))
  (is (= [0] (map :col (layout/assign-lanes [{:hash "x" :parents ["off-screen"]}])))))
