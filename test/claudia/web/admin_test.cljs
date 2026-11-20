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

(deftest the-sdk-channel-line
  (is (= "Agent SDK: none published yet; servers run their release's." (admin/sdk-line {:newest nil :broken [] :published []})))
  (is (re-find #"^Agent SDK 0\.3\.330, published .*$"
               (admin/sdk-line {:newest "0.3.330" :broken [] :published [{:version "0.3.330" :at 0}]})))
  (is (re-find #" Rolled back: 0\.3\.331\.$"
               (admin/sdk-line {:newest "0.3.330" :broken ["0.3.331"] :published [{:version "0.3.331" :at 1} {:version "0.3.330" :at 0}]}))))
