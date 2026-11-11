(ns claudia.web.world.state-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.world.state :as world]))

(def rect {:left 1 :top 2 :width 3 :height 4})

(deftest toggling-the-world
  (is (:world? (world/toggled {} nil)))
  (is (= {:world? false} (world/toggled {:world? true :world-panel {:kind :chat} :world-from rect} nil)))
  (is (= {:kind :chat} (:world-panel (world/toggled {:world? false :world-panel {:kind :chat}} true)))))

(deftest a-click-in-the-world
  (let [robot-fx (world/pick-fx {} {:robot {:id "home/a" :key ["home" "a"]} :rect rect})]
    (is (= {:kind :chat :target "home/a"} (:world-panel (:db robot-fx))))
    (is (= rect (:world-from (:db robot-fx))))
    (is (= [[:dispatch [:nav/open ["home" "a"]]]] (:fx robot-fx))))
  (is (= {:kind :stats :target "home"} (:world-panel (:db (world/pick-fx {} {:board "home" :rect rect})))))
  (is (= [[:dispatch [:new/server "home"]] [:dispatch [:nav/new]]]
         (:fx (world/pick-fx {} {:island "home" :kind "server"}))))
  (is (= {} (world/pick-fx {} {:island "gate" :kind "gate"})))
  (is (= {} (world/pick-fx {} nil)) "the sky"))

(deftest a-click-beside-an-open-panel-closes-it
  (is (= {:world/close-panel true} (world/pick-fx {:world-panel {:kind :chat}} nil)))
  (is (= {:world/close-panel true} (world/pick-fx {:world-panel {:kind :stats}} {:island "home" :kind "server"})))
  (is (= [[:dispatch [:nav/open ["home" "b"]]]]
         (:fx (world/pick-fx {:world-panel {:kind :stats}} {:robot {:key ["home" "b"]}}))))
  (is (= :stats (get-in (world/pick-fx {:world-panel {:kind :chat}} {:board "home"}) [:db :world-panel :kind]))))

(deftest the-view-follows-the-open-chat-only-while-its-panel-is-open
  (is (= "home/a" (world/focus-id {:open ["home" "a"] :world-panel {:kind :chat}})))
  (is (nil? (world/focus-id {:open ["home" "a"] :world-panel {:kind :stats}})))
  (is (nil? (world/focus-id {:open ["home" "a"]})))
  (is (nil? (world/focus-id {:open nil :world-panel {:kind :chat}}))))

(deftest stats-come-from-the-hub
  (is (= "/hub/gate/system" (world/stats-url "gate")))
  (is (= "/hub/servers/a%20b/system" (world/stats-url "a b"))))

(deftest key-actions
  (is (= :toggle (world/key-action {:alt? true :code "KeyW"} false)))
  (is (= :back (world/key-action {:code "Escape"} true)))
  (is (nil? (world/key-action {:code "Escape"} false))))

(deftest the-panel-flies-from-the-screen
  (is (= "translate(-100px, 50px) scale(0.5, 0.25)"
         (world/fly-transform {:left 100 :top 150 :width 200 :height 100}
                              {:left 200 :top 100 :width 400 :height 400}))))
