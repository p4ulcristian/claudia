(ns claudia.slave.chats-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.shared.colours :as colours]
            [claudia.slave.chats.answers :as answers]
            [claudia.slave.chats.session :as session]
            [claudia.slave.chats.titles :as titles]
            [claudia.slave.chats.turns :as turns]
            [claudia.slave.chats.view :as view]
            [claudia.slave.tools :as tools]))

;; Titles -----------------------------------------------------------------------------------------

(deftest plain-start-takes-the-marks-out
  (is (= "Title bold and code link" (titles/plain-start "# Title\n**bold** and `code` [link](http://x)" 200)))
  (is (= "a b" (titles/plain-start "| a | b |\n|---|---|" 200)))
  (testing "a long reply is cut at a word"
    (is (= "one two…" (titles/plain-start "one two three" 10)))))

(deftest titles-of-a-chat
  (is (= "Fix it" (titles/session-title #js {:customTitle "Fix it" :summary "s"})))
  (is (= "Summary" (titles/session-title #js {:summary "Summary" :firstPrompt "hi"})))
  (is (= "hi" (titles/session-title #js {:summary "hi" :firstPrompt "hi"})))
  (is (= "New chat" (titles/session-title #js {})))
  (is (= "look" (titles/first-prompt-title "look <claudia-file name=\"a.png\">")))
  (is (= "first line" (titles/first-sent-title "  first line\nsecond")))
  (is (= "New chat" (titles/first-sent-title "")))
  (is (= 120 (count (titles/first-sent-title (apply str (repeat 200 "x")))))))

(deftest title-updates-names-changed-titles
  (is (= [["a" "New"]]
         (titles/title-updates [{:id "a" :title "Old"} {:id "b" :title "Same"}]
                               [#js {:sessionId "a" :customTitle "New"}
                                #js {:sessionId "b" :customTitle "Same"}
                                #js {:sessionId "c" :customTitle "Unknown"}]))))

;; The chat list -----------------------------------------------------------------------------------

(deftest chats-without-a-colour
  (is (= [2 3] (colours/uncoloured [{:id 1 :color "red"} {:id 2 :color nil} {:id 3 :color ""}])))
  (is (= [] (colours/uncoloured []))))

(deftest least-worn-colours
  (is (= ["c"] (colours/least-worn ["a" "b" "c"] [{:id 1 :color "a"} {:id 2 :color "b"}] nil)))
  (is (= ["a" "c"] (colours/least-worn ["a" "b" "c"] [{:id 1 :color "a"} {:id 2 :color "b"}] 1)) "except itself")
  (is (= ["a" "b" "c"] (colours/least-worn ["a" "b" "c"] [{:id 1 :color "a" :archived true}] nil))))

(deftest row-of-a-chat
  (let [r {:id "c" :title "T" :ended 100 :seen 50 :preview "p" :last-turn "u1" :reply "ok"}]
    (testing "a finished turn not seen yet"
      (let [row (view/row r {} 0 400)]
        (is (true? (:unseen row)))
        (is (= {:id "u1" :ago 300 :stopped nil :failed nil :reply "ok"} (:lastTurn row)))
        (is (= "p" (:preview row)))
        (is (nil? (:runningFor row)))))
    (testing "work still running is not done yet"
      (is (false? (:unseen (view/row r {} 1 400)))))
    (testing "an open ask is the preview and goes to the hub"
      (let [row (view/row r {:running true :since 300
                             :asks {"k" {:ask {:id "k" :kind "permit" :tool "Plan" :description "d"} :at 350}}}
                          0 400)]
        (is (= "Needs your permission: Plan" (:preview row)))
        (is (true? (:waiting row)))
        (is (= [{:id "k" :kind "permit" :ago 50 :text "Plan: d"}] (:asks row)))
        (is (= 100 (:runningFor row)))))))

(deftest with-msg-replaces-or-adds
  (is (= [{:id 1 :t "new"} {:id 2}] (view/with-msg [{:id 1} {:id 2}] {:id 1 :t "new"})))
  (is (= [{:id 1} {:id 3}] (view/with-msg [{:id 1}] {:id 3})))
  (is (= 401 (count (view/with-msg (mapv #(hash-map :id %) (range 401)) {:id :x})))))

(deftest chat-msgs-lays-the-live-turn-over-the-file
  (let [hist [{:id "u1" :role "user"} {:id "a:0" :role "assistant" :text "old"} {:id "n" :role "note" :text "done"}]
        s {:turn [{:id "a:0" :role "assistant" :text "live"} {:id "t1" :role "tool"}
                  {:id "n2" :role "note" :text "done"}]
           :partial {:id "t9" :text "streaming"}
           :unsaved {"u2" {:id "u2" :role "user"} "u1" {:id "u1" :role "user"}}}]
    (is (= ["u1" "a:0" "n" "t1" "u2"] (map :id (view/chat-msgs hist s))))
    (is (= "live" (:text (second (view/chat-msgs hist s))))))
  (is (= {"b" 2} (view/unsaved-after {"a" 1 "b" 2} #{"a"}))))

;; Turns -------------------------------------------------------------------------------------------

(deftest turn-outcome-says-how-it-ended
  (is (= {:error nil :row {:ended 9 :last_turn "r" :stopped false :failed false :reply "Done" :preview "Done"}}
         (turns/turn-outcome {:subtype "success" :uuid "r" :result "**Done**"} false 9 "f")))
  (is (= {:error "a\nb" :row {:ended 9 :last_turn "f" :stopped false :failed true :reply ""}}
         (turns/turn-outcome {:subtype "error_during_execution" :errors ["a" "b"]} false 9 "f")))
  (is (false? (get-in (turns/turn-outcome {:subtype "error_max_turns"} true 9 "f") [:row :failed])) "a stopped turn did not fail"))

(deftest user-messages-of-a-turn
  (is (= "a\nb" (turns/user-text [{:type "text" :text "a"} {:type "tool_result"} {:type "text" :text "b"}])))
  (is (= "x" (turns/user-text "x")))
  (is (= [{:type "tool_result" :tool_use_id "t"}] (turns/tool-results [{:type "text"} {:type "tool_result" :tool_use_id "t"}])))
  (is (= {:id "t" :result {:error true :text "bad"} :error true}
         (turns/with-result {:id "t"} {:is_error true :content "bad"}))))

(deftest notes-from-system-messages
  (is (= "Context compacted (12k tokens before)." (:text (turns/compact-note {:compact_metadata {:pre_tokens 12000}} 1))))
  (is (= {:id "out-5" :role "note" :text "hi" :at 5} (turns/note-of {:content "hi"} "out-" 5)))
  (is (true? (turns/task-notice? {:subtype "task_notification" :summary "done"})))
  (is (false? (turns/task-notice? {:subtype "task_notification" :summary "done" :ambient true}))))

(deftest a-sent-message
  (let [msg (turns/sent-msg "u" "hi" [{:data "x"}] true 7)]
    (is (= {:id "u" :role "user" :text "hi" :at 7 :images 1 :queued true} msg))
    (is (= {:turn [:old] :turn-id "u" :unsaved {"u" msg}} (turns/with-sent {:turn [:old]} msg)) "queued: the turn goes on"))
  (let [msg (turns/sent-msg "u" "hi" nil false 7)]
    (is (= {:turn [] :turn-id "u" :unsaved {"u" msg}} (turns/with-sent {:turn [:old]} msg))))
  (testing "taken in or dropped"
    (let [s {:turn [{:id "u"} {:id "a"}] :unsaved {"u" {:id "u" :queued true}}}]
      (is (= {:turn [{:id "a"} {:id "u"}] :unsaved {"u" {:id "u"}}} (turns/taken-in s {:id "u"})))
      (is (= {:turn [{:id "a"}] :unsaved {}} (turns/dropped s "u"))))))

(deftest context-of-reads-the-count
  (is (= {:used 10 :max 100 :percent 10 :model "m"}
         (turns/context-of {:totalTokens 10 :maxTokens 100 :percentage 9.6 :model "m"})))
  (is (nil? (turns/context-of {}))))

;; Answers -----------------------------------------------------------------------------------------

(deftest answers-to-tools-and-asks
  (is (= {:behavior "deny" :message "why" :interrupt true} (answers/dropped-answer "question" "why")))
  (is (= (answers/refusal "why Nothing ran.") (answers/dropped-answer "secret" "why")))
  (is (= (answers/refusal "No command given.") (answers/secret-refusal {:env_name "TOKEN" :command ""})))
  (is (:isError (answers/secret-refusal {:env_name "1x" :command "ls"})))
  (is (nil? (answers/secret-refusal {:env_name "TOKEN" :command "ls"})))
  (is (= {:id "a" :kind "permit" :tool "ExitPlanMode" :title "Claude wants to use ExitPlanMode" :description "" :detail "{}"}
         (answers/permit-ask "a" "ExitPlanMode" {} nil nil))))

(deftest draw-answer-carries-the-screenshot
  (is (= [{:type "text" :text "ok"} {:type "image" :data "aGk=" :mimeType "image/png"}]
         (:content (answers/draw-answer "ok" {:png (js/Buffer.from "hi")}))))
  (is (= (answers/text-result "ok\n\n(No screenshot of the picture: gone)") (answers/draw-answer "ok" {:error "gone"}))))

(deftest folder-and-image-answers
  (is (= "/h/x" (answers/home-path "~/x" "/h")))
  (is (= "a~/x" (answers/home-path "a~/x" "/h")))
  (let [t (get-in (answers/folder-answer "/w" nil nil) [:content 0 :text])]
    (is (re-find #"no CLAUDE.md" t))
    (is (re-find #"not a git repository" t)))
  (is (re-find #"<claude-md path=\"/w/CLAUDE.md\">\nrules" (get-in (answers/folder-answer "/w" "rules" "## main\n") [:content 0 :text])))
  (is (re-find #"moved this chat to the project folder /w/app\. Work there" (answers/moved-note "/w/app" "/w")))
  (is (re-find #"It works in /w again" (answers/moved-note nil "/w")))
  (is (:isError (answers/image-refusal "/a.txt" nil {:file? true :size 1})))
  (is (:isError (answers/image-refusal "/a.png" "image/png" nil)))
  (is (:isError (answers/image-refusal "/a.png" "image/png" {:file? true :size 6000000})))
  (is (nil? (answers/image-refusal "/a.png" "image/png" {:file? true :size 1})))
  (is (= "Shown in the chat: a.png (cap)." (get-in (answers/image-shown "a.png" "d" "image/png" "cap") [:content 1 :text]))))

;; Session -----------------------------------------------------------------------------------------

(deftest session-names-and-spec
  (is (re-matches #"token-[0-9a-f]{8}" (session/account-of "t")))
  (is (= (session/hash36 "x") (session/hash36 "x")))
  (is (not= (session/hash36 "x") (session/hash36 "y")))
  (is (= {"A" "1" "PATH" "/bin:/usr" "CLAUDE_CODE_OAUTH_TOKEN" "t"}
         (session/run-env #js {:A "1" :PATH "/usr"} "t" "/bin")))
  (let [spec (session/start-spec {:cwd "/w" :id "c" :resume? true :prompt "p" :allowed ["Read"] :model "m"
                                  :edit-tools "Edit" :worktrees? true})]
    (is (= "c" (get-in spec [:options :resume])))
    (is (= "m" (get-in spec [:options :model])))
    (is (not (contains? (:options spec) :effort)))
    (is (= "Edit|Bash" (get-in spec [:hooks :PostToolUse 0 :matcher])))))

(deftest a-chats-settings-take-a-saved-plan-only
  (let [s {:model nil :effort nil :plan "paul"}]
    (is (= {:model "m" :effort nil :plan "paul"} (session/settings-with s {:model "m"} ["paul" "peter"])))
    (is (= {:model nil :effort nil :plan "peter"} (session/settings-with s {:plan "peter" :other 1} ["paul" "peter"])))
    (is (nil? (:plan (session/settings-with s {:plan "gone"} ["paul" "peter"]))))
    (is (nil? (:plan (session/settings-with s {:plan nil} ["paul"]))))))

(deftest a-session-starts-again-on-another-token
  (let [s {:live true :running false :account "token-a"}]
    (is (session/restart? s "token-b" 0))
    (is (not (session/restart? s "token-a" 0)) "the same token")
    (is (not (session/restart? (assoc s :running true) "token-b" 0)) "never during a turn")
    (is (not (session/restart? (assoc s :live false) "token-b" 0)))
    (is (not (session/restart? s "token-b" 2)) "a replaced token waits for the session's work")
    (is (session/restart? (assoc s :plan-changed true) "token-b" 2) "a picked plan does not wait")
    (is (not (session/restart? (assoc s :account nil) "token-b" 0)))
    (is (session/restart? (assoc s :account nil :plan-changed true) "token-b" 0))))

(deftest limits-are-kept-under-the-token-the-session-runs-on
  (let [paul "8716b2446a8e5549" peter "e64a2b0ee27b1109"]
    (is (= peter (session/limits-key-of "token-e64a2b0e" [paul peter])))
    (is (nil? (session/limits-key-of "token-00000000" [paul peter])) "a token no longer known")
    (is (nil? (session/limits-key-of nil [paul peter])))))

;; The chat's reply limit ---------------------------------------------------------------------------

(deftest stop-verdict-hands-a-long-reply-back
  (let [long-reply (apply str (repeat (inc tools/reply-limit) "a"))
        at-limit (apply str (repeat tools/reply-limit "a"))]
    (testing "a reply within the limit ends the turn; spaces around it do not count"
      (is (= {:continue true} (turns/stop-verdict "Done." 0)))
      (is (= {:continue true} (turns/stop-verdict (str "  " at-limit "\n") 0)))
      (is (= {:continue true} (turns/stop-verdict nil 0))))
    (testing "a reply over the limit goes back, with its length and the limit"
      (let [v (turns/stop-verdict long-reply 0)]
        (is (= "block" (:decision v)))
        (is (re-find (re-pattern (str "has " (inc tools/reply-limit))) (:reason v)))))
    (testing "handed back reply-sends times, then it stays"
      (is (= "block" (:decision (turns/stop-verdict long-reply (dec tools/reply-sends)))))
      (is (= {:continue true} (turns/stop-verdict long-reply tools/reply-sends))))))

(deftest the-prompt-names-the-reply-limit-and-the-notes-form
  (is (re-find (re-pattern (str "never over " tools/reply-limit " characters")) tools/system-append))
  (is (contains? tools/form-help "notes")))

(deftest last-replies-are-the-texts-after-the-last-step
  (is (= ["b" "a"] (map :id (turns/last-replies [{:id "t" :role "tool"} {:id "a" :role "assistant"} {:id "b" :role "assistant"}]))))
  (is (= [] (turns/last-replies [{:id "a" :role "assistant"} {:id "t" :role "tool"}]))))

(deftest open-chats-keep-their-session
  (is (true? (view/keeps-session? {:id 1 :color "blue"})))
  (is (true? (view/keeps-session? {:id 1 :color nil})) "the default look is open too")
  (is (false? (view/keeps-session? {:id 1 :color "blue" :archived true})) "archived")
  (is (false? (view/keeps-session? {:id 1 :color "ash"})) "passive")
  (is (false? (view/keeps-session? nil)) "no such chat"))
