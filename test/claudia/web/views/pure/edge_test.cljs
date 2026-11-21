(ns claudia.web.views.pure.edge-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.edge :as edge]))

(def ^:private idle {:running? false :new? true :folded? false :index 1 :n 3})

(deftest four-slots-over-the-middle-and-five-under-it-archive-under-new-chat
  (is (= [:search :system :prompts :settings] edge/over))
  (is (= [:stop :new :archive :prev :next] edge/under)))

(deftest every-slot-has-a-state-always
  (is (= (set (concat edge/over edge/under)) (set (keys (edge/slot-states {})))))
  (is (every? #{:shown :passive :red :on} (vals (edge/slot-states {})))))

(deftest stop-is-passive-while-nothing-runs-and-red-while-a-turn-runs
  (is (= :passive (:stop (edge/slot-states idle))))
  (is (= :red (:stop (edge/slot-states (assoc idle :running? true))))))

(deftest new-chat-is-passive-where-a-new-chat-cannot-be-written
  (is (= :passive (:new (edge/slot-states (assoc idle :new? false))))))

(deftest the-steps-act-only-towards-a-picture-that-is-there
  (is (= [:shown :shown] ((juxt :prev :next) (edge/slot-states idle))))
  (is (= [:passive :shown] ((juxt :prev :next) (edge/slot-states (assoc idle :index 0)))))
  (is (= [:shown :passive] ((juxt :prev :next) (edge/slot-states (assoc idle :index 2)))))
  (is (= [:passive :passive] ((juxt :prev :next) (edge/slot-states (assoc idle :index 0 :n 1)))))
  (is (= [:passive :passive] ((juxt :prev :next) (edge/slot-states (assoc idle :index nil :n 0))))))

(deftest a-folded-picture-makes-both-steps-passive
  (is (= [:passive :passive] ((juxt :prev :next) (edge/slot-states (assoc idle :folded? true))))))

(deftest search-system-prompts-and-settings-always-act
  (is (= [:shown :shown :shown :shown] ((juxt :search :system :prompts :settings) (edge/slot-states {})))))

(deftest a-sheet-slot-is-lit-while-its-sheet-is-on-top
  (is (= :on (:system (edge/slot-states {:view :system}))))
  (is (= [:shown :shown :shown] ((juxt :search :prompts :settings) (edge/slot-states {:view :system}))))
  (is (= :on (:search (edge/slot-states {:view :search}))))
  (is (= [:shown :shown :shown :shown]
         ((juxt :search :system :prompts :settings) (edge/slot-states {:view :picture})))
      "the Drawing board lights none"))

(deftest archive-acts-only-on-an-open-chat-of-ones-own
  (is (= :passive (:archive (edge/slot-states idle))))
  (is (= :shown (:archive (edge/slot-states (assoc idle :archivable? true))))))
