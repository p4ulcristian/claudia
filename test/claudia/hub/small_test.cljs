(ns claudia.hub.small-test
  "The hub's smaller namespaces: GitHub's deliveries, narration's keys, presence."
  (:require [cljs.test :refer [deftest is]]
            [claudia.hub.github :as github]
            [claudia.hub.narrate :as narrate]
            [claudia.hub.presence :as presence]
            [claudia.shared.presence :as p]))

(def sha (apply str (repeat 40 "a")))

(deftest github-deliveries-that-act
  (is (github/main-push? "push" {:ref "refs/heads/main"}))
  (is (not (github/main-push? "push" {:ref "refs/heads/other"})))
  (is (not (github/main-push? "workflow_run" {:ref "refs/heads/main"}))))

(deftest github-signature
  (let [body (js/Buffer.from "{}")]
    (is (false? (github/signed? "k" body nil)))
    (is (false? (github/signed? nil body "sha256=00")))
    (is (false? (github/signed? "k" body "sha256=00")))
    (is (true? (github/signed? "k" body "sha256=add853b103fbcc936a194f9eb15e29c4ff08af6e47d5d1bca4f20218e31e4fff")))))

(deftest narration-keys
  (is (= "" (narrate/voice-of nil)))
  (is (= "calm" (narrate/voice-of "  calm ")))
  (is (= 200 (count (narrate/voice-of (apply str (repeat 300 "v"))))))
  (is (not= (narrate/clip-key "" "http://a" "hi") (narrate/clip-key "calm" "http://a" "hi")))
  (is (= (narrate/clip-key "calm" "http://a" "hi") (narrate/clip-key "calm" "http://b" "hi")))
  (is (not= (narrate/clip-key "" "http://a" "hi") (narrate/clip-key "" "http://b" "hi"))))

(deftest said-text-trims-and-cuts
  (is (nil? (narrate/said-text {})))
  (is (nil? (narrate/said-text {:text 5})))
  (is (= "hi" (narrate/said-text {:text " hi "})))
  (is (= 1200 (count (narrate/said-text {:text (apply str (repeat 2000 "x"))})))))

(deftest beaten-keeps-fresh-active-pages
  (is (= {"a" 100} (presence/beaten nil "a" true 100)))
  (is (= {} (presence/beaten {"a" 90} "a" false 100)))
  (is (= {"b" 100} (presence/beaten {"a" (- 100 p/stale-ms)} "b" true 100))))

(deftest pushes-and-stage-runs-name-what-to-fetch
  (let [c (apply str (repeat 40 "c")) t (apply str (repeat 40 "d"))]
    (is (= [c t] (github/push-target {:after c :head_commit {:tree_id t}})))
    (is (nil? (github/push-target {:after c})))
    (let [d {:action "completed" :workflow_run {:conclusion "success" :path ".github/workflows/stage.yml"
                                                :display_title (str "stage " t)}}]
      (is (= t (github/staged-tree "workflow_run" d)))
      (is (nil? (github/staged-tree "workflow_run" (assoc-in d [:workflow_run :conclusion] "failure"))))
      (is (nil? (github/staged-tree "workflow_run" (assoc-in d [:workflow_run :display_title] "stage "))))
      (is (nil? (github/staged-tree "workflow_run" (assoc-in d [:workflow_run :path] ".github/workflows/check.yml")))))))
