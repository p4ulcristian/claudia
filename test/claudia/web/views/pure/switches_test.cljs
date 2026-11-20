(ns claudia.web.views.pure.switches-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.switches :as sw]))

(def db {:servers {"s" {:rows {"a" {:id "a" :voice "nora" :bell false}
                               "b" {:id "b"}
                               "c" {:id "c" :voice "gone"}}}}
         :new-settings {:voice "otto"}})

(deftest a-started-chat-reads-its-row
  (is (= "nora" (sw/voice-of db ["s" "a"])))
  (is (not (sw/bell-on? db ["s" "a"]))))

(deftest a-row-without-switches-is-silent-with-the-bell-on
  (is (= "" (sw/voice-of db ["s" "b"])))
  (is (= "" (sw/voice-of db ["s" "c"])) "an unknown voice is Off")
  (is (sw/bell-on? db ["s" "b"]))
  (is (sw/row-bell-on? {}))
  (is (not (sw/row-bell-on? {:bell false}))))

(deftest the-new-chat-reads-its-picks
  (is (= "otto" (sw/voice-of db nil)))
  (is (sw/bell-on? db nil)))

(deftest with-switches-lays-them-over-the-row-or-the-picks
  (is (false? (get-in (sw/with-switches db ["s" "b"] {:bell false}) [:servers "s" :rows "b" :bell])))
  (is (= "b" (get-in (sw/with-switches db ["s" "b"] {:bell false}) [:servers "s" :rows "b" :id])))
  (is (= {:voice "" :bell false} (:new-settings (sw/with-switches db nil {:voice "" :bell false})))))
