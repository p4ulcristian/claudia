(ns claudia.hub.health-test
  "The hub rolls a release back on its pages' word (README decision 35)."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.hub.health :as h]))

(def ^:private c40 (apply str (repeat 40 "c")))

(def ^:private fresh {:started 0 :ok #{} :broken {} :rolled false})

(defn- broken [load reason] {:load load :commit "ccccccc" :ok false :reason reason})

(deftest a-report-is-read-or-refused
  (is (= {:load "load-0001" :commit "ccccccc" :ok true :reason nil :detail nil}
         (h/report {:load "load-0001" :commit "ccccccc" :ok true})))
  (is (= "no-chats" (:reason (h/report {:load "load-0001" :commit "ccccccc" :ok false :reason "no-chats"}))))
  (is (= 300 (count (:detail (h/report {:load "load-0001" :commit "ccccccc" :ok false :reason "script-error"
                                        :detail (apply str (repeat 500 "x"))})))) "detail cut")
  (testing "refused"
    (is (nil? (h/report {:load "load-0001" :commit "ccccccc" :ok false :reason "bored"})) "unknown reason")
    (is (nil? (h/report {:load "x" :commit "ccccccc" :ok true})) "load id too short")
    (is (nil? (h/report {:load "load-0001" :commit "main" :ok true})) "not a commit")
    (is (nil? (h/report {:load "load-0001" :commit "ccccccc" :ok "yes"})) "ok not a boolean")
    (is (nil? (h/report {})))))

(deftest only-this-commit-and-only-at-first
  (let [rep (broken "load-0001" "no-chats")]
    (is (h/counts? fresh c40 rep 1000))
    (is (not (h/counts? fresh c40 rep h/window-ms)) "after the window")
    (is (not (h/counts? fresh nil rep 1000)) "a hub that names no commit never rolls back")
    (is (not (h/counts? fresh c40 (assoc rep :commit "aaaaaaa") 1000)) "a page of another release")))

(deftest a-load-counts-once
  (let [t (-> fresh (h/with-report (broken "load-0001" "no-chats")) (h/with-report (broken "load-0001" "no-chats")))]
    (is (= 1 (count (:broken t))))
    (is (= 1 (count (:broken (h/with-report t {:load "load-0001" :ok true})))) "its first word stays")))

(deftest broken-loads-and-none-working-roll-back
  (let [one (h/with-report fresh (broken "load-0001" "no-chats"))
        two (h/with-report one (broken "load-0002" "script-error"))]
    (is (not (h/roll-back? one)) "one broken load is not enough")
    (is (h/roll-back? two))
    (is (not (h/roll-back? (h/with-report two {:load "load-0003" :ok true}))) "a page that works: not the release")
    (is (not (h/roll-back? (assoc two :rolled true))) "once")))
