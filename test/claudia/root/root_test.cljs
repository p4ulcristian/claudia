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

(deftest agent-users-and-their-runners
  (is (= "paul" (rules/profile-of "paul-claudia-agent")))
  (is (= "a-b" (rules/profile-of "a-b-claudia-agent")))
  (is (nil? (rules/profile-of "paul")))
  (is (nil? (rules/profile-of "break-glass")))
  (is (nil? (rules/profile-of "-claudia-agent")))
  (is (= "/run/claudia-paul/runner.sock" (rules/runner-socket nil "paul")))
  (is (= "/tmp/r-paul.sock" (rules/runner-socket "/tmp/r-{profile}.sock" "paul"))))

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

(deftest only-an-exact-yes-from-the-runner-runs
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
    (is (= {:id "a1" :kind "permit" :root true :tool "Run as root" :title "Run as root"
            :description "ls /root" :detail "In /srv\n\nls /root" :expires 99} a)))
  (is (= "In /\n\nls" (:detail (answers/root-ask "a" ["ls"] nil 1))))
  (is (= {:allow true :via "allow" :chat "c"} (answers/root-answer "c" {:behavior "allow"})))
  (is (= {:allow false :chat "c" :why "The turn ended."} (answers/root-answer "c" {:behavior "deny" :message "The turn ended."})))
  (is (= "The person said no." (:why (answers/root-answer "c" {:behavior "deny"})))))

(deftest the-runner-answers-root-without-asking-only-with-the-switch
  (is (= :root-ask (runner/request-op "POST" "/api/root/ask" nil nil false)))
  (is (nil? (runner/request-op "GET" "/api/root/ask" nil nil false)))
  (is (= {:allow true :via "switch" :chat "c"} (runner/root-verdict {:id "c" :root true})))
  (is (nil? (runner/root-verdict {:id "c" :root false})) "the person is asked")
  (is (false? (:allow (runner/root-verdict nil)))))

(deftest the-row-says-the-root-switch
  (is (true? (:root (view/row {:id "c" :root true} {} 0 0))))
  (is (false? (:root (view/row {:id "c"} {} 0 0)))))

(deftest only-the-owner-gets-the-owner-header
  (let [own {:profile "paul"} other {:profile "peter"}]
    (is (some #{["X-Claudia-Owner" "1"]} (front/runner-headers #js ["X-Claudia-Owner" "0"] {:own own :target own})))
    (is (not (some #(= "X-Claudia-Owner" (first %)) (front/runner-headers #js [] {:own own :target other}))) "a write tick")
    (is (not (some #(= "X-Claudia-Owner" (first %)) (front/runner-headers #js [] {:own nil :target other :read-only true}))))
    (is (not (some #(= ["X-Claudia-Owner" "0"] %) (front/runner-headers #js ["X-Claudia-Owner" "0"] {:own own :target other}))) "never the caller's"))
  (is (true? (front/runner-only? "/api/root/ask")))
  (is (false? (front/runner-only? "/api/chats"))))

(deftest the-page-reads-the-root-switch
  (let [db {:servers {"s" {:rows {"c" {:root true} "d" {}}}}}]
    (is (true? (switches/root-on? db ["s" "c"])))
    (is (false? (switches/root-on? db ["s" "d"])))
    (is (false? (switches/root-on? db nil))))
  (is (contains? p/page->runner "root"))
  (is (not (contains? p/read-only-ok "root"))))
