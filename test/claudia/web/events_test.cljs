(ns claudia.web.events-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.shared.colours :as colours]
            [claudia.shared.files :as files]
            [claudia.web.events :as ev]))

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
  (is (= [["s" {:type "hello"}] ["s" {:type "open" :chat "c"}]] (ev/hello-frames ["s" "c"] "s")))
  (is (= [["t" {:type "hello"}]] (ev/hello-frames ["s" "c"] "t")))
  (is (= [["s" {:type "hello"}]] (ev/hello-frames nil "s"))))

(deftest chat-of-frame-keeps-the-chat-fields
  (is (= {:title "T" :messages [{:id 1}] :loaded true}
         (ev/chat-of-frame {:type "chat" :chat "c" :title "T" :messages (list {:id 1})}))))

(deftest created-moves-the-pending-chat
  (let [d {:pending-new {"p" {:text "hi"}} :outbox {["s" "p"] ["hi"]} :open ["s" "p"]
           :new-settings {:model "m"}}
        after (ev/created-db d "s" "c" "p" "hi")]
    (is (= {} (:pending-new after)))
    (is (= {["s" "c"] ["hi"]} (:outbox after)))
    (is (= ["s" "c"] (:open after)))
    (is (nil? (:new-settings after)))
    (is (= ["s" "x"] (:open (ev/created-db (assoc d :open ["s" "x"]) "s" "c" "p" "hi"))))
    (is (= [["s" {:type "open" :chat "c"}]] (ev/created-frames "s" "c")))))

(deftest a-new-chat-gets-a-colour-at-once
  (is (= "c" (get-in (ev/with-fresh-colour {:new-settings {:color "c"}}) [:new-settings :color])))
  (let [rows {"1" {:id "1" :color "cherry"}}
        picked (get-in (ev/with-fresh-colour {:me {:servers [{:name "s"}]} :servers {"s" {:rows rows}}}) [:new-settings :color])]
    (is (= (colours/most-different (vals rows) nil) picked))
    (is (not= "cherry" picked))))

(deftest a-new-chat-carries-its-picks
  (is (= {:model "m" :plan "p" :folder "/w"} (ev/new-picks {:model "m" :effort nil :plan "p" :folder "/w" :x 1})))
  (is (= {:color "teal"} (ev/new-picks {:color "teal"})))
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
  (is (= {:screen :chat :sidebar? false :rail-search? false :settings? false
          :archived? false :search nil :project-filter nil}
         (ev/on-chat-screen {:sidebar? true :settings? true :archived? true :search {:text "x"} :project-filter "/w/a"})))
  (is (= [["s" {:type "close" :chat "a"}] ["t" {:type "open" :chat "b"}]] (ev/switch-frames ["s" "a"] ["t" "b"])))
  (is (= [["t" {:type "open" :chat "b"}]] (ev/switch-frames nil ["t" "b"])))
  (is (= [] (ev/switch-frames nil nil))))

(deftest toggle-sidebar-from-the-rail-and-the-drawer
  (is (= {:sidebar? true :rail-search? false} (ev/toggle-sidebar {} nil)))
  (is (= {:sidebar? false :rail-search? false :settings? false} (ev/toggle-sidebar {:sidebar? true :settings? true} nil)))
  (is (= {:sidebar? false :rail-search? false :settings? false :search nil}
         (ev/toggle-sidebar {:sidebar? true :rail-search? true :search {:text "x"}} nil)))
  (is (= {:sidebar? true :rail-search? false} (ev/toggle-sidebar {:sidebar? true} true))))

(deftest outgoing-joins-notes-text-and-files
  (let [file {:path "/w/a.txt" :name "a.txt" :size 1}
        out (ev/outgoing [{:note "note"} {:file file} {:images [{:data "x"}]}] "  hi ")]
    (is (= "note\n\nhi" (:sent out)))
    (is (= (files/with-files "note\n\nhi" [file]) (:text out)))
    (is (= {:files [file] :images [{:data "x"}]} (:extra out))))
  (is (= {:sent "" :text "" :extra {}} (ev/outgoing nil nil))))

(deftest hold-send-when-nothing-or-not-yet
  (let [out (ev/outgoing nil "hi")]
    (is (false? (ev/hold-send? {} "c" nil out)))
    (is (true? (ev/hold-send? {} "c" nil (ev/outgoing nil " "))))
    (is (false? (ev/hold-send? {} "c" [{:images [{:data "x"}]}] (ev/outgoing [{:images [{:data "x"}]}] ""))))
    (is (true? (ev/hold-send? {} "c" [{:busy true}] out)))
    (is (true? (ev/hold-send? {:view "pat"} "c" nil out)))
    (is (true? (ev/hold-send? {:pending-new {"c" {}}} "c" nil out)))))

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
