(ns claudia.shared.order-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.shared.order :as o]))

(defn- row [s id started] {:server s :id id :started started})
(def rows [(row "s" "a" 1) (row "s" "b" 2) (row "s" "c" 3) (row "s" "d" 4)])

(defn- shape [arranged]
  (mapv #(if (= :group (:kind %)) [(:name (:group %)) (mapv :id (:rows %))] (:id (:row %))) arranged))

(deftest new-chats-first-newest-first-then-the-persons-order
  (is (= ["d" "c" "b" "a"] (shape (o/arrange rows [] :started))) "nothing arranged: newest first")
  (is (= ["d" "b" "a" "c"] (shape (o/arrange rows [{:chat "s/b"} {:chat "s/a"} {:chat "s/c"}] :started)))
      "d is new: on top; the rest as placed, whatever their times"))

(deftest a-new-message-moves-nothing
  (let [items [{:chat "s/a"} {:chat "s/b"}]
        later (assoc (row "s" "b" 2) :at 999)]
    (is (= ["a" "b"] (shape (o/arrange [(row "s" "a" 1) later] items :started))))))

(deftest placing-moves-a-chat-before-another-or-to-the-end
  (let [items [{:chat "s/a"} {:chat "s/b"} {:chat "s/c"}]]
    (is (= [{:chat "s/c"} {:chat "s/a"} {:chat "s/b"}] (o/place items "s/c" "s/a")))
    (is (= [{:chat "s/b"} {:chat "s/c"} {:chat "s/a"}] (o/place items "s/a" nil)))
    (is (= [{:chat "s/a"} {:chat "s/b"} {:chat "s/c"} {:chat "s/d"}] (o/place items "s/d" nil)) "a new chat placed")))

(deftest dropping-onto-a-chat-makes-a-group-where-it-was
  (let [g (o/group-with [{:chat "s/a"} {:chat "s/b"} {:chat "s/c"}] "s/c" "s/a" "g1" "IR")]
    (is (= [{:group "g1" :name "IR" :chats ["s/a" "s/c"] :folded false :picks {}} {:chat "s/b"}] g))
    (is (= [["IR" ["a" "c"]] "b"] (shape (o/arrange (take 3 rows) g :started))))
    (is (= {:group "g1" :name "IR" :chats ["s/a" "s/c" "s/b"] :folded false :picks {}}
           (first (o/into-group g "s/b" "g1" nil))))
    (is (= [{:chat "s/c"} {:group "g1" :name "IR" :chats ["s/a"] :folded false :picks {}} {:chat "s/b"}]
           (o/place g "s/c" "g1")) "dragged out, before the group")
    (is (= [{:chat "s/b"}] (o/without (o/without g "s/a") "s/c")) "the last chat out ends the group")))

(deftest a-fresh-chat-dropped-onto-a-fresh-chat
  (is (= [{:group "g" :name "N" :chats ["s/a" "s/b"] :folded false :picks {}}] (o/group-with [] "s/b" "s/a" "g" "N"))))

(deftest only-well-formed-lists-are-kept
  (is (= [{:chat "s/a"} {:group "g" :name "IR" :chats ["s/b"] :folded true :picks {:folder "/w"}}]
         (o/clean-items [{:chat "s/a"} {:chat "../etc"} {:group "g" :name " IR " :chats ["s/b" 7] :folded true
                                                         :picks {:folder "/w" :evil 1}} {:x 1}]))))

(deftest settle-places-the-fresh-chats-on-top-as-shown
  (is (= [{:chat "s/d"} {:chat "s/c"} {:chat "s/a"} {:chat "s/b"}]
         (o/settle [{:chat "s/b"}] rows :started)))
  (is (= (o/arrange rows [{:chat "s/b"}] :started)
         (o/arrange rows (o/settle [{:chat "s/b"}] rows :started) :started))))

(deftest a-reference-names-its-chat-and-a-group-is-found-by-id
  (is (= ["s" "a-1"] (o/chat-key "s/a-1")))
  (is (nil? (o/chat-key "nothing")))
  (is (= "g" (:group (o/find-group [{:chat "s/a"} {:group "g" :chats ["s/b"]}] "g"))))
  (is (nil? (o/find-group [{:chat "s/a"}] "g"))))

(def ^:private grouped [{:chat "s/a"} {:group "g" :name "G" :chats ["s/b" "s/c"]} {:chat "s/d"}])

(deftest a-chat-taken-out-goes-just-after-its-group
  (is (= [{:chat "s/a"} {:group "g" :name "G" :chats ["s/c"]} {:chat "s/b"} {:chat "s/d"}]
         (o/take-out grouped "s/b")))
  (is (= grouped (o/take-out grouped "s/a")))
  (is (= [{:chat "s/a"} {:chat "s/b"} {:chat "s/d"}]
         (o/take-out [{:chat "s/a"} {:group "g" :name "G" :chats ["s/b"]} {:chat "s/d"}] "s/b"))))

(deftest ungroup-puts-the-chats-where-the-group-was
  (is (= [{:chat "s/a"} {:chat "s/b"} {:chat "s/c"} {:chat "s/d"}] (o/ungroup grouped "g")))
  (is (= grouped (o/ungroup grouped "nope"))))

(def ^:private with-group
  [{:chat "s/a"} {:group "g1" :name "G" :chats ["s/b" "s/c"] :folded false :picks {}} {:chat "s/d"}])

(defn- keys-of [items] (mapv #(or (:group %) (:chat %)) items))

(deftest a-group-moves-whole
  (is (= ["g1" "s/a" "s/d"] (keys-of (o/place-group with-group "g1" "s/a"))))
  (is (= ["s/a" "s/d" "g1"] (keys-of (o/place-group with-group "g1" nil))))
  (is (= with-group (o/place-group with-group "nope" nil)) "no such group: as it was")
  (is (o/top-key? with-group "g1"))
  (is (not (o/top-key? with-group "s/b")) "a chat in a group is not a top-level key"))

(deftest a-new-group-sits-where-its-first-chat-was
  (is (= [{:chat "s/a"} {:group "g2" :name "N" :chats ["s/b" "s/c"] :folded false :picks {}} {:chat "s/d"}]
         (o/new-group with-group ["s/b" "s/c"] "g2" "N"))
      "the old group left empty goes; the new one takes its place")
  (is (= [{:chat "s/a"} {:group "g1" :name "G" :chats ["s/c"] :folded false :picks {}}
          {:group "g2" :name "N" :chats ["s/d" "s/b"] :folded false :picks {}}]
         (o/new-group with-group ["s/d" "s/b"] "g2" "N"))
      "its chats are taken out of where they were")
  (is (= "s/x" (first (:chats (first (o/new-group with-group ["s/x"] "g3" "X"))))) "a chat not placed: at the top"))
