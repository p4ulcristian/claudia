(ns claudia.web.views.chat-drag-test
  (:require [cljs.test :refer [deftest is]]
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
