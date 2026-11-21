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

(deftest the-colour-name-is-said-unless-the-chat-is-open
  (let [item {:k ["s" "c"] :colour-name "Red" :text "It works."}]
    (is (= "Red here. It works." (talk/spoken item ["s" "other"])) "another chat open")
    (is (= "Red here. It works." (talk/spoken item nil)) "no chat open")
    (is (= "It works." (talk/spoken item ["s" "c"])) "its own chat open")
    (is (= "It works." (talk/spoken (dissoc item :colour-name) nil)) "no colour: no name")))

(deftest a-tapped-chat-goes-first-the-others-wait
  (let [r1 {:id "r1" :k ["s" "red"]} b1 {:id "b1" :k ["s" "blue"]} b2 {:id "b2" :k ["s" "blue"]}
        st (-> talk/empty-line (talk/with-item r1) (talk/with-item b1) talk/next-up)
        tapped (talk/with-chat-first st [b1 b2])]
    (is (nil? (:current tapped)) "the one being read stops")
    (is (= [b1 b2] (:queue tapped)) "blue's own waiting one gives way to the tapped list")
    (let [st2 (-> talk/empty-line (talk/with-item b1) talk/next-up talk/without-current (talk/with-item r1))
          tapped2 (talk/with-chat-first st2 [b1 b2])]
      (is (= [b1 b2 r1] (:queue tapped2)) "red, waiting, comes after them")
      (is (= "b1" (:id (:current (talk/next-up tapped2)))) "b1 is read first")
      (is (not-any? #{"b1"} (:said tapped2)) "read before, read again"))))

(deftest a-reply-is-given-up-after-its-own-length
  (is (= 13000 (talk/stall-ms 15 5 3000)) "what is left on the clock, plus the margin")
  (is (= 23000 (talk/stall-ms 20 0 3000)) "a frozen clock leaves all of it")
  (is (= 3000 (talk/stall-ms 5 9 3000)) "past its end: only the margin")
  (is (= 3000 (talk/stall-ms nil 0 3000)) "nothing scheduled: only the margin"))
