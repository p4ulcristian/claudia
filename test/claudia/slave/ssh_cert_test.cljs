(ns claudia.slave.ssh-cert-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [claudia.slave.front :as front]
            [claudia.slave.hub-call :as hub-call]
            [claudia.slave.sshfront :as sshfront]
            [claudia.slave.ssh-cert :as ssh-cert]))

(def ^:private block (ssh-cert/config-block "paul-claudia-agent"))

(deftest the-block-points-ssh-at-the-key-the-certificate-and-the-same-user
  (is (= [ssh-cert/block-begin "Host *" "    IdentityFile ~/.ssh/claudia_agent"
          "    CertificateFile ~/.ssh/claudia_agent-cert.pub" "    IdentitiesOnly yes"
          "    User paul-claudia-agent" ssh-cert/block-end]
         (str/split-lines block))))

(deftest the-block-goes-in-once-and-the-rest-stays
  (testing "an empty or missing config gets just the block"
    (is (= (str block "\n") (ssh-cert/merge-block "" block)))
    (is (= (str block "\n") (ssh-cert/merge-block nil block))))
  (testing "after other lines, set apart by an empty line"
    (is (= (str "Host a\n    Port 2\n\n" block "\n") (ssh-cert/merge-block "Host a\n    Port 2\n" block)))
    (is (= (str "Host a\n\n" block "\n") (ssh-cert/merge-block "Host a" block))))
  (testing "a block already there is replaced in place, the lines around it kept"
    (let [old (str "Host a\n\n" (ssh-cert/config-block "old-claudia-agent") "\nHost b\n")]
      (is (= (str "Host a\n\n" block "\nHost b\n") (ssh-cert/merge-block old block)))))
  (testing "merging again changes nothing"
    (let [once (ssh-cert/merge-block "Host a\n" block)]
      (is (= once (ssh-cert/merge-block once block))))))

(deftest the-request-names-the-user-and-its-key
  (is (= {:user "paul-claudia-agent" :publicKey "ssh-ed25519 AAAA paul"}
         (ssh-cert/cert-request "paul-claudia-agent" "ssh-ed25519 AAAA paul\n"))))

(deftest only-a-certificate-is-taken-from-the-answer
  (is (= {:certificate "ssh-ed25519-cert-v01@openssh.com AAAA\n"}
         (ssh-cert/certificate-of {:ok true :status 200 :text "{\"certificate\":\"ssh-ed25519-cert-v01@openssh.com AAAA\"}"})))
  (is (= {:problem "hub answered 403: not that server's key"}
         (ssh-cert/certificate-of {:ok false :status 403 :text "{\"error\":\"not that server's key\"}"})))
  (is (= {:problem "hub answered 502: Bad Gateway"} (ssh-cert/certificate-of {:ok false :status 502 :text "Bad Gateway"})))
  (is (:problem (ssh-cert/certificate-of {:ok true :status 200 :text "{\"certificate\":\"ssh-rsa AAAA\"}"}))))

(deftest renewal-every-45-minutes-retry-in-5
  (is (= (* 45 60000) (ssh-cert/next-delay true)))
  (is (= (* 5 60000) (ssh-cert/next-delay false))))

(deftest the-hub-link-needs-all-three-settings
  (is (= {:url "u" :server "s" :key-file "k"}
         (hub-call/settings #js {:CLAUDIA_HUB_URL "u" :CLAUDIA_SERVER_NAME "s" :CLAUDIA_HUB_KEY_FILE "k"})))
  (is (nil? (hub-call/settings #js {:CLAUDIA_HUB_URL "u" :CLAUDIA_SERVER_NAME "s"}))))

(deftest the-front-calls-the-hub-with-its-own-key
  (is (= {:url "u" :server "s" :key-file "f"}
         (hub-call/front-settings #js {:CLAUDIA_HUB_URL "u" :CLAUDIA_SERVER_NAME "s" :FRONT_HUB_KEY_FILE "f"
                                       :CLAUDIA_HUB_KEY_FILE "k"})))
  (is (nil? (hub-call/front-settings #js {:CLAUDIA_HUB_URL "u" :CLAUDIA_SERVER_NAME "s" :CLAUDIA_HUB_KEY_FILE "k"}))
      "never a runner's key file"))

(deftest a-certificate-is-one-ed25519-certificate-line
  (is (= "ssh-ed25519-cert-v01@openssh.com AAAA c\n" (ssh-cert/cert-line " ssh-ed25519-cert-v01@openssh.com AAAA c\n")))
  (is (nil? (ssh-cert/cert-line "ssh-ed25519-cert-v01@openssh.com AAAA\nHost evil")) "one line only")
  (is (nil? (ssh-cert/cert-line "ssh-rsa AAAA")))
  (is (nil? (ssh-cert/cert-line nil))))

(deftest the-front-renews-only-agent-users-from-its-profiles-file
  (let [ps (front/parse-profiles "paul p@x /s/p user=paul-claudia-agent\nann a@x /s/a\nbob b@x /s/b user=bob\n")]
    (is (= ["paul"] (map :profile (sshfront/agent-profiles ps))))
    (is (= "paul-claudia-agent" (:user (first (sshfront/agent-profiles ps))))))
  (is (= "claudia-x" (front/profile-user "x" [])))
  (is (= "u1" (front/profile-user "x" ["note" "user=u1" "user=u2"]))))

(deftest the-runners-answers-to-the-renewal
  (is (= {:public-key "ssh-ed25519 AAAA a"} (sshfront/public-key-of {:status 200 :body {:publicKey "ssh-ed25519 AAAA a"}})))
  (is (= {:problem "public key: the runner answered 409: This runner does not run as an agent user."}
         (sshfront/public-key-of {:status 409 :body {:error "This runner does not run as an agent user."}})))
  (is (= {:problem "public key: the runner sent none"} (sshfront/public-key-of {:status 200 :body {}})))
  (is (nil? (sshfront/runner-problem "certificate" {:status 200 :body {:ok true}})))
  (is (= "certificate: the runner answered 400: no reason" (sshfront/runner-problem "certificate" {:status 400 :body {}}))))
