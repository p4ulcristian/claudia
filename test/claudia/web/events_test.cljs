(ns claudia.web.events-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.shared.colours :as colours]
            [claudia.shared.files :as files]
            [claudia.web.events :as ev]))

(deftest status-remembers-the-outage-and-shows-a-long-one-back
  (let [down (ev/with-status {:status :open :rows {}} :closed 1000)]
    (is (= {:status :closed :rows {} :down-since 1000 :back false} down))
    (is (= 1000 (:down-since (ev/with-status down :connecting 1200))) "still down since then")
    (is (= {:status :open :rows {} :back true}
           (ev/with-status (ev/with-status down :connecting 1200) :open (+ 1000 ev/back-after-ms))))
    (is (false? (:back (ev/with-status down :open 1100))) "a blip"))
  (is (= {:status :open :back false} (ev/with-status {:status :connecting} :open 5)) "the first open"))

(deftest new-picks-carry-the-voice-and-bell
  (is (= {:voice "otto" :bell true :color "jade"} (ev/new-picks {:voice "otto" :bell true :color "jade" :model nil}))))

(deftest an-error-goes-only-while-it-is-the-one-shown
  (let [db {:error "s: boom" :error-from "s" :chats {["s" "c"] {:error "bad" :title "t"}}}]
    (is (nil? (:error (ev/without-error db nil "s: boom"))))
    (is (nil? (:error-from (ev/without-error db nil "s: boom"))))
    (is (= "s: boom" (:error (ev/without-error db nil "older"))) "a newer error stays")
    (is (= {:title "t"} (get-in (ev/without-error db ["s" "c"] "bad") [:chats ["s" "c"]])))
    (is (= "bad" (get-in (ev/without-error db ["s" "c"] "other") [:chats ["s" "c"] :error])))))

(deftest without-index-drops-one-item
  (is (= [:a :c] (ev/without-index [:a :b :c] 1)))
  (is (= [] (ev/without-index [:a] 0))))

(deftest upsert-msg-adds-changes-and-drops
  (let [msgs [{:id 1 :text "a"} {:id 2 :text "b" :queued true}]]
    (is (= [{:id 1 :text "a2"} {:id 2 :text "b" :queued true}] (ev/upsert-msg msgs {:id 1 :text "a2"})))
    (is (= (conj msgs {:id 3}) (ev/upsert-msg msgs {:id 3})))
    (testing "gone takes a queued message away"
      (is (= [{:id 1 :text "a"}] (ev/upsert-msg msgs {:id 2 :gone true})))
      (is (= msgs (ev/upsert-msg msgs {:id 9 :gone true}))))
    (testing "a queued message taken in moves to the end"
      (is (= [{:id 0} {:id 1 :text "a"} {:id 2 :text "b"}]
             (ev/upsert-msg [{:id 2 :queued true} {:id 0} {:id 1 :text "a"}] {:id 2 :text "b"}))))))

(deftest append-delta-streams-text
  (is (= [{:id 1 :text "ab"}] (ev/append-delta [{:id 1 :text "a"}] 1 "b")))
  (is (= [{:id 7 :role "assistant" :text "hi" :streaming true}] (ev/append-delta nil 7 "hi"))))

(deftest drop-echo-takes-the-oldest-match
  (is (= ["b" "a"] (ev/drop-echo ["a" "b" "a"] " a ")))
  (is (= ["a"] (ev/drop-echo ["a"] "x")))
  (is (= ["b"] (ev/drop-echoes ["a" "b"] [{:role "user" :text "a"} {:role "assistant" :text "b"}]))))

(deftest drop-echo-takes-a-merged-run
  (testing "messages Claude Code took in as one echo as their texts joined by line breaks"
    (is (= ["z"] (ev/drop-echo ["one" "two" "three" "z"] "one\ntwo\nthree")))
    (is (= ["x"] (ev/drop-echo ["x" "one" "two"] "one\n\n\ntwo ")) "blank lines a reminder left")
    (is (= ["one" "x"] (ev/drop-echo ["one" "x"] "one\ntwo")) "no run: nothing taken"))
  (is (= [1 3] (ev/echo-run ["a" "b" "c"] "b\nc")))
  (is (= "a\nb" (ev/echo-text "  a \n\n b  "))))

(def ^:private db
  {:me {:servers [{:name "one"} {:name "two"}]}
   :new-server "two"})

(deftest servers-of-the-person
  (is (= ["one" "two"] (ev/server-names db)))
  (is (= "two" (ev/default-server db)))
  (is (= "one" (ev/default-server (assoc db :new-server "gone"))))
  (is (nil? (ev/default-server {})))
  (is (= {"a" {:status :connecting :rows {}}} (ev/connecting-servers ["a"]))))

(deftest list-order-by-start
  (is (= 1 (ev/list-order {:started 1 :at 5})))
  (is (= 5 (ev/list-order {:at 5})))
  (is (= 0 (ev/list-order {}))))

(deftest running-since-keeps-the-start
  (is (= 10 (ev/running-since 10 true 99)))
  (is (= 99 (ev/running-since nil true 99)))
  (is (nil? (ev/running-since 10 false 99))))

(deftest new-chat-text-only-on-new
  (is (= "hi there" (ev/new-chat-text "/new" "?text=hi%20there")))
  (is (= "" (ev/new-chat-text "/new" "")))
  (is (nil? (ev/new-chat-text "/" "?text=x"))))

(deftest start-db-opens-or-starts-a-new-chat
  (is (= :chat (:screen (ev/start-db ["s" "c"] nil))))
  (is (= :list (:screen (ev/start-db nil nil))))
  (let [d (ev/start-db nil "draft")]
    (is (= :chat (:screen d)))
    (is (nil? (:view d)))
    (is (= "draft" (get-in d [:drafts :new])))))

(deftest hello-frames-reopen-the-open-chat
  (is (= [{:type "hello"} {:type "open" :chat "c"}] (ev/hello-frames ["s" "c"] "s" nil)))
  (is (= [{:type "hello"}] (ev/hello-frames ["s" "c"] "t" nil)))
  (is (= [{:type "hello"}] (ev/hello-frames nil "s" nil)))
  (is (= [{:type "hello"}] (ev/hello-frames ["s" nil] "s" nil)) "a new chat not made yet: nothing to open")
  (is (= [{:type "hello"} {:type "here" :ago 5} {:type "open" :chat "c"}]
         (ev/hello-frames ["s" "c"] "s" {:type "here" :ago 5}))
      "a touched page says when, before the chat opens"))

(deftest an-opened-socket-says-hello-on-itself
  (let [start {:open ["s" "c"] :servers {"s" {:status :connecting}}}
        fx (ev/opened-fx start "s" 1000)]
    (is (= ["s" [{:type "hello"} {:type "open" :chat "c"}]] (:ws/hello fx)))
    (is (= :open (get-in fx [:db :servers "s" :status])))
    (is (= [[:dispatch [:version/check]]] (:fx fx)) "not down before: no Connected")
    (is (= ["t" [{:type "hello"}]] (:ws/hello (ev/opened-fx start "t" 1000))) "another server's socket")))

(deftest connected-shows-after-a-long-outage
  (let [start {:servers {"s" {:status :closed :down-since 0}}}]
    (is (= [:dispatch-later {:ms ev/back-shown-ms :dispatch [:ws/back-shown "s"]}]
           (second (:fx (ev/opened-fx start "s" ev/back-after-ms)))))
    (is (= 1 (count (:fx (ev/opened-fx start "s" (dec ev/back-after-ms))))) "a blip")))

(deftest chat-of-frame-keeps-the-chat-fields
  (is (= {:title "T" :messages [{:id 1}] :loaded true}
         (ev/chat-of-frame {:type "chat" :chat "c" :title "T" :messages (list {:id 1})}))))

(deftest created-moves-the-pending-chat
  (let [d {:pending-new {"p" {:text "hi"}} :outbox {["s" "p"] ["hi"]} :open ["s" "p"]
           :new-settings {:model "m" :color "red"}}
        after (ev/created-db d "s" "c" "p" "hi")]
    (is (= {} (:pending-new after)))
    (is (= {["s" "c"] ["hi"]} (:outbox after)))
    (is (= ["s" "c"] (:open after)))
    (is (= {:model "m"} (:new-settings after)) "kept for the next new chat, all but the colour")
    (is (= ["s" "x"] (:open (ev/created-db (assoc d :open ["s" "x"]) "s" "c" "p" "hi"))))
    (is (= [["s" {:type "open" :chat "c"}]] (ev/created-frames "s" "c")))))

(deftest a-chat-started-from-a-groups-plus-goes-into-that-group
  (let [d {:new-group "g" :new-settings {:model "m" :color "red"} :drafts {} :attach {}}
        sent (ev/send-new-chat d "s" "p" {:sent "hi" :text "hi"})
        pending (get-in sent [:db :pending-new "p"])]
    (is (= {:server "s" :text "hi" :group "g" :picks {:model "m" :color "red" :server "s"}} pending))
    (is (nil? (:new-group (:db sent))) "only that one chat")
    (is (= [[:dispatch [:order/created "s/c" "g" {:model "m" :server "s"}]]]
           (:fx (ev/on-frame (:db sent) "s" {:type "created" :chat "c" :id "p"}))))
    (is (nil? (:fx (ev/on-frame (assoc-in (:db sent) [:pending-new "p"] {:server "s" :text "hi"}) "s"
                                {:type "created" :chat "c" :id "p"})))
        "a chat not started from a group stays where new chats go")
    (is (nil? (:new-group (:db (ev/new-chat d {} "s")))) "a plain + starts outside the group")))

(deftest a-new-chat-gets-a-colour-at-once
  (is (= "c" (get-in (ev/with-fresh-colour {:new-settings {:color "c"}}) [:new-settings :color])))
  (let [rows {"1" {:id "1" :color "cherry"}}
        picked (get-in (ev/with-fresh-colour {:me {:servers [{:name "s"}]} :servers {"s" {:rows rows}}}) [:new-settings :color])]
    (is (= (colours/most-different (vals rows) nil) picked))
    (is (not= "cherry" picked))))

(deftest the-new-chat-takes-its-colour-when-the-list-comes
  (let [d {:me {:servers [{:name "s"}]} :new-settings {}}
        rows [{:id "1" :color "cherry" :archived true :started 1}]
        after (:db (ev/on-frame d "s" {:type "chats" :chats rows}))]
    (is (= (colours/most-different rows nil) (get-in after [:new-settings :color])))
    (is (not= "cherry" (get-in after [:new-settings :color]))))
  (let [d {:me {:servers [{:name "s"}]} :new-settings {:color "mint"}}]
    (is (= "mint" (get-in (:db (ev/on-frame d "s" {:type "chats" :chats []})) [:new-settings :color]))
        "a colour picked by hand stays"))
  (let [d {:me {:servers [{:name "s"}]} :new-settings {} :open ["s" "1"]}]
    (is (nil? (get-in (:db (ev/on-frame d "s" {:type "chats" :chats []})) [:new-settings :color]))
        "a chat open: picked when the new chat opens")))

(deftest a-new-chat-carries-its-picks
  (is (= {:model "m" :plan "p" :folder "/w"} (ev/new-picks {:model "m" :effort nil :plan "p" :folder "/w" :x 1})))
  (is (= {:color "teal"} (ev/new-picks {:color "teal"})))
  (is (= {:prompt "Reviewer"} (ev/new-picks {:prompt "Reviewer" :model nil})))
  (is (= {} (ev/new-picks {:folder ""})))
  (is (= {} (ev/new-picks nil)))
  (is (= ["s" {:type "new" :id "id" :text "hi" :effort "high"}]
         (:ws/send (ev/send-new-chat {:new-settings {:effort "high"}} "s" "id" (ev/outgoing nil "hi"))))))

(deftest errors-go-once-they-no-longer-hold
  (is (= {:chats {["s" "c"] {:x 1}}} (ev/drop-chat-error {:chats {["s" "c"] {:x 1 :error "e"}}} ["s" "c"])))
  (is (= {} (ev/drop-chat-error {} ["s" "c"])))
  (is (nil? (:error (ev/chat-after-turn {:error "e"} true false))) "a new turn")
  (is (= "e" (:error (ev/chat-after-turn {:error "e"} false false))) "the failed turn's end keeps it")
  (is (= {} (ev/without-stale-error {:error "s: x" :error-from "s"} "s" {:type "chats"})))
  (is (= {:error "s: x" :error-from "s"} (ev/without-stale-error {:error "s: x" :error-from "s"} "t" {:type "chats"})))
  (is (= {:error "s: x" :error-from "s"} (ev/without-stale-error {:error "s: x" :error-from "s"} "s" {:type "error"})))
  (is (nil? (:error (:db (ev/send-in-chat {:chats {["s" "c"] {:error "e"}}} ["s" "c"] (ev/outgoing nil "hi")))))))

(deftest chat-after-turn-settles-an-ended-turn
  (is (= {:running true :stopped false :note "n" :messages [{:streaming true}]}
         (ev/chat-after-turn {:note "n" :messages [{:streaming true}]} true true)))
  (is (= {:running false :stopped true :messages [{:id 1}]}
         (ev/chat-after-turn {:note "n" :messages [{:id 1 :streaming true}]} false true)))
  (is (= {:running false :stopped false :messages []} (ev/chat-after-turn nil false nil))))

(deftest remove-ask-by-id
  (is (= [{:id 2}] (ev/remove-ask [{:id 1} {:id 2}] 1))))

(deftest navigation
  (is (= {:screen :chat :archived? false :search {:text "x"} :project-filter nil}
         (ev/on-chat-screen {:archived? true :search {:text "x"} :project-filter "/w/a"}))
      "the archived chats close; Search keeps its words (it stays in the right half)")
  (is (= [["s" {:type "close" :chat "a"}] ["t" {:type "open" :chat "b"}]] (ev/switch-frames ["s" "a"] ["t" "b"])))
  (is (= [["t" {:type "open" :chat "b"}]] (ev/switch-frames nil ["t" "b"])))
  (is (= [] (ev/switch-frames nil nil))))

(deftest outgoing-joins-notes-text-and-files
  (let [file {:path "/w/a.txt" :name "a.txt" :size 1}
        out (ev/outgoing [{:note "note"} {:file file} {:images [{:data "x"}]}] "  hi ")]
    (is (= "note\n\nhi" (:sent out)))
    (is (= (files/with-files "note\n\nhi" [file]) (:text out)))
    (is (= {:files [file] :images [{:data "x"}]} (:extra out))))
  (is (= {:sent "" :text "" :extra {}} (ev/outgoing nil nil))))

(deftest hold-send-when-nothing-or-not-yet
  (let [out (ev/outgoing nil "hi")]
    (is (false? (ev/hold-send? {} "s" "c" nil out)))
    (is (true? (ev/hold-send? {} "s" "c" nil (ev/outgoing nil " "))))
    (is (false? (ev/hold-send? {} "s" "c" [{:images [{:data "x"}]}] (ev/outgoing [{:images [{:data "x"}]}] ""))))
    (is (true? (ev/hold-send? {} "s" "c" [{:busy true}] out)))
    (is (true? (ev/hold-send? {:view "pat"} "s" "c" nil out)))
    (is (false? (ev/hold-send? {:view "pat" :server-me {"s" {:writable ["pat"]}}} "s" "c" nil out))
        "a write tick for pat on s lets it send")
    (is (true? (ev/hold-send? {:view "pat" :server-me {"t" {:writable ["pat"]}}} "s" "c" nil out))
        "a write tick holds per server")
    (is (true? (ev/hold-send? {:pending-new {"c" {}}} "s" "c" nil out)))))

(deftest sending
  (let [out (ev/outgoing nil "hi")]
    (is (= {:db {:outbox {["s" "c"] ["hi"]} :drafts {["s" "c"] ""} :attach nil}
            :ws/send ["s" {:type "send" :chat "c" :text "hi"}]}
           (ev/send-in-chat {} ["s" "c"] out)))
    (let [fx (ev/send-new-chat {:new-upload-id "u"} "s" "id" out)]
      (is (= {:pending-new {"id" {:server "s" :text "hi"}} :open ["s" "id"] :outbox {["s" "id"] ["hi"]}
              :drafts {:new ""} :attach nil :new-server "s"}
             (:db fx)))
      (is (= ["s" {:type "new" :id "id" :text "hi"}] (:ws/send fx))))
    (is (nil? (ev/send-new-chat {} nil "id" out)))))

(deftest next-open-after-archiving
  (let [servers {"s" {:rows {"a" {:started 1} "b" {:started 2} "c" {:started 0 :archived true}}}}]
    (is (= ["s" "b"] (ev/next-open servers ["s" "a"])))
    (is (= ["s" "a"] (ev/next-open servers ["s" "b"])))
    (is (nil? (ev/next-open {"s" {:rows {"a" {}}}} ["s" "a"])))))

(deftest seen-due-once-the-turn-is-on-screen
  (let [d {:open ["s" "c"] :screen :chat
           :servers {"s" {:rows {"c" {:unseen true :at 5}}}}
           :chats {["s" "c"] {:loaded true}}}]
    (is (true? (ev/seen-due? d false true)))
    (is (false? (ev/seen-due? d false false)))
    (is (false? (ev/seen-due? (assoc d :screen :list) true true)))
    (is (true? (ev/seen-due? (assoc d :screen :list) false true)))
    (is (false? (ev/seen-due? (assoc-in d [:chats ["s" "c"] :running] true) false true)))
    (is (false? (ev/seen-due? (assoc-in d [:seen-sent ["s" "c"]] 5) false true)))
    (is (false? (ev/seen-due? (assoc d :view "pat") false true)))))

(deftest the-mic-text-goes-to-the-chat-it-was-held-in
  (let [db {:open ["s" "other"] :drafts {["s" "c"] "half" ["s" "other"] "mine"}
            :servers {"s" {:status :open}}}]
    (testing "added: it joins that chat's draft, never the open one's"
      (let [out (:db (ev/heard-fx db ["s" "c"] {:text "more" :raw "more"} :add))]
        (is (= "half more" (get-in out [:drafts ["s" "c"]])))
        (is (= "mine" (get-in out [:drafts ["s" "other"]])))))
    (testing "sent: into that chat, with its draft"
      (let [fx (ev/heard-fx db ["s" "c"] {:text "more" :raw "more"} :send)]
        (is (= ["s" {:type "send" :chat "c" :text "half more"}] (update (:ws/send fx) 1 dissoc :msg)))
        (is (= "mine" (get-in fx [:db :drafts ["s" "other"]])))))
    (testing "sent now: the runner stops the running turn first"
      (is (= ["s" {:type "send" :chat "c" :text "half more" :now true}]
             (update (:ws/send (ev/heard-fx db ["s" "c"] {:text "more" :raw "more"} :now)) 1 dissoc :msg))))
    (testing "a question open in that chat is answered"
      (let [db (assoc-in db [:chats ["s" "c"] :asks] [{:id "q" :kind "question" :questions [{:question "Which?"}]}])
            fx (ev/heard-fx db ["s" "c"] {:text "this one" :raw "this one"} :send)]
        (is (= [[:dispatch [:ask/answer ["s" "c"] "q" {"Which?" "half this one"}]]] (:fx fx)))))))

(deftest dictated-words-are-kept-and-checked-at-send
  (let [db {:open ["s" "c"] :drafts {["s" "c"] ""} :servers {"s" {:status :open}}}
        added (:db (ev/heard-fx db ["s" "c"] {:text "call mission now" :raw "call mission now um"} :add))]
    (is (= [{:text "call mission now" :raw "call mission now um" :shown :text}] (get-in added [:dictated ["s" "c"]])))
    (testing "sent with one word corrected to a name: offered, and the pieces forgotten"
      (let [fx (ev/with-dictation-checked {:db added} added ["s" "c"] "call Dorka now")]
        (is (= [[:dispatch [:words/maybe-offer "Dorka"]]] (:fx fx)))
        (is (nil? (get-in fx [:db :dictated ["s" "c"]])))))
    (testing "sent unchanged: nothing offered"
      (is (nil? (:fx (ev/with-dictation-checked {:db added} added ["s" "c"] "call mission now")))))
    (testing "nothing sent: nothing changes"
      (is (= {} (ev/with-dictation-checked {} added ["s" "c"] "call Dorka now"))))))

(deftest a-new-chat-carries-the-last-chats-picks
  (let [db {:chats {["s" "c"] {:settings {:plan "max" :model "opus" :effort "high" :other 1} :folder "/w/ir"}}
            :servers {"s" {:rows {"c" {:voice "nora" :bell false :color "teal"}}}}
            :new-settings {:plan "pro" :color "red" :folder "/old"}}]
    (is (= {:model "opus" :effort "high" :voice "nora" :bell false :folder "/w/ir"}
           (ev/carried-picks db ["s" "c"])) "from the open chat, its folder too, never its colour or subscription")
    (is (= {:voice "nora" :bell false :folder "/w/work"} (ev/carried-picks (-> db (update-in [:chats ["s" "c"]] dissoc :folder) (assoc :work-folders {"s" "/w/work"}) (assoc-in [:chats ["s" "c"] :settings] {})) ["s" "c"]))
        "a chat without a folder: its computer's work folder")
    (is (= {:folder "/old"} (ev/carried-picks db nil)) "no chat open: the last new chat's, without colour or subscription")))

(deftest a-new-chats-first-message-needs-a-folder
  (let [db {:me {:servers [{:name "s"}]} :new-server "s" :servers {"s" {:status :open}}
            :drafts {:new "hello"} :new-settings {}}]
    (is (= [[:dispatch [:folders/open {:chat nil :send? true}]]] (:fx (ev/send-for db :new "hello")))
        "no folder: Files opens in the right half, nothing sent")
    (is (nil? (:ws/send (ev/send-for db :new "hello"))))
    (is (= "new" (get-in (ev/send-for (assoc-in db [:new-settings :folder] "/w/app") :new "hello") [:ws/send 1 :type]))
        "with a folder it goes")))

(deftest dictated-clips-are-tied-to-the-message-they-went-out-in
  (let [base {:open ["s" "c"] :drafts {["s" "c"] ""} :servers {"s" {:status :open}}}
        added (:db (ev/heard-fx base ["s" "c"] {:text "one" :raw "one" :clip "c1"} :add))
        fx (ev/heard-fx added ["s" "c"] {:text "two" :raw "two" :clip "c2"} :send)
        msg (get-in fx [:ws/send 1 :msg])]
    (is (= ["c1"] (get-in added [:voice-draft ["s" "c"]])))
    (is (string? msg) "every send carries the id the page made for its message")
    (is (= [:voice-back/tag [["c1" "c2"] msg]] (last (:fx fx))))
    (is (nil? (get-in fx [:db :voice-draft ["s" "c"]])))
    (testing "nothing sent: nothing tied"
      (is (= {} (ev/with-voice-tagged {} added ["s" "c"]))))))

(deftest a-keys-ask-is-answered-without-a-card
  (let [fx (ev/on-frame {:chats {}} "s" {:type "keys_ask" :chat "c" :ask "a"})]
    (is (= {:server "s" :chat "c" :ask "a"} (:keys/answer fx)))
    (is (= {:chats {}} (:db fx)) "no ask card is added")))

(deftest a-speak-frame-is-a-reply-to-read
  (is (= {:id "s/c/t1" :k ["s" "c"] :voice "nora" :text "Red here. It works."}
         (ev/speak-item "s" {:chat "c" :id "t1" :voice "nora" :text "**It works.**"} "cherry")))
  (is (= {:id "s/c/ask-a" :k ["s" "c"] :voice "otto" :text "Lagoon here. Which one?"}
         (ev/speak-item "s" {:chat "c" :id "ask-a" :voice "otto" :ask {:questions [{:question "Which one?"}]}} "lagoon")))
  (is (= "It works." (:text (ev/speak-item "s" {:chat "c" :id "t3" :voice "nora" :text "It works."} nil))) "no colour: no name")
  (is (nil? (ev/speak-item "s" {:chat "c" :id "t2" :voice "nora" :text "  "} "cherry")) "nothing to say"))

(def ^:private two-servers
  {:me {:servers [{:name "a"} {:name "b"}]}
   :servers {"a" {:status :open :rows {"1" {:id "1" :color "cherry" :started 1}
                                       "2" {:id "2" :color "ocean" :started 2}
                                       "3" {:id "3" :color "ocean" :started 9 :archived true}}}
             "b" {:status :open :rows {"4" {:id "4" :color "cherry" :started 5}}}}})

(deftest no-two-open-chats-wear-one-colour
  (let [plan (ev/recolour-plan (ev/all-rows two-servers))]
    (is (= [["b" "4"]] (map first plan)) "the newer of the two red chats, on another server")
    (is (not (#{"cherry" "ocean"} (second (first plan)))) "it gets a free colour"))
  (is (ev/lists-complete? two-servers))
  (is (not (ev/lists-complete? (assoc-in two-servers [:servers "b" :status] :closed))) "a list still missing: wait")
  (is (not (ev/lists-complete? (assoc two-servers :view "peter"))) "someone else's chats: never"))

(deftest a-new-chat-takes-a-free-colour-from-every-server
  (let [c (get-in (ev/with-fresh-colour two-servers) [:new-settings :color])]
    (is (not (#{"cherry" "ocean"} c))))
  (is (= "jade" (get-in (ev/with-fresh-colour (assoc two-servers :new-settings {:color "jade"})) [:new-settings :color]))
      "a free colour picked stays")
  (is (not= "ocean" (get-in (ev/with-fresh-colour (assoc two-servers :new-settings {:color "ocean"})) [:new-settings :color]))
      "a colour an open chat wears is picked again"))

(deftest a-chat-back-from-the-archive-keeps-its-colour-unless-taken
  (is (some? (ev/back-colour two-servers ["a" "3"])) "ocean is worn by an open chat")
  (is (nil? (ev/back-colour (assoc-in two-servers [:servers "a" :rows "3" :color] "jade") ["a" "3"]))))
