(ns claudia.slave.front-calls-test
  "The front's root taps and SSH certificate renewal end to end, against a fake runner on a unix
  socket (and a fake hub): a tap on a chat the runner does not have keeps nothing; the renewal asks
  the runner for its key, the hub for the certificate as this server, and hands it back."
  (:require [cljs.test :refer [async deftest is]]
            ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:os" :as os]
            ["node:path" :as path]
            [claudia.node.crypto :as c]
            [claudia.node.http :as io]
            [claudia.shared.protocol :as p]
            [claudia.shared.root-grant :as rg]
            [claudia.slave.rootfront :as rootfront]
            [claudia.slave.sshfront :as sshfront]))

(defn- serve!
  "Promise of a started server on listen-at (a unix socket path, or 0 for a TCP port) answering each
  POST with (answer url body headers) -> [status body]; every call kept in calls (an atom)."
  [listen-at calls answer]
  (js/Promise.
   (fn [done]
     (let [server (http/createServer
                   (fn [^js req ^js res]
                     (.then (io/read-json req 65536)
                            (fn [body]
                              (swap! calls conj [(.-url req) body])
                              (let [[status out] (answer (.-url req) body (.-headers req))]
                                (io/send-json res status out))))))]
       (if (string? listen-at)
         (.listen server listen-at #(done server))
         (.listen server 0 "127.0.0.1" #(done server)))))))

(defn- fake-runner
  "The runner's root and SSH routes: chat c1 is here, nothing else."
  [url {:keys [chat certificate]} _]
  (case url
    "/api/root/known" (if (= "c1" chat) [200 {:chat chat}] [404 {:error "No such chat."}])
    "/api/root/shown" [200 {:ok true}]
    "/api/ssh/pubkey" [200 {:publicKey "ssh-ed25519 AAAA agent"}]
    "/api/ssh/cert" (if (string? certificate) [200 {:ok true}] [400 {:error "no certificate"}])
    [404 {:error "Not found."}]))

(defn- fake-hub [url body ^js headers]
  (if (and (= url "/hub/server/ssh-cert") (= "s1" (aget headers "x-claudia-server-name"))
           (= "the-key" (aget headers "x-claudia-server-key")) (= "paul-claudia-agent" (:user body)))
    [200 {:certificate "ssh-ed25519-cert-v01@openssh.com AAAA cert"}]
    [403 {:error "no"}]))

(def ^:private owner {:own {:profile "paul"} :target {:profile "paul" :email "paul@x.y"}})

;; The key broker's grant key, pinned for these tests (README decision 79).
(def ^:private broker-key (c/ed25519-keypair))

(defn- tap!
  "Promise of {:status :body} for a tap on url through the front's handler, on a socket of its own."
  [dir profiles url body]
  (let [sock (path/join dir (str "front-" (js/Date.now) "-" (rand-int 1000000) ".sock"))
        server (http/createServer #(rootfront/handle profiles owner url %1 %2))]
    (js/Promise. (fn [done]
                   (.listen server sock
                            #(done (.then (io/socket-json sock url body 5000)
                                          (fn [a] (.close server) a))))))))

(defn- signed-grant
  "{:grant :signature} as the broker would sign for nonce, approved by email, under k's private key."
  [k nonce email]
  (let [text (p/write-json (rg/grant (rg/purpose {:nonce nonce :profile "paul" :chat "c1" :action "on"}) email (js/Date.now)))]
    {:grant text :signature (c/ed25519-sign (:private k) text)}))

(defn- signed-on!
  "Promise of the front's answer to turning root on for c1 with a grant approved by email: the
  front's nonce first, then the switch carrying the broker's signed grant."
  [dir profiles email]
  (aset js/process.env "FRONT_BROKER_GRANT_KEY" (:public broker-key))
  (.then (tap! dir profiles "/api/root/challenge" {:chat "c1" :action "on"})
         (fn [{:keys [body]}]
           (tap! dir profiles "/api/root/switch" (merge {:chat "c1" :on true} (signed-grant broker-key (:nonce body) email))))))

(deftest root-on-needs-the-owners-passkey-grant
  (async finish
         (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "front-grant-"))
               runner-sock (path/join dir "runner.sock")
               profiles [{:profile "paul" :socket runner-sock :user "paul-claudia-agent"}]]
           (aset js/process.env "FRONT_ROOT_STATE" (path/join dir "root-switches.json"))
           (aset js/process.env "FRONT_BROKER_GRANT_KEY" (:public broker-key))
           (-> (serve! runner-sock (atom []) fake-runner)
               (.then (fn [^js runner]
                        (-> (tap! dir profiles "/api/root/switch" {:chat "c1" :on true})
                            (.then (fn [a]
                                     (is (= 403 (:status a)) "no grant: refused")
                                     (signed-on! dir profiles "bob@x.y")))
                            (.then (fn [a]
                                     (is (= 403 (:status a)) "another person's passkey: refused")
                                     (.then (tap! dir profiles "/api/root/challenge" {:chat "c1" :action "on"})
                                            #(tap! dir profiles "/api/root/switch"
                                                   (merge {:chat "c1" :on true}
                                                          (signed-grant (c/ed25519-keypair) (get-in % [:body :nonce]) "paul@x.y"))))))
                            (.then (fn [a]
                                     (is (= 403 (:status a)) "signed by another key (a hacked hub): refused")
                                     (signed-on! dir profiles "paul@x.y")))
                            (.then (fn [a]
                                     (is (= {:status 200 :body {:ok true :root true}} a) "the owner's grant: on")
                                     (js-delete (.-env js/process) "FRONT_ROOT_STATE")
                                     (js-delete (.-env js/process) "FRONT_BROKER_GRANT_KEY")
                                     (.close runner)
                                     (finish))))))))))

(deftest a-tap-on-a-chat-the-runner-lacks-keeps-nothing
  (async finish
         (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "front-calls-"))
               state (path/join dir "root-switches.json")
               runner-sock (path/join dir "runner.sock")
               calls (atom [])
               profiles [{:profile "paul" :socket runner-sock :user "paul-claudia-agent"}]]
           (aset js/process.env "FRONT_ROOT_STATE" state)
           (-> (serve! runner-sock calls fake-runner)
               (.then (fn [^js runner]
                        (-> (tap! dir profiles "/api/root/switch" {:chat "elsewhere" :on true})
                            (.then (fn [a]
                                     (is (= {:status 404 :body {:error "No such chat."}} a))
                                     (is (not (fs/existsSync state)) "nothing stored")
                                     (is (= [["/api/root/known" {:chat "elsewhere"}]] @calls) "the runner shows nothing")
                                     (tap! dir profiles "/api/root/answer" {:chat "elsewhere" :ask "n" :allow true})))
                            (.then (fn [a]
                                     (is (= {:status 404 :body {:error "No such chat."}} a) "an answer too")
                                     (signed-on! dir profiles "paul@x.y")))
                            (.then (fn [a]
                                     (is (= {:status 200 :body {:ok true :root true}} a))
                                     (is (= {(keyword "paul/c1") true} (p/read-json (fs/readFileSync state "utf8"))))
                                     (tap! dir profiles "/api/root/switch" {:chat "c1" :on false})))
                            (.then (fn [a]
                                     (is (= 200 (:status a)))
                                     (js-delete (.-env js/process) "FRONT_ROOT_STATE")
                                     (js-delete (.-env js/process) "FRONT_BROKER_GRANT_KEY")
                                     (.close runner)
                                     (finish))))))))))

(deftest the-front-renews-an-agent-users-certificate
  (async finish
         (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "front-ssh-"))
               key-file (path/join dir "hub-key")
               runner-sock (path/join dir "runner.sock")
               runner-calls (atom [])
               hub-calls (atom [])]
           (fs/writeFileSync key-file "the-key\n")
           (-> (js/Promise.all #js [(serve! runner-sock runner-calls fake-runner) (serve! 0 hub-calls fake-hub)])
               (.then (fn [[^js runner ^js hub]]
                        (let [link {:url (str "http://127.0.0.1:" (.-port (.address hub)) "/") :server "s1" :key-file key-file}]
                          (-> (sshfront/renew! link {:socket runner-sock :user "paul-claudia-agent"})
                              (.then (fn [ok?]
                                       (is (true? ok?))
                                       (is (= [["/hub/server/ssh-cert" {:user "paul-claudia-agent" :publicKey "ssh-ed25519 AAAA agent"}]] @hub-calls))
                                       (is (= [["/api/ssh/pubkey" {}] ["/api/ssh/cert" {:certificate "ssh-ed25519-cert-v01@openssh.com AAAA cert\n"}]]
                                              @runner-calls))
                                       (sshfront/renew! (assoc link :server "s2") {:socket runner-sock :user "paul-claudia-agent"})))
                              (.then (fn [ok?]
                                       (is (false? ok?) "the hub's no: nothing handed to the runner")
                                       (is (= 3 (count @runner-calls)) "only the key was asked again")
                                       (.close runner)
                                       (.close hub)
                                       (finish)))))))))))
