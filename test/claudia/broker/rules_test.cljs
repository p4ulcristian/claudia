(ns claudia.broker.rules-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.broker.main :as main]
            [claudia.broker.rules :as rules]
            [claudia.hub.broker :as hub-broker]))

(def key44 "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")

(deftest only-the-broker-vault-is-read
  (is (rules/vault-ref? "Claudia" "op://Claudia/GitHub/token"))
  (is (rules/vault-ref? "Claudia" "op://Claudia/Cloudflare/api/token"))
  (is (not (rules/vault-ref? "Claudia" "op://Private/GitHub/token")))
  (is (not (rules/vault-ref? "Claudia" "op://Claudia/GitHub")))
  (is (not (rules/vault-ref? "Claudia" "op://Claudia//token")))
  (is (not (rules/vault-ref? "Claudia" "op://Claudia/GitHub/token?attribute=otp")))
  (is (not (rules/vault-ref? "Claudia" "op://ClaudiaX/GitHub/token")))
  (is (not (rules/vault-ref? "" "op:///GitHub/token"))))

(deftest a-purpose-is-checked
  (is (= {:kind "read" :ref "op://Claudia/a/b" :pubkey key44 :request "r1"}
         (rules/purpose "Claudia" {:kind "read" :ref "op://Claudia/a/b" :pubkey key44 :request "r1" :extra 1})))
  (is (:error (rules/purpose "Claudia" {:kind "read" :ref "op://Claudia/a/b" :pubkey "short" :request "r1"})))
  (is (:error (rules/purpose "Claudia" {:kind "read" :ref "op://Claudia/a/b" :pubkey key44})))
  (is (= {:kind "add-passkey"} (rules/purpose "Claudia" {:kind "add-passkey"})))
  (is (= {:kind "remove-passkey" :id "x"} (rules/purpose "Claudia" {:kind "remove-passkey" :id "x"})))
  (is (:error (rules/purpose "Claudia" {:kind "remove-passkey"})))
  (is (:error (rules/purpose "Claudia" {:kind "sudo"}))))

(deftest a-challenge-is-used-once-by-its-person-for-its-kind
  (let [p (rules/open-challenge {} "c1" {:email "a@x" :challenge "ch" :purpose {:kind "read"}} 1000)
        exp (get-in p ["c1" :expires])]
    (is (= (+ 1000 rules/challenge-ms) exp))
    (is (= [(get p "c1") {}] (rules/take-challenge p "c1" "a@x" "read" 2000)))
    (is (= [nil {}] (rules/take-challenge p "c1" "b@x" "read" 2000)))
    (is (= [nil {}] (rules/take-challenge p "c1" "a@x" "add-passkey" 2000)))
    (is (= [nil {}] (rules/take-challenge p "c1" "a@x" "read" (inc exp))))
    (is (= [nil p] (rules/take-challenge p "nope" "a@x" "read" 2000)))))

(deftest expired-challenges-are-dropped-when-one-opens
  (let [p (rules/open-challenge {"old" {:expires 5}} "new" {:email "a@x"} 10)]
    (is (= ["new"] (keys p)))))

(deftest an-enrollment-code-holds-once-and-briefly
  (is (= :none (rules/code-check nil 0 false)))
  (is (= :ok (rules/code-check {:expires 100 :tries 0} 50 true)))
  (is (= :wrong (rules/code-check {:expires 100 :tries 0} 50 false)))
  (is (= :spent (rules/code-check {:expires 100 :tries 0} 101 true)))
  (is (= :spent (rules/code-check {:expires 100 :tries rules/code-tries} 50 true))))

(deftest passkeys-show-no-key-material
  (is (= {:id "i" :name "n" :createdAt 1 :usedAt nil}
         (rules/passkey-view {:id "i" :name "n" :createdAt 1 :usedAt nil :publicKey "pk" :counter 3 :email "a@x"})))
  (is (= "Passkey" (rules/passkey-name "  ")))
  (is (= 60 (count (rules/passkey-name (apply str (repeat 80 "x")))))))

(deftest emails-come-lower-case-from-the-hub
  (is (rules/email? "a@b.c"))
  (is (not (rules/email? "A@b.c")))
  (is (not (rules/email? nil)))
  (is (not (rules/email? "a b@c"))))

(deftest the-hub-passes-only-broker-paths-on
  (is (= "/passkeys/list" (hub-broker/target "GET" "/hub/passkeys")))
  (is (= "/vault/read" (hub-broker/target "POST" "/hub/vault/read")))
  (is (nil? (hub-broker/target "GET" "/hub/vault/read")))
  (is (hub-broker/call? "/hub/approve/options"))
  (is (not (hub-broker/call? "/hub/vault/other"))))

(deftest settings-take-the-relying-party-from-the-origin
  (let [env-of (fn [m] (fn [k d] (get m k d)))]
    (is (= "hub.example" (:rp-id (main/settings (env-of {"BROKER_ORIGIN" "https://hub.example"})))))
    (is (= "other.example" (:rp-id (main/settings (env-of {"BROKER_ORIGIN" "https://hub.example" "BROKER_RP_ID" "other.example"})))))
    (is (nil? (main/origin-host "not a url")))
    (is (= ["BROKER_SOCKET is not set."] (main/problems {:rp-id "h" :origin "https://h"})))))
