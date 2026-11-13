(ns claudia.web.views.pure.talk-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.talk :as talk]))

(deftest the-turn-ends-with-claudes-last-message
  (is (= "Done." (talk/turn-reply [{:role "user" :text "go"} {:role "assistant" :text "Looking."}
                                   {:role "note" :text "x"} {:role "assistant" :text " Done. "}])))
  (is (nil? (talk/turn-reply [{:role "assistant" :text "old"} {:role "user" :text "new"}])))
  (is (nil? (talk/turn-reply []))))

(deftest asks-are-their-questions
  (is (= "Which one? Now?" (talk/ask-text {:questions [{:question "Which one?"} {:question " Now? "}]})))
  (is (nil? (talk/ask-text {:questions []}))))

(deftest replies-become-words
  (is (= "It works, see the chat." (talk/speakable "**It works**, see [the chat](https://x.y/z).")))
  (is (= "Run npm test now. There's code in the chat."
         (talk/speakable "Run `npm test` now.\n```sh\nnpm test\n```")))
  (is (= "Sizes: There's a table in the chat." (talk/speakable "Sizes:\n| a | b |\n|---|---|\n| 1 | 2 |")))
  (is (= "One two" (talk/speakable "- One\n- two")))
  (is (= "There's code in the chat." (talk/speakable "```\ncode only\n```\n")))
  (is (nil? (talk/speakable " ")))
  (is (= "Done" (talk/speakable "## Done"))))

(deftest only-the-open-chat-speaks
  (is (talk/speaks? {:talk? true :open ["s" "c"]} ["s" "c"]))
  (is (not (talk/speaks? {:talk? true :open ["s" "d"]} ["s" "c"])))
  (is (not (talk/speaks? {:talk? false :open ["s" "c"]} ["s" "c"]))))
