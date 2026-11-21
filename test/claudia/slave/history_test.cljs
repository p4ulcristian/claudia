(ns claudia.slave.history-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.slave.history :as history]))

(deftest tool-summary-is-one-short-line
  (is (= "$ npm test" (history/tool-summary "Bash" {:command "npm   test\nmore"} nil)))
  (is (= "Edit src/x.cljs" (history/tool-summary "Edit" {:file_path "/w/p/src/x.cljs"} "/w/p")))
  (is (= "draw" (history/tool-summary "mcp__picture__draw" {} nil)) "a draw without a title")
  (is (= "a b" (history/tool-summary "mcp__a__b" {} nil))))

(deftest command-msg-reads-slash-commands
  (is (= [] (history/command-msg "<local-command-caveat>x</local-command-caveat>")))
  (is (= [{:role "user" :text "/model opus"}]
         (history/command-msg "<command-name>model</command-name><command-args> opus </command-args>")))
  (is (= [{:role "note" :text "done" :out true}]
         (history/command-msg "<local-command-stdout>done</local-command-stdout>")))
  (is (nil? (history/command-msg "just text"))))

(deftest typed-command-adds-the-slash-once
  (is (= "/clear" (history/typed-command "clear" nil)))
  (is (= "/model x" (history/typed-command "/model" "x"))))

(deftest answers-in-reads-question-answer-pairs
  (is (= {:Which? "Red" :Size "L"}
         (history/answers-in "User answered: \"Which?\"=\"Red\", \"Size\"=\"L\". Go on."))))

(deftest read-assistant-numbers-text-blocks-across-entries
  (let [[msgs counts] (history/read-assistant {:id "m1" :content [{:type "text" :text "hi"}
                                                                  {:type "text" :text "  "}
                                                                  {:type "text" :text "again"}]}
                                              {} 5 nil)]
    (is (= ["m1:0" "m1:2"] (map :id msgs)) "a blank block counts but shows nothing")
    (is (= {"m1" 2} counts))
    (testing "the next entry of the same message goes on counting"
      (is (= ["m1:3"] (map :id (first (history/read-assistant {:id "m1" :content [{:type "text" :text "x"}]}
                                                              counts 6 nil)))))))
  (let [[[tool]] (history/read-assistant {:id "m2" :content [{:type "tool_use" :id "t1" :name "Bash"
                                                              :input {:command "ls"}}]} {} 1 nil)]
    (is (= {:id "t1" :role "tool" :tool "Bash" :summary "$ ls" :label "Bash" :title "ls"}
           (select-keys tool [:id :role :tool :summary :label :title])))))

(deftest jsonl-entries-skips-blank-and-broken-lines
  (is (= [1 2] (map #(.-n ^js %) (history/jsonl-entries "{\"n\":1}\n\n  \n{broken\n{\"n\":2}")))))

(deftest sent-mid-turn-names-the-sent-uuid
  (is (= "sent" (history/sent-mid-turn #js {:type "attachment" :uuid "own"
                                            :attachment #js {:type "queued_command" :prompt "hi" :source_uuid "sent"}})))
  (is (= "own" (history/sent-mid-turn #js {:type "attachment" :uuid "own"
                                           :attachment #js {:type "queued_command" :prompt "hi"}})))
  (is (nil? (history/sent-mid-turn #js {:type "user"})))
  (is (nil? (history/sent-mid-turn nil))))

(deftest with-earlier-chains-puts-the-chain-before-a-compaction-first
  (let [entries [#js {:uuid "a" :type "user" :message #js {:role "user" :content "first"}}
                 #js {:uuid "b" :type "compact_boundary" :subtype "compact_boundary" :logicalParentUuid "a"
                      :compactMetadata #js {:preTokens 9000}}
                 #js {:uuid "s" :parentUuid "b" :type "user" :isCompactSummary true}
                 #js {:uuid "c" :parentUuid "s" :type "user"}]
        msgs [{:type "user" :uuid "s"} {:type "user" :uuid "c"}]]
    (is (= [{:type "user" :uuid "a" :parent_tool_use_id nil :message {:role "user" :content "first"}}
            {:type "compacted" :uuid "b" :parent_tool_use_id nil :compacted 9000}
            {:type "user" :uuid "c"}]
           (history/with-earlier-chains msgs entries))))
  (is (= [{:uuid "x"}] (history/with-earlier-chains [{:uuid "x"}] [])) "no compaction: as given"))

(deftest session-msgs-lays-out-a-chat
  (let [msgs [{:type "user" :uuid "u1" :message {:content "hello <system-reminder>x</system-reminder>"}}
              {:type "assistant" :uuid "a1" :message {:id "m1" :content [{:type "text" :text "hi"}
                                                                         {:type "tool_use" :id "t1" :name "Bash" :input {:command "ls"}}]}}
              {:type "user" :uuid "u2" :message {:content [{:type "tool_result" :tool_use_id "t1" :is_error true :content "nope"}]}}
              {:type "user" :uuid "u3" :parent_tool_use_id "t9" :message {:content "inside an agent"}}
              {:type "user" :uuid "u4" :message {:content [{:type "text" :text "look"} {:type "image"}]}}]
        entries [#js {:uuid "u1" :timestamp "1970-01-01T00:00:01.000Z"}]
        out (history/session-msgs msgs entries nil)]
    (is (= ["u1" "m1:0" "t1" "u4"] (map :id out)))
    (is (= {:id "u1" :role "user" :text "hello" :at 1000} (first out)))
    (is (= {:error true :text "nope"} (:result (nth out 2))))
    (is (true? (:error (nth out 2))))
    (is (= {:id "u4" :role "user" :text "look" :at 0 :images 1} (last out)))))

(deftest session-msgs-turns-an-answer-into-the-users-message
  (let [msgs [{:type "assistant" :uuid "a1"
               :message {:id "m1" :content [{:type "tool_use" :id "q1" :name "AskUserQuestion"
                                             :input {:questions [{:question "Colour?" :header "Colour"}]}}]}}
              {:type "user" :uuid "u1"
               :message {:content [{:type "tool_result" :tool_use_id "q1" :content "\"Colour?\"=\"Red\""}]}}]
        out (history/session-msgs msgs [] nil)]
    (is (= ["q1" "answer-q1"] (map :id out)))
    (is (= "Red" (:text (last out))))))

;; The shape of a real entry (Claude Code 2.1.326) for three messages queued during one turn and
;; taken in at once: one user entry, its uuid the last send's, the earlier two in mergedSendUuids.
;; Text and ids made up.
(def merged-entry
  #js {:parentUuid "p0" :isSidechain false :promptId "prompt-1" :type "user"
       :message #js {:role "user" :content "first\nsecond\nthird"}
       :uuid "send-3" :timestamp "1970-01-01T00:00:02.000Z" :promptSource "sdk"
       :turnPosition #js {:promptIndex 0 :turnIndex 11}
       :mergedSendUuids #js ["send-1" "send-2"] :turnAttributionKey "send-3"})

(deftest merged-sends-reads-the-other-ids
  (is (= ["send-1" "send-2"] (history/merged-sends merged-entry)))
  (is (nil? (history/merged-sends #js {:type "user" :uuid "u"})) "no merge")
  (is (nil? (history/merged-sends #js {:type "user" :mergedSendUuids #js []})) "an empty list")
  (is (nil? (history/merged-sends #js {:type "assistant" :mergedSendUuids #js ["x"]})) "only user entries")
  (is (= ["b"] (history/merged-sends #js {:type "user" :mergedSendUuids #js ["" nil "b"]})) "blank ids left out")
  (is (= {"send-3" ["send-1" "send-2"]}
         (history/merged-by-uuid [merged-entry #js {:type "user" :uuid "u"}]))))

(deftest session-msgs-marks-a-merged-message
  (let [msgs [{:type "user" :uuid "send-3" :message {:role "user" :content "first\nsecond\nthird"}}
              {:type "assistant" :uuid "a1" :message {:id "m1" :content [{:type "text" :text "ok"}]}}]
        out (history/session-msgs msgs [merged-entry] nil)]
    (is (= {:id "send-3" :role "user" :text "first\nsecond\nthird" :at 2000 :merged ["send-1" "send-2"]}
           (first out)))
    (is (nil? (:merged (second out))) "only the user message"))
  (is (= [{:id "x" :role "assistant"}] (history/with-merged [{:id "x" :role "assistant"}] {"x" ["y"]}))))

(deftest an-offered-word-becomes-a-card-s-data
  (let [[[m]] (history/read-assistant {:id "m1" :content [{:type "tool_use" :id "t1" :name "mcp__workspace__offer_word"
                                                           :input {:word " GitHub " :heard "get hub"}}]}
                                      {} 0 nil)]
    (is (= {:word "GitHub" :heard "get hub"} (:word m)))
    (is (= "offer word: GitHub" (:summary m))))
  (is (= {:word "" :heard ""} (history/offered-word nil)) "nothing given: blank, no card"))

(deftest claudias-own-notes-show-as-hers
  (is (= "Claudia: The agent stalled." (history/claudia-note (history/with-claudia-tag "The agent stalled."))))
  (is (nil? (history/claudia-note "The agent stalled.")) "the person's own words are not hers"))
