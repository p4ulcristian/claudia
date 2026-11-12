(ns claudia.slave.servers-test
  "The runner's routing, the forms' preparation and the reads' parsers, on plain data."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.slave.api :as api]
            [claudia.slave.changes :as changes]
            [claudia.slave.clojure :as clojure]
            [claudia.slave.codeforms :as codeforms]
            [claudia.slave.explain :as explain]
            [claudia.slave.git :as repo]
            [claudia.slave.runner :as runner]
            [claudia.slave.scene :as scene]))

(deftest runner-gates-frames
  (is (= :unknown (runner/frame-gate "nope" false true)))
  (is (= :read-only (runner/frame-gate "send" true true)))
  (is (= :hello (runner/frame-gate "hello" true false)))
  (is (= :new (runner/frame-gate "new" false false)))
  (is (= :no-chat (runner/frame-gate "open" false false)))
  (is (= :chat (runner/frame-gate "open" true true))))

(deftest runner-pages-messages
  (let [ms (mapv #(hash-map :id %) (range 130))]
    (is (= {:messages (subvec ms 10) :more true} (runner/newest-page ms)))
    (is (= {:messages (subvec ms 0 5) :more false} (runner/older-page ms 5)))
    (is (= {:messages [] :more false} (runner/older-page ms :gone)))))

(deftest runner-routes-requests
  (is (= ["a b" "picture.png"] (runner/chat-call "/api/chats/a%20b/picture.png")))
  (is (= [nil nil] (runner/chat-call "/api/me")))
  (is (= :no-chat (runner/request-op "GET" "/api/chats/x/state" "x" "state" false)))
  (is (= :picture-png (runner/request-op "GET" "/api/chats/x/picture.png" "x" "picture.png" true)))
  (is (= :timeline (runner/request-op "GET" "/api/chats/x/timeline/a/b" "x" "timeline/a/b" true)))
  (is (= :me (runner/request-op "GET" "/api/me" nil nil false)))
  (is (= :secret-read (runner/request-op "POST" "/api/secret/read" nil nil false)))
  (is (nil? (runner/request-op "POST" "/api/chats/x/send" "x" "send" true))))

(deftest api-routes-calls
  (testing "no call starts, messages, answers or reads a chat from outside the page"
    (is (nil? (api/route "POST" ["api" "delegate"] false)))
    (is (nil? (api/route "POST" ["api" "chats" "c" "send"] false)))
    (is (nil? (api/route "POST" ["api" "chats" "c" "answer"] false)))
    (is (nil? (api/route "GET" ["api" "chats" "c" "state"] false))))
  (is (= :read-only (api/route "GET" ["api" "github"] true)))
  (is (= :project-logo (api/route "GET" ["api" "project" "logo"] false)))
  (is (= :adopt (api/route "POST" ["api" "sessions" "x" "y"] false)))
  (is (= :patch-chat (api/route "PATCH" ["api" "chats" "c"] false)))
  (is (= :sent (api/route "GET" ["api" "chats" "c" "sent" "u" "0"] true)))
  (is (nil? (api/route "PATCH" ["api" "chats" "c" "x"] false)))
  (is (nil? (api/route "GET" ["api" "nothing"] false))))

(deftest api-chat-state-and-updates
  (is (= {:color "ash" :prev_color "red"} (api/chat-update {:color "ash"} {:color "red"} ["red"] (fn [] "new"))))
  (is (= {:color "red" :prev_color nil} (api/chat-update {:color "wake"} {:prev-color "red"} [] (fn [] "new"))))
  (is (= {:color "new" :prev_color nil :archived true} (api/chat-update {:color "wake" :archived true} {} [] (fn [] "new"))))
  (is (= {:color "blue"} (api/chat-update {:color "blue"} {} ["blue"] (fn [] "new")))))

(deftest api-finds-images-in-lines
  (let [img #js {:type "image" :source #js {:media_type "image/png" :data "x"}}
        line (js/JSON.stringify #js {:type "user" :uuid "u1" :message #js {:content #js [img]}})]
    (is (= "image/png" (.. ^js (api/image-of-line line {:uuid "u1" :n 0}) -source -media_type)))
    (is (nil? (api/image-of-line line {:uuid "u2" :n 0})))))

(deftest changes-rows
  (is (changes/same-state? {:text "a"} {:text "a"}))
  (is (not (changes/same-state? {:text "a"} {:text "b"})))
  (is (= {:turn "" :path "/f" :before nil :after "x\n" :status "added" :skipped nil :at 5 :added 1 :removed 0}
         (changes/change-row nil "/f" {:text nil} {:text "x\n"} 5)))
  (is (= "deleted" (:status (changes/change-row "t" "/f" {:text "x"} {:text nil} 5))))
  (is (= "modified" (:status (changes/change-row "t" "/f" {:text nil :skipped "binary file"} {:text nil :skipped "binary file"} 5)))))

(deftest explain-reads-a-step
  (let [text (str (js/JSON.stringify #js {:type "user" :message #js {:content "do it"}}) "\n"
                  (js/JSON.stringify #js {:type "assistant" :timestamp "2026-01-01T00:00:00Z"
                                          :message #js {:content #js [#js {:type "text" :text "ok"}
                                                                      #js {:type "tool_use" :id "s1" :name "Read" :input #js {}}]}})
                  "\nnot json\n"
                  (js/JSON.stringify #js {:type "user" :message #js {:content #js [#js {:type "tool_result" :tool_use_id "s1" :content "fine"}]}}))
        s (explain/step-in text "s1")]
    (is (= ["do it" "ok" "Read" true false] ((juxt :asked :before :name :finished :error) s)))
    (is (nil? (explain/step-in text "s2")))
    (is (re-find #"It is still running, for 3 seconds" (explain/prompt-of {:name "Bash" :at 1000} 4000)))
    (is (re-find #"Its output \(it failed\):\nboom" (explain/prompt-of {:name "Bash" :finished true :error true :result "boom"} 0)))
    (is (= {:name "Bash" :title "t" :input "d" :at 1 :result "r" :error false :finished true}
           (explain/live-step {:tool "Bash" :title "t" :detail "d" :at 1 :result {:text "r"}})))
    (is (= "ab" (explain/message-text #js {:type "assistant" :message #js {:content #js [#js {:type "text" :text "a"} #js {:type "image"} #js {:type "text" :text "b"}]}})))
    (is (= "" (explain/message-text #js {:type "user"})))
    (is (= {:k "t"} (explain/remember (into {} (map (fn [i] [i i])) (range 501)) :k "t")))))

(deftest codeforms-routes
  (is (= {:op :forms} (codeforms/form-route "GET" "/api/forms")))
  (is (= {:op :delete-template :template "a%20b"} (codeforms/form-route "DELETE" "/api/templates/a%20b")))
  (is (= {:op :chat :chat "c" :sub "blocks/b/template" :block "b"} (codeforms/form-route "POST" "/api/chats/c/blocks/b/template")))
  (is (nil? (codeforms/form-route "GET" "/api/chats/c/nope")))
  (is (= :files (codeforms/chat-read "GET" "files" nil)))
  (is (= :repo (codeforms/chat-read "GET" "git" nil)))
  (is (= :clojure (codeforms/chat-read "GET" "calls" nil)))
  (is (= :source (codeforms/chat-read "GET" "source" nil)))
  (is (= {:op :chat :chat "c" :sub "source" :block nil} (codeforms/form-route "GET" "/api/chats/c/source")))
  (is (= :save-block (codeforms/chat-read "POST" "blocks/b/template" "b")))
  (is (= :not-found (codeforms/chat-read "POST" "files" nil)))
  (is (= 2 (codeforms/int-or "x" 2)))
  (is (= 7 (codeforms/int-or "7" 2))))

(deftest codeforms-templates-and-text
  (is (nil? (codeforms/with-template {:id "b"} nil)))
  (is (= {:id "b" :template "T" :html "<p>" :parts {:a "1" :b "2"} :name "T"}
         (codeforms/with-template {:id "b" :parts {:b "2"}} {:name "T" :html "<p>" :parts {:a "1" :b "1"}})))
  (is (= "Picture updated: The picture is empty.. Not drawn: git: the user deleted that form; use another kind"
         (codeforms/draw-text {:blocks []} {:refused ["git" "git"]}))))

(deftest clojure-index-from-analysis
  (let [a (clj->js {"namespace-definitions" [{:name "a.core" :filename "src/a/core.clj" :doc "A."}
                                             {:name "a.util" :filename "src/a/util.clj"}]
                    "namespace-usages" [{:from "a.core" :to "a.util"} {:from "a.core" :to "clojure.string"}]
                    "var-usages" [{:from "a.core" :to "a.util" :name "help" :from-var "run" :row 3 :col 5}
                                  {:from "a.core" :to "a.util" :name "help" :from-var "run" :row 3 :col 5}]
                    "var-definitions" [{:ns "a.core" :name "run" :filename "src/a/core.clj" :row 2 :end-row 4 :defined-by "clojure.core/defn"}
                                       {:ns "a.util" :name "help" :filename "src/a/util.clj" :row 1 :defined-by "clojure.core/defn-"}]})
        i (clojure/analysis-index a "/p" "fp")]
    (is (= [{:name "a.core" :file "src/a/core.clj" :vars 1 :requires ["a.util"]}
            {:name "a.util" :file "src/a/util.clj" :vars 1 :requires []}]
           (:namespaces i)))
    (is (= {"a.core" "A."} (:docs i)))
    (is (= {"a.core" {"a.util" {"help" 2}}} (:uses i)))
    (is (= [{:from "a.core/run" :to "a.util/help" :row 3 :col 5 :endCol 9}] (:calls i)))
    (is (= {"a.core" ["clojure.string"]} (:external i)))
    (is (= [true "fn"] ((juxt :private :kind) (first (get-in i [:vars "a.util"])))))))

(deftest clojure-walks-steps
  (let [graph {"a" ["b" "c"] "b" ["d"] "c" [] "d" []}]
    (is (= {:dist {"a" 0 "b" 1 "c" 1 "d" 2} :cut {}}
           (clojure/walk-steps {:dist {"a" 0} :cut {}} "a" 8 1 #(get graph % []))))
    (is (= {:dist {"a" 0 "b" -1 "c" -1} :cut {}}
           (clojure/walk-steps {:dist {"a" 0} :cut {}} "a" 1 -1 #(get graph % []))))))

(deftest history-log-parses
  (is (= ["main"] (repo/log-revs "main,gone" [{:name "main"}])))
  (is (= ["--branches" "--tags" "--remotes" "HEAD"] (repo/log-revs "gone" [{:name "main"}])))
  (is (= [{:hash "h" :parents ["p1" "p2"] :author "Ann" :when "1 day ago" :time 5 :refs [{:type "tag" :name "v1"}] :subject "s"}]
         (repo/parse-log "h\u001fp1 p2\u001fAnn\u001f1 day ago\u001ftag: v1\u001fs\u001f5\u001e" {}))))

(deftest scene-blocks-and-graph
  (is (= "form" (scene/block-kind nil "steps" {})))
  (is (= "html" (scene/block-kind nil nil {:template "t"})))
  (is (= "graph" (scene/block-kind nil nil {})))
  (is (= "html" (scene/block-kind {:kind "html"} nil {:kind "graph"})))
  (let [{s :scene changed :changed} (scene/apply-draw scene/empty-scene
                                                      {:nodes [{:id "a"} {:id "b"}] :edges [{:from "a" :to "b"}]}
                                                      ["steps"])]
    (is (= [{:id "graph" :kind "graph" :nodes [{:label "a" :id "a"} {:label "b" :id "b"}]
             :edges [{:from "a" :to "b"}] :groups []}]
           (:blocks s)))
    (is (= #{"a->b" "a" "b"} (set changed)))))
