(ns claudia.shared.root-grant-test
  "Root grants (README decision 79) on plain data: what a purpose is, what the broker signs, and why
  the front lets a tap through or not."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.broker.rules :as broker-rules]
            [claudia.node.crypto :as c]
            [claudia.shared.root-grant :as rg]))

(def ^:private nonce "0123456789abcdef0123456789abcdef")

(deftest a-root-purpose
  (is (= {:kind "root" :nonce nonce :profile "paul" :chat "c1" :action "on"}
         (rg/purpose {:nonce nonce :profile "paul" :chat "c1" :action "on" :ask "ignored"}))
      "on carries no ask")
  (is (= "a1" (:ask (rg/purpose {:nonce nonce :profile "paul" :chat "c1" :action "allow" :ask "a1"}))))
  (is (:error (rg/purpose {:nonce "short" :profile "paul" :chat "c1" :action "on"})))
  (is (:error (rg/purpose {:nonce nonce :profile "paul" :chat "c1" :action "shell"})))
  (is (:error (rg/purpose {:nonce nonce :profile "paul" :chat "c1" :action "allow"})) "allow needs its ask")
  (is (= "root" (:kind (broker-rules/purpose "Claudia" {:kind "root" :nonce nonce :profile "p" :chat "c" :action "on"})))
      "the broker takes root as an approval kind"))

(deftest the-front-checks-a-grant
  (let [now 1000
        tap {:profile "paul" :chat "c1" :action "on" :ask nil}
        expected (get (rg/open-nonce {} nonce tap now) nonce)
        g (rg/grant (rg/purpose (assoc tap :nonce nonce)) "ana@x.y" now)]
    (is (nil? (rg/problem g expected tap "Ana@X.Y" (+ now 1000))) "the owner's passkey, in time: through")
    (testing "refused"
      (is (rg/problem g nil tap "ana@x.y" now) "no open nonce (used, or never this front's)")
      (is (rg/problem g expected tap "bob@x.y" now) "another person's passkey")
      (is (rg/problem g expected tap nil now) "no owner known")
      (is (rg/problem g expected (assoc tap :chat "c2") "ana@x.y" now) "another chat")
      (is (rg/problem g expected (assoc tap :action "allow" :ask "a1") "ana@x.y" now) "an on is no Allow")
      (is (rg/problem g expected tap "ana@x.y" (+ now rg/grant-ms 1)) "too late")
      (is (rg/problem (assoc g :kind "read") expected tap "ana@x.y" now) "not a root grant"))))

(deftest an-allow-grant-names-its-command
  (let [tap {:profile "paul" :chat "c1" :action "allow" :ask "a1"}
        expected (get (rg/open-nonce {} nonce tap 0) nonce)
        g (rg/grant (rg/purpose (assoc tap :nonce nonce)) "ana@x.y" 0)]
    (is (nil? (rg/problem g expected tap "ana@x.y" 1)))
    (is (rg/problem g expected (assoc tap :ask "a2") "ana@x.y" 1) "another card's command")))

(deftest open-nonces-drop-ended-ones
  (let [n (rg/open-nonce {"old" {:expires 5}} nonce {:profile "p" :chat "c" :action "on"} 10)]
    (is (= [nonce] (keys n)))
    (is (= (+ 10 rg/grant-ms) (:expires (get n nonce))))))

(deftest ed25519-signs-and-verifies
  (let [{:keys [private public]} (c/ed25519-keypair)
        sig (c/ed25519-sign private "grant")]
    (is (= public (c/ed25519-public private)))
    (is (c/ed25519-valid? public "grant" sig))
    (is (not (c/ed25519-valid? public "grant2" sig)) "another text")
    (is (not (c/ed25519-valid? (:public (c/ed25519-keypair)) "grant" sig)) "another key")
    (is (not (c/ed25519-valid? "not a key" "grant" sig)) "a malformed key is no")))
