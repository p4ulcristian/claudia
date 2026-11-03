(ns claudia.hub.tokens-test
  (:require [cljs.test :refer [deftest is testing]]
            ["node:crypto" :as crypto]
            [claudia.hub.tokens :as tokens]))

(def ^:private secret (crypto/randomBytes 32))
(def ^:private sample-token (str "sk-ant-oat01-" (apply str (repeat 30 "a"))))

(deftest seal-opens-only-under-its-own-key-and-secret
  (let [sealed (tokens/seal secret "paul" sample-token)]
    (is (= sample-token (tokens/unseal secret "paul" sealed)))
    (is (not (re-find #"sk-ant" sealed)) "the sealed text holds no token text")
    (is (not= sealed (tokens/seal secret "paul" sample-token)) "a new nonce every time")
    (testing "another key (AAD) or another secret does not open it"
      (is (thrown? js/Error (tokens/unseal secret "ananda" sealed)))
      (is (thrown? js/Error (tokens/unseal (crypto/randomBytes 32) "paul" sealed))))))

(deftest names-and-tokens-are-cleaned
  (is (= "Paul" (tokens/clean-name "  Paul ")))
  (is (= "Ananda M." (tokens/clean-name "Ananda M.")))
  (is (nil? (tokens/clean-name "")))
  (is (nil? (tokens/clean-name "-x")))
  (is (nil? (tokens/clean-name (apply str (repeat 25 "a")))))
  (is (= sample-token (tokens/clean-token (str " " (subs sample-token 0 10) "\n" (subs sample-token 10)))))
  (is (nil? (tokens/clean-token "ghp_abc")))
  (is (= "ananda-m-" (tokens/fold "Ananda M."))))

(deftest refusals
  (is (= 503 (first (tokens/save-problem false "Paul" sample-token))))
  (is (= 400 (first (tokens/save-problem true nil sample-token))))
  (is (= 400 (first (tokens/save-problem true "Paul" nil))))
  (is (nil? (tokens/save-problem true "Paul" sample-token)))
  (is (= 404 (first (tokens/pick-problem true "x" ["paul"] "paul" false))))
  (is (nil? (tokens/pick-problem true "paul" ["paul"] nil false)))
  (is (= 409 (first (tokens/pick-problem true "paul" ["paul" "ananda"] "paul" true))))
  (is (nil? (tokens/pick-problem true "ananda" ["paul" "ananda"] "paul" true))))

(deftest a-server-is-written-only-when-it-differs
  (is (tokens/same-token? {:name "Paul" :at 5} {:name "Paul" :at 5 :key "paul"}))
  (is (not (tokens/same-token? {:name "Paul" :at 4} {:name "Paul" :at 5})))
  (is (= {:ok true :name "Paul" :at 5 :error nil :checked 9} (tokens/sync-record [200 {:name "Paul" :at 5}] 9)))
  (is (= {:ok false :name nil :at nil :error "answered 404" :checked 9} (tokens/sync-record [404 {}] 9)))
  (is (= "no route" (:error (tokens/sync-record [0 {:error "no route"}] 9)))))

(deftest the-page-sees-names-never-tokens
  (let [s (tokens/state-of true {:key "paul" :name "Paul" :by "a@b" :at 5}
                           [{:key "paul" :name "Paul" :saved-by "a@b" :saved-at 3}
                            {:key "ana" :name "Ana" :saved-by "a@b" :saved-at 4}]
                           {"paul" {:limits [{:key "seven_day" :percent 31}] :heard-at 7}}
                           [{:name "box" :label "Box"}]
                           {"box" {:ok true :name "Paul"}})]
    (is (= {:name "Paul" :by "a@b" :at 5} (:inUse s)))
    (is (= [{:name "Paul" :by "a@b" :at 3 :limits [{:key "seven_day" :percent 31}] :heardAt 7}
            {:name "Ana" :by "a@b" :at 4 :limits [] :heardAt nil}]
           (:tokens s)))
    (is (= [{:name "box" :label "Box" :ok true :current "Paul" :error nil}] (:servers s))))
  (is (= [] (:tokens (tokens/state-of false nil nil nil [] {})))))

(deftest a-servers-limits-go-to-saved-tokens-only
  (let [heard {:h1 {:limits [{:key "seven_day" :percent 40}] :at 9}
               :h2 {:limits [{:key "seven_day" :percent 5}] :at 8}
               :h3 {:limits [] :at 8}
               :h4 {:limits [{:key "five_hour" :percent 1}] :at nil}}]
    (is (= [["paul" [{:key "seven_day" :percent 40}] 9] ["ana" [{:key "five_hour" :percent 1}] 0]]
           (tokens/heard-rows {"h1" "paul" "h3" "ana" "h4" "ana"} heard)))))
