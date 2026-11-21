(ns claudia.slave.activity-test
  "The activity log's lines (README decision 59) on plain data: the shared shape, the runner's tool
  and message lines, the front's sender headers and its own lines."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [claudia.shared.activity :as activity]
            [claudia.shared.protocol :as p]
            [claudia.slave.activity :as act]
            [claudia.slave.front :as front]
            [claudia.slave.rootfront :as rootfront]
            [claudia.slave.ssh-cert :as ssh-cert]
            [claudia.slave.sshfront :as sshfront]))

(defn- entry-of [line]
  (is (str/starts-with? line "activity "))
  (p/read-json (subs line (count activity/prefix))))

(deftest a-line-is-the-prefix-and-json-without-nils
  (is (= "activity {\"kind\":\"x\",\"a\":1}" (activity/line {:kind "x" :a 1 :b nil})))
  (is (= 500 (count (activity/cut (apply str (repeat 600 "a"))))))
  (is (= "ab" (activity/cut "ab")))
  (is (nil? (activity/cut nil)))
  (is (= 64 (count (activity/argv-cut (vec (repeat 100 "x"))))))
  (is (nil? (activity/argv-cut nil))))

(deftest a-tool-call-says-what-it-did-in-short
  (is (= "ls -la" (act/input-of "Bash" {:command "ls -la"})))
  (is (= "/a/b" (act/input-of "Edit" {:file_path "/a/b" :old_string "x"})))
  (is (= "https://example.org" (act/input-of "WebFetch" {:url "https://example.org" :prompt "p"})))
  (is (= "fo+" (act/input-of "Grep" {:pattern "fo+" :path "/x"})))
  (is (= "{\"server\":\"s\"}" (act/input-of "mcp__x__y" {:server "s"})) "an unknown tool: its input as JSON")
  (is (= 500 (count (act/input-of "Bash" {:command (apply str (repeat 900 "y"))})))))

(def ^:private self {:profile "paul" :agentUser "paul-claudia-agent"})

(deftest every-tool-call-of-an-assistant-message-is-a-line
  (let [m {:type "assistant" :parent_tool_use_id nil
           :message {:content [{:type "text" :text "hi"}
                               {:type "tool_use" :id "t1" :name "Bash" :input {:command "id"}}]}}]
    (is (= [{:kind "tool" :chat "c" :profile "paul" :agentUser "paul-claudia-agent" :tool "Bash" :id "t1"
             :agent nil :input "id"}]
           (act/tool-entries "c" self m))))
  (is (= "a1" (:agent (first (act/tool-entries "c" self {:parent_tool_use_id "a1"
                                                         :message {:content [{:type "tool_use" :id "t2" :name "Read"
                                                                              :input {:file_path "/f"}}]}}))))
      "a sub-agent's call names the agent's call")
  (is (empty? (act/tool-entries "c" self {:message {:content "text"}}))))

(deftest every-tool-result-is-a-line-with-the-bash-exit
  (let [m {:message {:content [{:type "tool_result" :tool_use_id "t1" :is_error true :content "Exit code 2\nno"}
                               {:type "tool_result" :tool_use_id "t2" :content [{:type "text" :text "ok"}]}
                               {:type "tool_result" :tool_use_id "t3" :content "x"}]}}]
    (is (= [{:kind "result" :chat "c" :tool "Bash" :id "t1" :ok false :exit 2}
            {:kind "result" :chat "c" :tool "Bash" :id "t2" :ok true :exit 0}
            {:kind "result" :chat "c" :tool nil :id "t3" :ok true :exit nil}]
           (act/result-entries "c" {"t1" "Bash" "t2" "Bash"} m))))
  (is (nil? (act/exit-of "Bash" false "permission denied")) "a failed Bash without its exit code")
  (is (nil? (act/exit-of "Read" true "x"))))

(deftest a-message-names-who-typed-it
  (is (= {:kind "message" :chat "c" :profile "paul" :by "a@x.org" :via "own"}
         (act/message-entry "c" "paul" {:by "a@x.org" :via "own" :ws :ignored})))
  (is (= {:by "a@x.org" :via "write-tick"} (act/sender #js {"x-claudia-by" " a@x.org " "x-claudia-via" "write-tick"})))
  (is (= {:by nil :via nil} (act/sender #js {}))))

(deftest the-front-names-the-sender-and-strips-any-the-caller-sent
  (let [r {:own {:profile "ann"} :target {:profile "ann"} :read-only false :by "ann@x.org"}
        out (map vec (front/runner-headers #js ["X-Claudia-By" "eve@x.org" "x-claudia-via" "own" "Accept" "*/*"] r))]
    (is (= [["Accept" "*/*"] ["X-Claudia-Profile" "ann"] ["X-Claudia-By" "ann@x.org"] ["X-Claudia-Via" "own"]] out))
    (is (not (some #(= "eve@x.org" (second %)) out)) "a forged sender never reaches the runner"))
  (testing "how the caller reaches the chats"
    (is (= "own" (front/via-of {:viewing nil :read-only false})))
    (is (= "write-tick" (front/via-of {:viewing "bob" :read-only false})))
    (is (= "view" (front/via-of {:viewing "bob" :read-only true}))))
  (is (= [["X-Claudia-Profile" "ann"]] (map vec (front/runner-headers #js ["X-Claudia-By" "eve@x.org"] {:target {:profile "ann"}})))
      "no caller: no sender, and none passed on"))

(deftest the-front-logs-what-it-passes-on
  (let [r {:own {:profile "ann"} :target {:profile "bob"} :viewing "bob" :read-only false :by "ann@x.org"}]
    (is (= {:kind "connection" :by "ann@x.org" :profile "bob" :via "write-tick"}
           (entry-of (front/passed-line "connection" r nil nil))))
    (is (= {:kind "call" :by "ann@x.org" :profile "bob" :via "write-tick" :method "POST" :path "/api/chat-move/hold"}
           (entry-of (front/passed-line "call" r "POST" "/api/chat-move/hold"))))))

(deftest the-front-logs-root-taps-and-certificates
  (let [route {:own {:profile "paul" :email "p@x.org"} :target {:profile "paul" :user "paul-claudia-agent"}}]
    (is (= {:kind "root" :chat "c1" :profile "paul" :agentUser "paul-claudia-agent" :argv ["ls"]
            :decision "allowed" :by "p@x.org"}
           (entry-of (rootfront/tap-line route "c1" "allowed" ["ls"]))))
    (is (= {:kind "root" :chat "c1" :profile "paul" :agentUser "paul-claudia-agent" :decision "switch on" :by "p@x.org"}
           (entry-of (rootfront/tap-line route "c1" "switch on" nil)))))
  (is (= {:kind "ssh-cert" :agentUser "paul-claudia-agent" :keyId "s/paul-claudia-agent/t"}
         (entry-of (sshfront/issued-line "paul-claudia-agent" "s/paul-claudia-agent/t"))))
  (is (= "s/u/t" (ssh-cert/key-id-of {:text "{\"certificate\":\"c\",\"keyId\":\"s/u/t\"}"})))
  (is (nil? (ssh-cert/key-id-of {:text "{\"certificate\":\"c\"}"})))
  (is (nil? (ssh-cert/key-id-of {:text "Bad Gateway"}))))
