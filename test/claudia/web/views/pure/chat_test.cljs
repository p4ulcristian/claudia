(ns claudia.web.views.pure.chat-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.views.pure.chat :as chat]
            [claudia.web.views.pure.steps :as steps]
            [claudia.web.views.pure.words :as words]))

(deftest turn-picture-is-its-own-else-the-last-before-the-next-turn
  (let [msgs [{:id "a" :role "user" :at 10} {:id "b" :role "user" :at 20}]
        pictures [{:turn "a" :at 11} {:turn "x" :at 15} {:turn "b" :at 21}]]
    (is (= 0 (chat/turn-picture pictures msgs "a")))
    (is (= 2 (chat/turn-picture pictures msgs "b")))
    (testing "no picture of its own: the last one drawn before the next user message"
      (is (= 1 (chat/turn-picture [{:turn "y" :at 5} {:turn "x" :at 15} {:turn "z" :at 25}] msgs "a"))))
    (is (nil? (chat/turn-picture [] msgs "a")))))

(deftest others-count-leaves-out-the-open-and-the-off-chats
  (let [rows [{:server "s" :id "1" :waiting true} {:server "s" :id "2" :unseen true}
              {:server "s" :id "3" :unseen true :off true} {:server "s" :id "4" :waiting true}]]
    (is (= {:waiting 1 :done 1} (chat/others-count rows ["s" "4"] false)))
    (is (= {:waiting 1 :done 0} (chat/others-count rows ["s" "4"] true)))))

(deftest new-title-only-when-new
  (is (= "B" (chat/new-title "  B " "A")))
  (is (nil? (chat/new-title "A" "A")))
  (is (nil? (chat/new-title "  " "A"))))

(deftest small-words
  (is (= "5s" (chat/elapsed-text 0 5400)))
  (is (= "2m 5s" (chat/elapsed-text 0 125000)))
  (is (= "picture · 1 file" (chat/turn-links true 1)))
  (is (= "3 files" (chat/turn-links false 3)))
  (is (= "Message Claude…" (chat/placeholder nil nil)))
  (is (= "Add to this turn…" (chat/placeholder nil true)))
  (is (= "Paul's chat · read only" (chat/read-only-note "paul")))
  (is (= "Someone else's chat · read only" (chat/read-only-note nil))))

(deftest feed-keeps-queued-messages-below
  (let [{:keys [queued blocks last-user]}
        (chat/feed {:running true
                    :messages [{:id "u1" :role "user"} {:id "t1" :role "tool"} {:id "t2" :role "tool"}
                               {:id "u2" :role "user" :queued true}]})]
    (is (= ["u2"] (map :id queued)))
    (is (= [:msg :steps] (map :k blocks)))
    (is (= "u1" last-user))))

(deftest user-marks-of-a-message
  (is (= {:viewing? true :picture? true :files 2 :forkable? true}
         (chat/user-marks {:id "a"} {:viewing "a" :picture-turns #{"a"} :change-turns {"a" 2} :last-user "b"})))
  (is (false? (:forkable? (chat/user-marks {:id "answer-1"} {}))))
  (is (false? (:forkable? (chat/user-marks {:id "a"} {:last-user "a"})))))

(deftest working-label-says-what-it-waits-for
  (is (= "Waiting for your OK" (chat/working-label {:asks [{:kind "permit"}]})))
  (is (= "Waiting for your answer" (chat/working-label {:asks [{:kind "question"}]})))
  (is (= "Reading" (chat/working-label {:note "Reading"})))
  (is (= "Working" (chat/working-label {}))))

(deftest scrolling
  (is (chat/at-bottom? 1000 900 50))
  (is (not (chat/at-bottom? 1000 100 50)))
  (is (chat/wants-older? {:more true} 10))
  (is (not (chat/wants-older? {:more true :loading-older true} 10)))
  (is (not (chat/wants-older? {:more true} 900)))
  (testing "older messages above keep the place"
    (is (= 700 (chat/kept-top ["c" "m2" 1000] "c" "m1" false 200 1500)))
    (is (nil? (chat/kept-top ["c" "m2" 1000] "c" "m1" true 200 1500)))
    (is (nil? (chat/kept-top ["c" "m1" 1000] "c" "m1" false 200 1500)))
    (is (nil? (chat/kept-top ["d" "m2" 1000] "c" "m1" false 200 1500)))))

(deftest typed-answer-answers-a-question
  (let [ask {:kind "question" :questions [{:question "Q1"} {:question "Q2"}]}]
    (is (= {"Q1" "yes" "Q2" "yes"} (chat/typed-answer ask "yes" [])))
    (is (nil? (chat/typed-answer ask "yes" [{:id 1}])))
    (is (nil? (chat/typed-answer ask "" [])))
    (is (nil? (chat/typed-answer {:kind "permit"} "yes" [])))))

(deftest words
  (is (= "Paul" (words/capital "paul")))
  (is (= "S" (words/initial "server")))
  (is (= "a/b" (words/clip "a/b" 10)))
  (is (= "…/proj/file.txt" (words/clip "/home/x/long/path/proj/file.txt" 18)))
  (is (= "1 file" (words/plural 1 "file")))
  (is (= "ash" (peek words/colours-with-ash))))

(deftest steps-fold-into-groups
  (is (= "Draw" (steps/tool-label "mcp__picture__draw")))
  (is (= "github create_pr" (steps/tool-label "mcp__github__create_pr")))
  (is (= "Bash" (steps/tool-label "Bash")))
  (is (= [{:k :msg :m {:role "user"}}
          {:k :steps :id 1 :tools [{:id 1 :role "tool"} {:id 2 :role "tool"}]}
          {:k :steps :id 3 :tools [{:id 3 :role "tool" :image true}]}
          {:k :shown :tool {:id 3 :role "tool" :image true}}]
         (steps/blocks [{:role "user"} {:id 1 :role "tool"} {:id 2 :role "tool"} {:id 3 :role "tool" :image true}])))
  (is (= [{:k :steps :id 4 :tools [{:id 4 :role "tool" :video true}]}
          {:k :shown :tool {:id 4 :role "tool" :video true}}]
         (steps/blocks [{:id 4 :role "tool" :video true}])))
  (is (= "old" (steps/step-summary {:title "old" :summary "new"})))
  (is (= "new" (steps/step-summary {:summary "new"})))
  (is (= "out" (steps/step-result {:text "out"})))
  (is (= "Read, Bash" (steps/group-names [{:tool "Read"} {:tool "Bash"} {:tool "Read"}]))))

(deftest chat-plan-is-the-pick-else-the-default
  (let [plans [{:key "paul" :name "Paul"} {:key "peter" :name "Peter"}]]
    (is (= {:key "peter" :name "Peter"} (chat/chat-plan plans "peter" "paul")))
    (is (= {:key "paul" :name "Paul"} (chat/chat-plan plans nil "paul")))
    (is (= {:key "paul" :name "Paul"} (chat/chat-plan plans "gone" "paul")) "a pick no longer saved")
    (is (nil? (chat/chat-plan plans nil nil)))
    (is (nil? (chat/chat-plan [] "paul" "paul")))))
