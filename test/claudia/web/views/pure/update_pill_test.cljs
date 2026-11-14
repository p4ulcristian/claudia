(ns claudia.web.views.pure.update-pill-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.update-pill :as u]))

(def moving {:release {:commit "c" :tree "t" :merged 1000 :title "Fix"
                       :machines {:box {:state "done" :secs 4} :destiny {:state "waiting"}}
                       :hub {:state "waiting"}}})

(deftest the-pill-counts-what-has-taken-the-update
  (is (= {:label "Updating 1/3" :tone "running"} (u/pill moving 5000))))

(deftest a-finished-update-says-how-long-then-goes
  (let [done (-> moving (assoc-in [:release :machines :destiny] {:state "done" :secs 6})
                 (assoc-in [:release :hub] {:state "done" :secs 15.4}))]
    (is (= {:label "Live in 15 s" :tone "done"} (u/pill done 20000)))
    (is (nil? (u/pill done 200000)))))

(deftest a-build-before-the-merge-shows
  (is (= {:label "Building abcdef1" :tone "running"}
         (u/pill {:builds [{:commit "abcdef1234" :tree "x" :state "pending" :at 1000 :started 1000}]} 2000)))
  (is (= "Build failed" (:label (u/pill {:builds [{:commit "abcdef1234" :state "failure" :at 1000 :started 900}]} 2000))))
  (is (nil? (u/pill {:builds [{:commit "abcdef1234" :state "success" :at 1000 :started 900}]} 2000))))

(deftest the-panel-lists-every-step
  (is (= ["merged" "hub" "box" "destiny"] (map first (u/steps moving)))))
