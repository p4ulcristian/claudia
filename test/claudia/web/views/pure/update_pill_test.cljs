(ns claudia.web.views.pure.update-pill-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.update-pill :as u]))

(def moving {:release {:commit "c" :tree "t" :merged 1000 :title "Fix"
                       :machines {:box {:state "done" :secs 4} :destiny {:state "waiting"}}
                       :hub {:state "waiting"}}})

(deftest the-pill-counts-what-has-taken-the-update
  (is (= {:label "Updating 1/3\n0:04" :tone "running"} (u/pill moving 5000))))

(deftest a-finished-update-says-how-long-then-goes
  (let [done (-> moving (assoc-in [:release :machines :destiny] {:state "done" :secs 6})
                 (assoc-in [:release :hub] {:state "done" :secs 15.4}))]
    (is (= {:label "Live in 15 s" :tone "done"} (u/pill done 20000)))
    (is (nil? (u/pill done 200000)))))

(deftest a-pull-requests-build-is-not-the-pills
  (is (nil? (u/pill {:builds [{:commit "abcdef1234" :tree "x" :state "pending" :at 1000 :started 1000}]} 2000))
      "built ahead of a merge: nothing is going live"))

(deftest a-merge-waiting-for-its-build-shows-the-build
  (let [p {:builds [{:commit "abcdef1234" :tree "t" :state "pending" :at 1000 :started 900}] :release (:release moving)}]
    (is (= {:label "Building abcdef1\n0:04" :tone "running"} (u/pill p 5000)))
    (is (= "Build failed" (:label (u/pill (assoc-in p [:builds 0 :state] "failure") 5000))))))

(deftest the-panel-lists-every-step
  (is (= ["merged" "hub" "box" "destiny"] (map first (u/steps moving)))))

(deftest when-this-version-went-live
  (is (= "8495c52 \u00b7 just now" (u/updated-text "8495c52" 0 30000)))
  (is (= "8495c52 \u00b7 5 min ago" (u/updated-text "8495c52" 0 (* 5 60000))))
  (is (= "3 h ago" (u/updated-text nil 0 (* 3 3600000))))
  (is (= "x \u00b7 1 day ago" (u/updated-text "x" 0 (* 30 3600000))))
  (is (= "x \u00b7 3 days ago" (u/updated-text "x" 0 (* 72 3600000))))
  (is (nil? (u/updated-text "x" nil 0))))

(deftest asleep-machines-do-not-count
  (is (= {:label "Updating 1/3\n0:04" :tone "running"}
         (u/pill (assoc-in moving [:release :machines :mac] {:state "asleep"}) 5000))))

(deftest the-clock-reads-like-a-clock
  (is (= "0:00" (u/clock-text -5)))
  (is (= "0:07" (u/clock-text 7999)))
  (is (= "1:42" (u/clock-text 102000)))
  (is (= "1:02:05" (u/clock-text 3725000))))
