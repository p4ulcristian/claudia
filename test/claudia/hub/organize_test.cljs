(ns claudia.hub.organize-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.hub.organize :as org]))

(def rows [{:server "box" :id "a" :started 1} {:server "box" :id "b" :started 2}
           {:server "mac" :id "c" :started 3} {:server "mac" :id "z" :started 4 :archived true}
           {:server "far" :error "unreachable"}])

(def known #{"box/a" "box/b" "mac/c" "mac/z"})

(deftest steps-are-checked-on-their-own
  (is (nil? (org/problem known {:action "edit" :chat "box/a" :title "New" :color "jade" :archived true})))
  (is (= "no chat box/x of yours" (org/problem known {:action "edit" :chat "box/x" :title "New"})) "only the person's chats")
  (is (= "give title, color or archived" (org/problem known {:action "edit" :chat "box/a"})))
  (is (= "archived: true or false" (org/problem known {:action "edit" :chat "box/a" :archived "yes"})))
  (is (= "title: over 200 characters" (org/problem known {:action "edit" :chat "box/a" :title (apply str (repeat 201 "x"))})))
  (is (re-find #"^color: one of" (org/problem known {:action "edit" :chat "box/a" :color "neon"})))
  (is (= "chats: at least one" (org/problem known {:action "new_group" :name "G" :chats []})))
  (is (= "no chat other/q of yours" (org/problem known {:action "new_group" :name "G" :chats ["box/a" "other/q"]})))
  (is (= "name: empty" (org/problem known {:action "rename_group" :group "g1" :name " "})))
  (is (= "name: over 60 characters" (org/problem known {:action "new_group" :name (apply str (repeat 61 "n")) :chats ["box/a"]})))
  (is (= "group: a group id" (org/problem known {:action "delete_group"})))
  (is (= "no chat box/x of yours" (org/problem known {:action "move" :item "box/x" :into "g1"})))
  (is (re-find #"^action: one of" (org/problem known {:action "explode"}))))

(deftest the-list-steps-run-in-turn-and-edits-become-calls
  (let [steps [{:action "new_group" :name " Work " :chats ["box/b" "mac/c"]}
               {:action "edit" :chat "box/a" :title " Tidy " :color "ash"}
               {:action "rename_group" :group "nope" :name "X"}
               {:action "move" :item "g-1" :before "box/a"}
               {:action "move" :item "box/a" :into "g-1" :before "mac/c"}
               {:action "edit" :chat "box/x" :title "No"}]
        p (org/plan rows [] steps ["g-1" "g-2" "g-3" "g-4" "g-5" "g-6"])]
    (is (= ["group g-1" nil "no group nope" "ok" "ok" "no chat box/x of yours"] (:results p)))
    (is (true? (:changed p)))
    (is (= [{:group "g-1" :name "Work" :chats ["box/b" "box/a" "mac/c"] :folded false :picks {}}] (:items p))
        "unplaced chats settled first (newest first), archived ones left out")
    (is (= [{:server "box" :chat "a" :body {:color "ash" :title "Tidy"} :i 1}] (:edits p)))))

(deftest only-edits-leave-the-list-alone
  (let [p (org/plan rows [{:chat "box/a"}] [{:action "edit" :chat "mac/z" :archived false}] ["g"])]
    (is (false? (:changed p)))
    (is (= [{:server "mac" :chat "z" :body {:archived false} :i 0}] (:edits p)))))

(deftest a-move-needs-a-place-that-is-there
  (let [items [{:chat "box/a"} {:chat "box/b"}]]
    (is (= {:error "nothing to put it before: box/zz"} (org/order-step items known {:action "move" :item "box/a" :before "box/zz"} "g")))
    (is (= {:items [{:chat "box/b"} {:chat "box/a"}]} (org/order-step items known {:action "move" :item "box/a"} "g")))
    (is (= {:error "no group g9"} (org/order-step items known {:action "delete_group" :group "g9"} "g")))))

(deftest the-call-as-a-whole
  (is (= "steps: a list" (org/steps-problem nil)))
  (is (= "steps: at least one" (org/steps-problem [])))
  (is (= "steps: at most 200" (org/steps-problem (vec (repeat 201 {})))))
  (is (nil? (org/steps-problem [{}])))
  (is (= "ok" (org/edit-result {:status 200 :body {:ok true}})))
  (is (= "no such chat" (org/edit-result {:status 404 :body {:error "no such chat"}})))
  (is (= "the server answered 502" (org/edit-result {:status 502 :body nil}))))
