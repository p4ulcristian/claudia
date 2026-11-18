(ns claudia.hub.ssh-ca-test
  (:require ["sshpk" :as sshpk]
            [cljs.test :refer [deftest is testing]]
            [claudia.hub.ssh-ca :as ssh-ca]))

(def ^:private now 1794984000000)

(deftest the-window-starts-five-minutes-back-and-lasts-an-hour
  (is (= {:from (- now (* 5 60000)) :until (+ now (* 60 60000))} (ssh-ca/validity now))))

(deftest the-key-id-names-server-user-and-time
  (is (= "box/paul-claudia-agent/2026-11-18T06:40:00.000Z" (ssh-ca/key-id "box" "paul-claudia-agent" now))))

(def ^:private fine {:server-name "box" :key-ok? true :user "paul-claudia-agent" :email "p@x" :may? true})

(deftest a-request-is-signed-only-for-a-profile-on-that-server
  (is (nil? (ssh-ca/request-problem fine)))
  (is (= "no server named" (ssh-ca/request-problem (assoc fine :server-name ""))))
  (is (= "not that server's key" (ssh-ca/request-problem (assoc fine :key-ok? false))))
  (is (= "user must be <profile>-claudia-agent" (ssh-ca/request-problem (assoc fine :user "root"))))
  (is (= "no person has that profile" (ssh-ca/request-problem (assoc fine :email nil))))
  (is (= "that person has no profile on that server" (ssh-ca/request-problem (assoc fine :may? false)))))

(defn- ed25519 [] (sshpk/generatePrivateKey "ed25519"))
(defn- ecdsa [] (sshpk/generatePrivateKey "ecdsa" #js {:curve "nistp256"}))

(deftest the-authority-must-be-an-ed25519-key
  (let [a (ssh-ca/read-authority (.toString (ed25519) "ssh"))]
    (is (some? (:key a)))
    (is (re-matches #"^ssh-ed25519 \S+ claudia-hub-ca\n$" (:public a))))
  (is (re-find #"must be ed25519" (:problem (ssh-ca/read-authority (.toString (ecdsa) "ssh")))))
  (is (re-find #"cannot be read" (:problem (ssh-ca/read-authority "not a key")))))

(deftest only-ed25519-keys-are-certified
  (is (= "ed25519" (.-type (ssh-ca/subject-key (.toString (.toPublic (ed25519)) "ssh")))))
  (is (thrown? js/Error (ssh-ca/subject-key (.toString (.toPublic (ecdsa)) "ssh"))))
  (is (thrown? js/Error (ssh-ca/subject-key "ssh-ed25519 broken"))))

(deftest the-certificate-carries-exactly-what-was-asked
  (let [ca (ed25519)
        line (ssh-ca/certificate ca (.toPublic (ed25519)) {:server "box" :user "paul-claudia-agent" :now now})
        ^js cert (sshpk/parseCertificate line "openssh")
        ^js sig (.. cert -signatures -openssh)]
    (is (re-matches #"^ssh-ed25519-cert-v01@openssh\.com \S+$" line))
    (is (.isSignedByKey cert (.toPublic ca)))
    (is (= [["user" "paul-claudia-agent"]] (mapv (juxt #(.-type %) #(.-uid %)) (.-subjects cert))))
    (is (= (- now (* 5 60000)) (.getTime (.-validFrom cert))))
    (is (= (+ now (* 60 60000)) (.getTime (.-validUntil cert))))
    (is (= "box/paul-claudia-agent/2026-11-18T06:40:00.000Z" (.-keyId sig)))
    (testing "permit-pty only: no forwarding, no critical options"
      (is (= [["permit-pty" false]] (mapv (juxt #(.-name %) #(boolean (.-critical %))) (.-exts sig)))))))
