(ns claudia.web.views.chat-drag-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.views.chat-drag :as drag]))

(def items [{:chat "s/a"} {:group "g" :name "G" :chats ["s/b" "s/c"]} {:chat "s/d"}])

(deftest a-drop-changes-the-list-as-its-target-says
  (is (= [{:chat "s/d"} {:chat "s/a"} {:group "g" :name "G" :chats ["s/b" "s/c"]}]
         ((drag/change-for "s/d" [:place "s/a"] "new") items)))
  (is (= [{:group "g" :name "G" :chats ["s/b" "s/a" "s/c"]} {:chat "s/d"}]
         ((drag/change-for "s/a" [:into "g" "s/c"] "new") items)))
  (is (= [{:group "new" :name "New group" :chats ["s/a" "s/d"] :folded false :picks {}}
          {:group "g" :name "G" :chats ["s/b" "s/c"]}]
         ((drag/change-for "s/d" [:group "s/a"] "new") items)))
  (is (= items ((drag/change-for "s/d" nil "new") items))))

(deftest the-mark-shows-where-the-drop-lands
  (is (= "drop-onto" (drag/mark-class [:group "s/a"] :onto false)))
  (is (= "drop-into" (drag/mark-class [:into "g" nil] :onto true)))
  (is (= "drop-before" (drag/mark-class [:place "g"] :before true)))
  (is (= "drop-after" (drag/mark-class [:into "g" "s/c"] :after false))))

(deftest where-in-a-chat-decides
  (is (= :before (drag/zone-of 0.1)))
  (is (= :onto (drag/zone-of 0.5)))
  (is (= :after (drag/zone-of 0.9))))

(deftest a-drop-target-from-what-is-under-the-pointer
  (testing "over the dragged chat itself: nothing"
    (is (nil? (drag/target-for {:ref "s/a" :zone :onto} "s/a"))))
  (testing "a top-level chat: before, after (the next key), onto = a new group"
    (is (= [:place "s/b"] (drag/target-for {:ref "s/b" :zone :before :after "g"} "s/a")))
    (is (= [:place "g"] (drag/target-for {:ref "s/b" :zone :after :after "g"} "s/a")))
    (is (= [:group "s/b"] (drag/target-for {:ref "s/b" :zone :onto :after "g"} "s/a"))))
  (testing "a group (the list's header, the rail's stack): its edges place, its middle goes into it"
    (is (= [:place "g"] (drag/target-for {:group "g" :zone :before :after "s/d"} "s/a")))
    (is (= [:place "s/d"] (drag/target-for {:group "g" :zone :after :after "s/d"} "s/a")))
    (is (= [:into "g" nil] (drag/target-for {:group "g" :zone :onto :after "s/d"} "s/a"))))
  (testing "a chat in a group: before or after it in that group, never a group in a group"
    (is (= [:into "g" "s/b"] (drag/target-for {:ref "s/b" :in "g" :zone :before :after "s/c"} "s/a")))
    (is (= [:into "g" "s/c"] (drag/target-for {:ref "s/b" :in "g" :zone :onto :after "s/c"} "s/a")))
    (is (= [:into "g" nil] (drag/target-for {:ref "s/c" :in "g" :zone :after :after nil} "s/a")))))

(deftest only-a-mouse-tears-a-chat-out-and-only-outside-its-home
  (is (true? (drag/tears? {:tear? true :touch? false :home? false})))
  (is (false? (drag/tears? {:tear? true :touch? false :home? true})))
  (is (false? (drag/tears? {:tear? true :touch? true :home? false})))
  (is (false? (drag/tears? {:tear? false :touch? false :home? false}))))
