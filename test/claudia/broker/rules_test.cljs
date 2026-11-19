(ns claudia.broker.rules-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.broker.main :as main]
            [claudia.broker.rules :as rules]
            [claudia.broker.vault :as vault]
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
  (is (= {:kind "read" :ref "op://Claudia/a/b" :pubkey key44 :request "r1" :chat "home/c1"}
         (rules/purpose "Claudia" {:kind "read" :ref "op://Claudia/a/b" :pubkey key44 :request "r1" :chat "home/c1" :extra 1})))
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
  (is (= "/keys/mine" (hub-broker/target "GET" "/hub/keys/mine")))
  (is (nil? (hub-broker/target "POST" "/hub/keys/mine")))
  (is (not (hub-broker/call? "/hub/vault/other"))))

(deftest settings-take-the-relying-party-from-the-origin
  (let [env-of (fn [m] (fn [k d] (get m k d)))]
    (is (= "hub.example" (:rp-id (main/settings (env-of {"BROKER_ORIGIN" "https://hub.example"})))))
    (is (= "other.example" (:rp-id (main/settings (env-of {"BROKER_ORIGIN" "https://hub.example" "BROKER_RP_ID" "other.example"})))))
    (is (nil? (main/origin-host "not a url")))
    (is (= ["BROKER_SOCKET is not set."] (main/problems {:rp-id "h" :origin "https://h"})))))

(deftest the-version-named-to-1password-is-short
  (is (= "48908a5" (rules/integration-version "48908a5635dc0a73069e1e6980728a7e505bf116")))
  (is (= "abc" (rules/integration-version "abc")))
  (is (= "dev" (rules/integration-version nil))))

(deftest key-access-rules
  (is (= "GitHub" (rules/ref-item "Claudia" "op://Claudia/GitHub/token")))
  (is (nil? (rules/ref-item "Claudia" "op://Other/GitHub/token")))
  (let [items [{:id "i1" :title "GitHub"} {:id "i2" :title "i1"}]]
    (is (= "i1" (:id (rules/find-item items "i1"))))
    (is (= "i1" (:id (rules/find-item items "GitHub"))))
    (is (nil? (rules/find-item items "nope"))))
  (is (rules/may-use? true [] "i1"))
  (is (rules/may-use? false ["i1"] "i1"))
  (is (not (rules/may-use? false ["i2"] "i1"))))

(deftest grant-batches-are-checked
  (is (= [{:item "i1" :email "p@x" :on true}] (rules/grant-changes [{:item "i1" :email "p@x" :on true :x 1}])))
  (is (nil? (rules/grant-changes [])))
  (is (nil? (rules/grant-changes [{:item "i1" :email "P@x" :on true}])))
  (is (nil? (rules/grant-changes [{:item "i1" :email "p@x" :on "yes"}])))
  (is (= "grants" (:kind (rules/purpose "Claudia" {:kind "grants" :changes [{:item "i" :email "p@x" :on false}]})))))

(deftest one-hour-per-chat-per-key
  (let [k (rules/standing-key "a@x" "home/c1" "i1")
        s (rules/add-standing {[:old] 5} k 1000)]
    (is (= {k (+ 1000 rules/standing-ms)} s))
    (is (rules/standing? s k 2000))
    (is (rules/standing? s k (+ 1000 rules/standing-ms)))
    (is (not (rules/standing? s k (+ 1001 rules/standing-ms))))
    (is (not (rules/standing? s (rules/standing-key "a@x" "home/c2" "i1") 2000)))
    (is (not (rules/standing? s (rules/standing-key "a@x" "home/c1" "i2") 2000)))))

(deftest a-person-sees-only-the-keys-they-may-use
  (let [items [{:id "i1" :title "GitHub"} {:id "i2" :title "Vultr"}]]
    (is (= items (rules/usable-items true [] items)))
    (is (= [{:id "i2" :title "Vultr"}] (rules/usable-items false ["i2" "gone"] items)))
    (is (= [] (rules/usable-items false [] items)))))

(deftest labels-never-keep-a-value
  (let [l (rules/item-labels {:fields [{:id "f1" :title "token" :value "s3cret" :fieldType "Concealed" :details {:code "1"}}]
                              :sections [{:id "s1" :title "API" :value "x"}]
                              :notes "hidden"})]
    (is (= {:fields [{:id "f1" :title "token"}] :sections [{:id "s1" :title "API"}]} l))
    (is (not (re-find #"s3cret|hidden" (pr-str l))))))

(deftest references-are-exact
  (is (= "GitHub" (rules/ref-part "GitHub" "i1" true)))
  (is (= "i1" (rules/ref-part "GitHub" "i1" false)) "a clashing title gives the id")
  (is (= "i1" (rules/ref-part "a/b" "i1" true)))
  (is (= "i1" (rules/ref-part " pad" "i1" true)))
  (is (= "i1" (rules/ref-part "" "i1" true)))
  (is (= "i1" (rules/ref-part "private key" "i1" true)) "1Password takes no space in a reference")
  (is (= "i1" (rules/ref-part "break-glass (servers)" "i1" true)) "nor brackets")
  (is (= "break-glass_2.key" (rules/ref-part "break-glass_2.key" "i1" true)))
  (let [all [{:id "i1" :title "GitHub"} {:id "i2" :title "Hetzner"} {:id "i3" :title "hetzner"}]
        usable [{:id "i1" :title "GitHub"} {:id "i2" :title "Hetzner"}]
        labels {"i1" {:fields [{:id "username" :title "username" :value "me"}
                               {:id "f2" :title "token" :section "s1" :value "ghp_x"}
                               {:id "f3" :title "Token" :section "s2"}
                               {:id "f4" :title "note" :section "s3"}]
                      :sections [{:id "s1" :title "API"} {:id "s2" :title "CI/CD"} {:id "s3" :title ""}]}}
        out (rules/key-list "Claudia" all usable labels)]
    (is (= {:vault "Claudia"
            :items [{:title "GitHub"
                     :fields [{:label "username" :ref "op://Claudia/GitHub/username"}
                              {:label "token" :section "API" :ref "op://Claudia/GitHub/API/f2"}
                              {:label "Token" :section "CI/CD" :ref "op://Claudia/GitHub/s2/f3"}
                              {:label "note" :ref "op://Claudia/GitHub/note"}]}
                    {:title "Hetzner" :fields []}]}
           out))
    (is (not (re-find #"ghp_x|\"me\"" (pr-str out))))
    (is (= "op://Claudia/i2/x" (get-in (rules/key-list "Claudia" all usable {"i2" {:fields [{:id "x" :title "x"}]}}) [:items 1 :fields 0 :ref]))
        "an item whose title another item shares is named by its id")))

(deftest an-sdk-item-gives-labels-only
  (let [answer #js {:individualResponses
                    #js [#js {:content #js {:id "i1" :title "GitHub" :notes "n0tes"
                                            :fields #js [#js {:id "f1" :title "token" :sectionId "s1" :value "ghp_s3cret"
                                                              :fieldType "Concealed" :details #js {:content #js {:code "123456"}}}
                                                         #js {:id "f2" :title "user" :sectionId "" :value "me@x"}]
                                            :sections #js [#js {:id "s1" :title "API"}]}}
                         #js {:error #js {:type "itemNotFound"}}]}
        out (#'vault/fetched-labels answer {"i1" "2026-11-19"})]
    (is (= {"i1" {:updated "2026-11-19"
                  :labels {:fields [{:id "f1" :title "token" :section "s1"} {:id "f2" :title "user" :section nil}]
                           :sections [{:id "s1" :title "API"}]}}}
           out))
    (is (not (re-find #"s3cret|me@x|123456|n0tes" (pr-str out))))))
