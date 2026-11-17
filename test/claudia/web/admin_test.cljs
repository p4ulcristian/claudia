(ns claudia.web.admin-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [claudia.web.admin :as admin]))

(deftest paths-are-encoded
  (is (= "/hub/admin/servers/a%2Fb" (admin/server-path "a/b")))
  (is (= "/hub/admin/people/a%40b.c" (admin/person-path "a@b.c"))))

(deftest pair-body-trims-and-leaves-out-blanks
  (is (= {:key "k"} (admin/pair-body {:key " k " :address "" :name " " :label ""})))
  (is (= {:key "k" :address "100.101.2.3:4540" :name "team" :label "Team"}
         (admin/pair-body {:key "k" :address " 100.101.2.3:4540" :name " team" :label "Team "}))))

(deftest pairing-note-by-state
  (is (= "by hand" (first (admin/pairing-note {}))))
  (is (= "paired" (first (admin/pairing-note {:pairing {:fetchedAt 2 :createdAt 1}}))))
  (is (= "waiting" (first (admin/pairing-note {:pairing {:createdAt 1}}))))
  (is (str/starts-with? (second (admin/pairing-note {:pairing {:createdAt 1}})) "Paired ")))

(deftest the-release-builder-line
  (is (= "None yet: releases come from GitHub." (admin/builder-line nil)))
  (is (re-find #"^box, paired .*, not heard from yet$" (admin/builder-line {:host "box" :pairedAt 0}))))
