(ns claudia.web.world.state-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.world.state :as world]))

(deftest toggling-the-world
  (is (:world? (world/toggled {} nil)))
  (is (= {:world? false :world-chat? false} (world/toggled {:world? true :world-chat? true} nil)))
  (is (:world-chat? (world/toggled {:world? false :world-chat? true} true))))

(deftest a-click-in-the-world
  (let [robot-fx (world/pick-fx {} {:robot {:key ["home" "a"]} :rect {:left 1 :top 2 :width 3 :height 4}})]
    (is (:world-chat? (:db robot-fx)))
    (is (= {:left 1 :top 2 :width 3 :height 4} (:world-from (:db robot-fx))))
    (is (= [[:dispatch [:nav/open ["home" "a"]]]] (:fx robot-fx))))
  (is (= [[:dispatch [:new/server "home"]] [:dispatch [:nav/new]]]
         (:fx (world/pick-fx {} {:island "home" :kind "server"}))))
  (is (= {} (world/pick-fx {} {:island "gate" :kind "gate"}))))

(deftest the-view-follows-the-open-chat-only-while-its-panel-is-open
  (is (= "home/a" (world/focus-id {:open ["home" "a"] :world-chat? true})))
  (is (nil? (world/focus-id {:open ["home" "a"] :world-chat? false})))
  (is (nil? (world/focus-id {:open nil :world-chat? true}))))

(deftest key-actions
  (is (= :toggle (world/key-action {:alt? true :code "KeyW"} false)))
  (is (= :back (world/key-action {:code "Escape"} true)))
  (is (nil? (world/key-action {:code "Escape"} false))))

(deftest the-panel-flies-from-the-screen
  (is (= "translate(-100px, 50px) scale(0.5, 0.25)"
         (world/fly-transform {:left 100 :top 150 :width 200 :height 100}
                              {:left 200 :top 100 :width 400 :height 400}))))
