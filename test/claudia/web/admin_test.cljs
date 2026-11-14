(ns claudia.web.admin-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [claudia.web.admin :as admin]))

(deftest paths-are-encoded
  (is (= "/hub/admin/servers/a%2Fb" (admin/server-path "a/b")))
  (is (= "/hub/admin/people/a%40b.c" (admin/person-path "a@b.c"))))

(deftest pair-body-trims-and-leaves-out-blanks
  (is (= {:key "k"} (admin/pair-body {:key " k " :name " " :label ""})))
  (is (= {:key "k" :name "team" :label "Team"} (admin/pair-body {:key "k" :name " team" :label "Team "}))))

(deftest pairing-note-by-state
  (is (= "on WireGuard" (first (admin/pairing-note {}))))
  (is (= "fetched" (first (admin/pairing-note {:pairing {:fetchedAt 2 :createdAt 1 :ip "x"}}))))
  (is (= ["peer up" "The WireGuard peer is up (x); waiting for the server to fetch its address"]
         (admin/pairing-note {:pairing {:ip "x"} :peerUp true})))
  (is (str/starts-with? (second (admin/pairing-note {:pairing {:createdAt 1}})) "Paired ")))

(deftest the-release-builder-line
  (is (= "None yet: releases come from GitHub." (admin/builder-line nil)))
  (is (re-find #"^box, paired .*, not heard from yet$" (admin/builder-line {:host "box" :pairedAt 0}))))
