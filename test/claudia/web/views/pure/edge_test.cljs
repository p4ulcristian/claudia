(ns claudia.web.views.pure.edge-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.edge :as edge]))

(def ^:private idle {:running? false :new? true})

(deftest four-slots-over-the-middle-and-five-under-it-settings-at-the-bottom
  (is (= [:search :system :files :prompts] edge/over))
  (is (= [:stop :new :fork :archive :settings] edge/under))
  (is (= 9 (count (concat edge/over edge/under))) "nine slots, as before the picture steps left"))

(deftest every-slot-has-a-state-always
  (is (= (set (concat edge/over edge/under)) (set (keys (edge/slot-states {})))))
  (is (every? #{:shown :passive :red :on} (vals (edge/slot-states {})))))

(deftest stop-is-passive-while-nothing-runs-and-red-while-a-turn-runs
  (is (= :passive (:stop (edge/slot-states idle))))
  (is (= :red (:stop (edge/slot-states (assoc idle :running? true))))))

(deftest new-chat-is-passive-where-a-new-chat-cannot-be-written
  (is (= :passive (:new (edge/slot-states (assoc idle :new? false))))))

(deftest search-system-files-profiles-and-settings-act
  (is (= [:shown :shown :shown :shown :shown]
         ((juxt :search :system :files :prompts :settings) (edge/slot-states {})))))

(deftest files-is-passive-in-a-read-only-view
  (is (= :passive (:files (edge/slot-states {:read-only? true})))))

(deftest a-sheet-slot-is-lit-while-its-sheet-is-on-top
  (is (= :on (:system (edge/slot-states {:view :system}))))
  (is (= :on (:files (edge/slot-states {:view :files}))))
  (is (= [:shown :shown :shown] ((juxt :search :prompts :settings) (edge/slot-states {:view :system}))))
  (is (= :on (:search (edge/slot-states {:view :search}))))
  (is (= [:shown :shown :shown :shown :shown]
         ((juxt :search :system :files :prompts :settings) (edge/slot-states {:view :picture})))
      "the Drawing board lights none"))

(deftest archive-and-fork-act-only-on-an-open-chat-of-ones-own
  (is (= [:passive :passive] ((juxt :fork :archive) (edge/slot-states idle))))
  (is (= [:shown :shown] ((juxt :fork :archive) (edge/slot-states (assoc idle :archivable? true))))))

(deftest fork-is-lit-while-the-server-makes-the-fork
  (is (= :on (:fork (edge/slot-states (assoc idle :archivable? true :forking? true))))))
