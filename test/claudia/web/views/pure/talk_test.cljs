(ns claudia.web.views.pure.talk-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.talk :as talk]))

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

(def a {:id "t1" :k ["s" "c"] :voice "nora" :text "One."})
(def b {:id "t2" :k ["s" "d"] :voice "otto" :text "Two."})
(def c {:id "t3" :k ["s" "c"] :voice "nora" :text "Three."})

(deftest replies-wait-in-line-and-are-read-once
  (let [st (-> talk/empty-line (talk/with-item a) (talk/with-item b) (talk/with-item a))]
    (is (= [a b] (:queue st)) "the same reply twice waits once")
    (let [st1 (talk/next-up st)]
      (is (= a (:current st1)))
      (is (= [b] (:queue st1)))
      (is (= st1 (talk/next-up st1)) "one at a time")
      (is (= st1 (talk/with-item st1 a)) "a reply being read is not queued again")
      (let [st2 (-> st1 talk/without-current talk/next-up talk/without-current)]
        (is (= b (:current (talk/next-up (talk/without-current st1)))))
        (is (= st2 (talk/with-item st2 a)) "a reply read is not read again")))))

(deftest holding-the-mic-drops-only-the-reply-being-read
  (let [st (-> talk/empty-line (talk/with-item a) (talk/with-item b) talk/next-up (talk/held true))]
    (is (nil? (:current st)))
    (is (= [b] (:queue st)) "the others still come")
    (is (= st (talk/next-up st)) "nothing is read while the mic is held")
    (is (= b (:current (talk/next-up (talk/held st false)))))))

(deftest a-chat-turned-off-leaves-the-line
  (let [st (-> talk/empty-line (talk/with-item a) (talk/with-item b) (talk/with-item c) talk/next-up)]
    (is (= {:queue [b] :current nil} (select-keys (talk/without-chat st ["s" "c"]) [:queue :current])))
    (is (= a (:current (talk/without-chat st ["s" "d"]))))))

(deftest the-play-button-reads-next-even-when-read
  (let [st (-> talk/empty-line (talk/with-item a) (talk/with-item b) talk/next-up talk/without-current)]
    (is (= [a b] (:queue (talk/with-item-first st a))))
    (is (= [b a] (:queue (talk/with-item-first (talk/with-item-first st a) b))) "no reply twice")))

(deftest hungarian-replies-are-read-in-hungarian
  (is (= "hu" (talk/language-of "Kész, működik.")))
  (is (= "hu" (talk/language-of "Ez van, és nem kell semmi.")))
  (is (nil? (talk/language-of "Done, the tests pass.")))
  (is (nil? (talk/language-of nil))))
