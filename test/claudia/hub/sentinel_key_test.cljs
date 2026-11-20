(ns claudia.hub.sentinel-key-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.hub.sentinel-key :as sk]))

(deftest a-sealed-key-is-bound-to-its-person
  (is (= "sentinel:ann@example.com" (sk/aad "ann@example.com"))))

(deftest the-kept-key-is-handed-over-only-when-the-server-has-another
  (is (sk/push? {:fingerprint "f1"} [200 {:hint nil :fingerprint nil}]) "none there yet")
  (is (sk/push? {:fingerprint "f1"} [200 {:hint "snt_bbbb" :fingerprint "f2"}]) "an older key")
  (is (not (sk/push? {:fingerprint "f1"} [200 {:hint "snt_aaaa" :fingerprint "f1"}])))
  (is (sk/push? {:fingerprint nil :removed true} [200 {:fingerprint "f1"}]) "removed: the server drops it")
  (is (not (sk/push? {:fingerprint nil :removed true} [200 {:fingerprint nil}])))
  (is (not (sk/push? {:fingerprint "f1"} [404 {:error "not found"}])) "an older runner: nothing to hand to")
  (is (not (sk/push? {:fingerprint "f1"} [0 {:error "no answer"}]))))

(deftest a-look-at-a-server-is-recorded
  (is (= {:ok true :hint "snt_aaaa" :error nil :checked 5}
         (sk/sync-record {:fingerprint "f1"} [200 {:hint "snt_aaaa" :fingerprint "f1"}] 5)))
  (is (= {:ok false :hint "snt_bbbb" :error "has another key" :checked 5}
         (sk/sync-record {:fingerprint "f1"} [200 {:hint "snt_bbbb" :fingerprint "f2"}] 5)))
  (is (= {:ok false :hint nil :error "answered 404" :checked 5} (sk/sync-record {:fingerprint "f1"} [404 nil] 5)))
  (is (= {:ok false :hint nil :error "no answer" :checked 5} (sk/sync-record {:fingerprint "f1"} [0 {:error "no answer"}] 5)))
  (is (:ok (sk/sync-record {:fingerprint nil :removed true} [200 {:hint nil :fingerprint nil}] 5))))

(deftest the-page-sees-the-hint-and-each-server
  (is (= {:on true :hint "snt_aaaa" :savedAt 9
          :servers [{:name "a" :label "A" :ok true :error nil}
                    {:name "b" :label "B" :ok false :error nil}]}
         (sk/state-of true {:hint "snt_aaaa" :fingerprint "f1" :saved-at 9 :removed false}
                      [{:name "a" :label "A"} {:name "b" :label "B"}]
                      {"a" {:ok true :hint "snt_aaaa"}})))
  (is (= {:on true :hint nil :savedAt nil :servers []} (sk/state-of true {:removed true :saved-at 9} [] {})))
  (is (= {:on false :hint nil :savedAt nil :servers []} (sk/state-of false nil [] {}))))

(deftest a-save-is-refused-without-a-key-file-or-a-key
  (is (= 503 (first (sk/save-problem false "k"))))
  (is (= 400 (first (sk/save-problem true nil))))
  (is (nil? (sk/save-problem true "k"))))
