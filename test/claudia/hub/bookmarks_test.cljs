(ns claudia.hub.bookmarks-test
  "An agent's bookmark op on the hub (claudia.hub.bookmarks/change), on plain data."
  (:require [cljs.test :refer [deftest is]]
            [claudia.hub.bookmarks :as hb]))

(deftest change-adds-relabels-removes
  (let [items [{:server "home" :folder "/a"}]]
    (is (= [{:server "home" :folder "/a"} {:server "box" :folder "/b"}] (hb/change items {:server "box" :folder "/b/"})))
    (is (= [{:server "home" :folder "/a" :label "A"}] (hb/change items {:server "home" :folder "/a" :label " A "})))
    (is (= [] (hb/change items {:server "home" :folder "/a" :remove true})))
    (is (:error (hb/change items {:server "home" :folder "a/../b"})))))
