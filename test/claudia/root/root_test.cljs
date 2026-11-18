(ns claudia.root.root-test
  "The root helper's and the sudo shim's pure parts (README decision 52), and the runner's and
  front's side of root, on plain data."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [claudia.root.args :as args]
            [claudia.root.frames :as frames]
            [claudia.root.rules :as rules]
            [claudia.shared.protocol :as p]
            [claudia.slave.chats.answers :as answers]
            [claudia.slave.chats.view :as view]
            [claudia.slave.front :as front]
            [claudia.slave.rootgate :as gate]
            [claudia.slave.runner :as runner]
            [claudia.web.views.pure.switches :as switches]))

(def ^:private list-text
  "# services\nsystemctl restart nginx\n  journalctl -u nginx -n 100  \n\n#systemctl stop nginx\nss -tlnp\n")

(deftest the-no-tap-list-is-one-command-per-line
  (is (= #{"systemctl restart nginx" "journalctl -u nginx -n 100" "ss -tlnp"} (rules/no-tap-list list-text)))
  (is (= #{} (rules/no-tap-list nil))))

(deftest no-tap-matches-exactly
  (let [l (rules/no-tap-list list-text)]
    (is (true? (rules/no-tap? l ["systemctl" "restart" "nginx"])))
    (is (true? (rules/no-tap? l ["ss" "-tlnp"])))
    (testing "no globbing, no prefixes, no extra arguments"
      (is (false? (rules/no-tap? l ["systemctl" "restart" "nginx" "postgres"])))
      (is (false? (rules/no-tap? l ["systemctl" "restart" "ngin*"])))
      (is (false? (rules/no-tap? l ["systemctl" "restart"]))))
    (testing "a comment is no command"
      (is (false? (rules/no-tap? l ["#systemctl" "stop" "nginx"]))))
    (testing "an argument with white space never matches, even when the joined line does"
      (is (false? (rules/no-tap? l ["systemctl restart nginx"])))
      (is (false? (rules/no-tap? l ["systemctl" "restart nginx"])))
      (is (false? (rules/no-tap? l ["ss" "" "-tlnp"]))))
    (is (false? (rules/no-tap? l [])))))

(deftest agent-users-and-their-profiles
  (is (= "paul" (rules/profile-of "paul-claudia-agent")))
  (is (= "a-b" (rules/profile-of "a-b-claudia-agent")))
  (is (nil? (rules/profile-of "paul")))
  (is (nil? (rules/profile-of "break-glass")))
  (is (nil? (rules/profile-of "-claudia-agent"))))

(deftest only-closed-agent-folders-are-served
  (is (true? (rules/serve-dir? "paul-claudia-agent" (js/parseInt "40750" 8))))
  (is (false? (rules/serve-dir? "paul-claudia-agent" (js/parseInt "40755" 8))) "others may enter")
  (is (false? (rules/serve-dir? "paul" (js/parseInt "40750" 8))) "not an agent user"))

(deftest requests-are-checked
  (is (nil? (rules/request-problem {:argv ["ls"] :cwd "/tmp" :chatKey "k" :env {:LANG "C"}})))
  (is (nil? (rules/request-problem {:argv ["ls"]})))
  (is (string? (rules/request-problem {:argv []})))
  (is (string? (rules/request-problem {:argv "ls -l"})))
  (is (string? (rules/request-problem {:argv ["ls" 3]})))
  (is (string? (rules/request-problem {:argv ["" "x"]})))
  (is (string? (rules/request-problem {:argv ["ls\u0000"]})))
  (is (string? (rules/request-problem {:argv ["ls"] :cwd 3})))
  (is (string? (rules/request-problem {:argv ["ls"] :chatKey (apply str (repeat 201 "k"))})))
  (is (string? (rules/request-problem {:argv ["ls"] :env ["LANG"]}))))

(deftest a-root-command-keeps-only-a-few-variables
  (let [e (rules/child-env "paul-claudia-agent" {:LANG "C.UTF-8" :PATH "/evil" :LD_PRELOAD "/x.so" :TERM 3})]
    (is (= "C.UTF-8" (get e "LANG")))
    (is (= rules/root-path (get e "PATH")))
    (is (nil? (get e "LD_PRELOAD")))
    (is (nil? (get e "TERM")))
    (is (= "paul-claudia-agent" (get e "SUDO_USER")))
    (is (= "/root" (get e "HOME")))))

(deftest only-an-exact-yes-from-the-front-runs
  (is (= {:allow true :via "switch"} (rules/verdict {:allow true :via "switch" :chat "c"})))
  (is (= {:allow true :via "allow"} (rules/verdict {:allow true :via "allow"})))
  (is (false? (:allow (rules/verdict {:allow "true" :via "allow"}))))
  (is (false? (:allow (rules/verdict {:allow true}))))
  (is (false? (:allow (rules/verdict {:allow true :via "maybe"}))))
  (is (false? (:allow (rules/verdict nil))))
  (is (false? (:allow (rules/verdict [true]))))
  (is (= "Nobody answered." (:why (rules/verdict {:allow false :why "Nobody answered."}))))
  (is (= "The person did not allow it." (:why (rules/verdict {:allow false})))))

(deftest exit-codes-and-the-log
  (is (= 0 (rules/exit-code 0 nil)))
  (is (= 3 (rules/exit-code 3 nil)))
  (is (= 143 (rules/exit-code nil 15)))
  (is (= 1 (rules/exit-code nil nil)))
  (is (= "key:abcdefgh" (rules/key-tag "abcdefghijk")))
  (is (= "no key" (rules/key-tag nil)))
  (let [l (rules/log-line {:user "u" :chat "c" :argv ["ls"] :decision "no-tap" :exit 0})]
    (is (str/starts-with? l "root "))
    (is (= {:user "u" :chat "c" :argv ["ls"] :decision "no-tap" :exit 0} (p/read-json (subs l 5)))))
  (is (not (str/includes? (rules/log-line {:user "u" :chat "c" :argv ["x"] :decision "refused: no"}) "exit"))))

(deftest frames-go-as-lines-and-carry-bytes
  (is (= "{\"exit\":2}\n" (frames/line {:exit 2})))
  (is (= {:exit 2} (frames/read-frame "{\"exit\":2}")))
  (is (nil? (frames/read-frame "[1]")))
  (is (nil? (frames/read-frame "nope")))
  (let [raw (js/Buffer.from #js [0 255 10 13 128])
        [f :as fs] (frames/data-frames :out raw)]
    (is (= 1 (count fs)))
    (is (true? (.equals raw (frames/frame-bytes (frames/read-frame (str/trim (frames/line f))) :out)))))
  (let [big (js/Buffer.alloc (+ frames/chunk-bytes 10) 7)
        fs (frames/data-frames :err big)]
    (is (= 2 (count fs)))
    (is (= (.-length big) (reduce + (map #(.-length (frames/frame-bytes % :err)) fs)))))
  (is (= [] (frames/data-frames :in (js/Buffer.alloc 0))))
  (is (nil? (frames/frame-bytes {:out 3} :out))))

(deftest sudo-takes-only-n-and-E
  (is (= {:argv ["systemctl" "restart" "nginx"]} (args/sudo-args ["systemctl" "restart" "nginx"])))
  (is (= {:argv ["ls" "-la"]} (args/sudo-args ["-n" "-E" "ls" "-la"])))
  (is (= {:argv ["ls"]} (args/sudo-args ["-nE" "ls"])))
  (is (= {:argv ["-weird"]} (args/sudo-args ["--" "-weird"])))
  (is (= {:argv ["-"]} (args/sudo-args ["-"])))
  (doseq [flag ["-i" "-s" "-u" "-su" "--login" "-nu"]]
    (is (str/includes? (:error (args/sudo-args [flag "root"])) args/no-shells) flag))
  (is (= args/usage (:error (args/sudo-args []))))
  (is (= args/usage (:error (args/sudo-args ["-n"]))))
  (is (= args/usage (:error (args/sudo-args ["--"])))))

(deftest the-run-as-root-card
  (is (= "systemctl restart nginx" (answers/command-text ["systemctl" "restart" "nginx"])))
  (is (= "sh -c 'echo hi > /etc/x' 'it'\\''s'" (answers/command-text ["sh" "-c" "echo hi > /etc/x" "it's"])))
  (let [a (answers/root-ask "a1" ["ls" "/root"] "/srv" 99)]
    (is (= {:id "a1" :kind "permit" :root true :tool "Run as root" :title "Run as root" :argv ["ls" "/root"] :cwd "/srv"
            :description "ls /root" :detail "In /srv\n\nls /root" :expires 99} a)))
  (is (= "In /\n\nls" (:detail (answers/root-ask "a" ["ls"] nil 1)))))

(deftest the-runner-only-shows-root
  (is (= :root (runner/request-op "POST" "/api/root/card" nil nil false)))
  (is (= :root (runner/request-op "POST" "/api/root/chat" nil nil false)))
  (is (nil? (runner/request-op "GET" "/api/root/chat" nil nil false)))
  (is (= [404 {:error "Not found."}] (runner/root-route "ask" {:chatKey "k" :argv ["ls"]})) "no route answers allow")
  (is (= [404 {:error "No such chat."}] (runner/root-route "known" {})) "no chat named")
  (is (= :ssh (runner/request-op "POST" "/api/ssh/cert" nil nil false)))
  (is (nil? (runner/request-op "GET" "/api/ssh/pubkey" nil nil false))))

(deftest the-row-says-the-root-switch
  (is (true? (:root (view/row {:id "c" :root true} {} 0 0))))
  (is (false? (:root (view/row {:id "c"} {} 0 0)))))

(deftest the-front-keeps-root-calls-to-itself
  (is (= :front (front/root-call "POST" "/api/root/answer")))
  (is (= :front (front/root-call "POST" "/api/root/switch")))
  (is (= :refuse (front/root-call "GET" "/api/root/switch")))
  (is (= :refuse (front/root-call "POST" "/api/root/card")) "a runner's root route is never passed on")
  (is (= :refuse (front/root-call "POST" "/api/root/chat")))
  (is (nil? (front/root-call "POST" "/api/chats")))
  (is (= :refuse (front/root-call "POST" "/api/ssh/cert")) "the SSH certificate routes are the front's own")
  (is (= :refuse (front/root-call "POST" "/api/ssh/pubkey")))
  (is (not (some #(= "X-Claudia-Owner" (first %))
                 (front/runner-headers #js ["X-Claudia-Owner" "1"] {:own {:profile "p"} :target {:profile "p"}})))
      "the runner hears of no owner"))

(def ^:private own {:own {:profile "paul"} :target {:profile "paul"}})
(def ^:private writer {:own {:profile "peter"} :target {:profile "paul"}})
(def ^:private pending {:profile "paul" :chat "c1" :argv ["systemctl" "restart" "x"] :cwd "/srv"})

(deftest only-the-owner-is-the-owner
  (is (true? (gate/owner? own)))
  (is (false? (gate/owner? writer)) "a write tick")
  (is (false? (gate/owner? {:own nil :target {:profile "paul"} :read-only true})) "a viewer")
  (is (false? (gate/owner? {}))))

(deftest an-allow-counts-only-for-the-command-the-front-asked
  (let [allow {:chat "c1" :ask "n" :allow true :argv ["systemctl" "restart" "x"] :cwd "/srv"}]
    (is (nil? (gate/answer-problem pending allow own)))
    (is (= "Only the chat's owner may give it root." (gate/answer-problem pending allow writer)))
    (is (= "That root ask is no longer open." (gate/answer-problem nil allow own)))
    (is (= "That root ask is another chat's." (gate/answer-problem pending (assoc allow :chat "c2") own)))
    (is (= "That root ask is another profile's."
           (gate/answer-problem (assoc pending :profile "peter") allow own)))
    (is (= "The command shown is not the one asked for."
           (gate/answer-problem pending (assoc allow :argv ["systemctl" "restart" "y"]) own)))
    (is (= "The folder shown is not the one asked for." (gate/answer-problem pending (assoc allow :cwd "/") own)))
    (testing "a Deny needs no matching command"
      (is (nil? (gate/answer-problem pending {:chat "c1" :ask "n" :allow false} own))))))

(deftest the-front-holds-the-switches
  (let [s (gate/with-switch {} "paul" "c1" true)]
    (is (true? (gate/switch-on? s "paul" "c1")))
    (is (false? (gate/switch-on? s "peter" "c1")) "per profile")
    (is (false? (gate/switch-on? s "paul" "c2")))
    (is (= {} (gate/with-switch s "paul" "c1" false)))))

(deftest a-tap-counts-only-on-a-chat-of-the-target-profile
  (is (nil? (gate/tap-problem own "c1")))
  (is (= [403 {:error "Only the chat's owner may give it root."}] (gate/tap-problem writer "c1")))
  (is (= [400 {:error "chat is needed."}] (gate/tap-problem own nil)))
  (is (nil? (gate/chat-problem {:status 200 :body {:chat "c1"}})))
  (is (= [404 {:error "No such chat."}] (gate/chat-problem {:status 404 :body {:error "No such chat."}})))
  (is (= [502 {:error "The runner did not answer: x"}] (gate/chat-problem {:status 502 :body {:error "The runner did not answer: x"}})))
  (is (= [502 {:error "The runner answered 500."}] (gate/chat-problem {:status 500 :body {}}))))

(deftest kept-switches-only-of-profiles-here
  (is (= {"paul/c1" true} (gate/switches-here {"paul/c1" true "peter/c2" true "x" true} [{:profile "paul"}])))
  (is (= {} (gate/switches-here {"peter/c2" true} []))))

(deftest the-helper-asks-only-for-agent-users-here
  (let [ps [{:profile "paul" :socket "/s"}]]
    (is (nil? (gate/ask-problem {:agentUser "paul-claudia-agent" :argv ["ls"]} ps)))
    (is (= "Not an agent user." (gate/ask-problem {:agentUser "paul" :argv ["ls"]} ps)))
    (is (= "No profile peter on this server." (gate/ask-problem {:agentUser "peter-claudia-agent" :argv ["ls"]} ps)))
    (is (string? (gate/ask-problem {:agentUser "paul-claudia-agent" :argv []} ps))))
  (is (= {:allow true :via "allow" :chat "c"} (gate/verdict-of "c" true nil)))
  (is (= {:allow false :why "no" :chat "c"} (gate/verdict-of "c" false "no"))))

(deftest the-page-reads-the-root-switch
  (let [db {:servers {"s" {:rows {"c" {:root true} "d" {}}}}}]
    (is (true? (switches/root-on? db ["s" "c"])))
    (is (false? (switches/root-on? db ["s" "d"])))
    (is (false? (switches/root-on? db nil))))
  (is (not (contains? p/page->runner "root")) "the switch never goes to a runner"))
