(ns claudia.web.views.pure.switches-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.switches :as sw]))

(def db {:servers {"s" {:rows {"a" {:id "a" :talk true :bell false}
                               "b" {:id "b"}}}}
         :new-settings {:talk true}})

(deftest a-started-chat-reads-its-row
  (is (sw/talk-on? db ["s" "a"]))
  (is (not (sw/bell-on? db ["s" "a"]))))

(deftest a-row-without-switches-has-the-speaker-off-and-the-bell-on
  (is (not (sw/talk-on? db ["s" "b"])))
  (is (sw/bell-on? db ["s" "b"]))
  (is (sw/row-bell-on? {}))
  (is (not (sw/row-bell-on? {:bell false}))))

(deftest the-new-chat-reads-its-picks
  (is (sw/talk-on? db nil))
  (is (sw/bell-on? db nil)))

(deftest with-switches-lays-them-over-the-row-or-the-picks
  (is (false? (get-in (sw/with-switches db ["s" "b"] {:bell false}) [:servers "s" :rows "b" :bell])))
  (is (= "b" (get-in (sw/with-switches db ["s" "b"] {:bell false}) [:servers "s" :rows "b" :id])))
  (is (= {:talk false :bell false} (:new-settings (sw/with-switches db nil {:talk false :bell false})))))
