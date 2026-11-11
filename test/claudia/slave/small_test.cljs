(ns claudia.slave.small-test
  "The slave programs' small pure helpers, on plain data."
  (:require [cljs.test :refer [deftest is]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [claudia.slave.agentgit :as agentgit]
            [claudia.slave.editor :as editor]
            [claudia.slave.files :as files]
            [claudia.slave.front :as front]
            [claudia.slave.lines :as lines]
            [claudia.slave.projects :as projects]
            [claudia.slave.render :as render]
            [claudia.slave.secret :as secret]
            [claudia.slave.tokens :as tokens]
            [claudia.slave.uploads :as uploads]))

(deftest split-lines-keeps-the-unfinished-tail
  (is (= {:lines ["a" "b"] :tail "c"} (lines/split-lines "a\n\nb\nc")))
  (is (= {:lines [] :tail "abc"} (lines/split-lines "abc")))
  (is (= {:lines ["x"] :tail ""} (lines/split-lines "x\n"))))

(deftest guard-file-refuses-code-over-an-unsent-edit
  (let [before {:id "a" :code "1" :v 1 :edit "2" :rev 3}]
    (is (= [{:id "a" :code "2" :v 2 :edit nil} nil] (editor/guard-file {:id "a" :code "2"} before)))
    (is (= [{:id "a"} true] (update (editor/guard-file {:id "a" :code "9"} before) 1 string?)))
    (is (= [{:id "a" :code "9" :v 2 :edit nil} nil] (editor/guard-file {:id "a" :code "9" :seen 3} before)))
    (is (= [{:id "b" :code "x" :v 1 :edit nil} nil] (editor/guard-file {:id "b" :code "x"} nil)))
    (is (= [{:id "c" :name "n"} nil] (editor/guard-file {:id "c" :name "n" :v 7} nil)))))

(deftest code-part-counts-the-diff
  (is (= "<claudia-code block=\"b\" file=\"f\" name=\"f\" added=\"1\" removed=\"0\">\n<diff>\n+x\n</diff>\n<code>\nx\n</code>\n</claudia-code>"
         (editor/code-part "b" {:id "f" :edit "x"} "+x"))))

(deftest run-result-hides-the-secret
  (is (= {:text "a [secret] b\n[secret]" :error false} (secret/run-result "pw" false nil "a pw b" "pw")))
  (is (= {:text "(no output)\n(exit 2)" :error true} (secret/run-result "pw" true 2 "" "")))
  (is (= "op read failed: bad ref" (secret/op-error "bad ref\nmore" "x")))
  (is (= "op read failed: timed out" (secret/op-error "  " "timed out"))))

(deftest logos-score-by-name-and-depth
  (is (= 100 (projects/logo-score "favicon.svg" 0)))
  (is (= 58 (projects/logo-score "logo.png" 2)))
  (is (nil? (projects/logo-score "readme.md" 0)))
  (is (= "b" (projects/best-logo [{:path "a" :score 1} {:path "b" :score 5} {:path "c" :score 5}])))
  (is (nil? (projects/best-logo [])))
  (is (nil? (projects/root-name nil "/w")) "no folder, no project")
  (is (nil? (projects/root-name "rel/x" "/w")) "only absolute folders"))

(deftest agent-branches-parse
  (let [h (apply str (repeat 40 "a"))]
    (is (= h (agentgit/a-hash (str " " h "\n"))))
    (is (nil? (agentgit/a-hash "abc"))))
  (is (= "/r" (agentgit/repo-of "/r/.git" "/x")))
  (is (= "/r" (agentgit/repo-of "" "/r/.claude/worktrees/w")))
  (is (nil? (agentgit/repo-of "" "/x/y")))
  (is (= [{:hash "h1" :subject "one" :when "2 days ago"} {:hash "h2" :subject "" :when ""}]
         (agentgit/parse-commits "h1\u001fone\u001f2 days ago\u001e\nh2\u001e"))))

(deftest commit-identity-of-a-github-user
  (is (= ["Ann" "7+ann@users.noreply.github.com"] (tokens/commit-identity {:id 7 :login "ann" :name "Ann"})))
  (is (= ["ann" "7+ann@users.noreply.github.com"] (tokens/commit-identity {:id 7 :login "ann" :name ""}))))

(deftest uploads-refuse-and-type
  (let [chat "0b8d2c1e-1111-4222-8333-444455556666"]
    (is (= [400 "bad request"] (uploads/refusal "nope" "f" 1)))
    (is (= [400 "bad request"] (uploads/refusal chat "" 1)))
    (is (= [413 "file too big"] (uploads/refusal chat "f" (inc uploads/max-bytes))))
    (is (nil? (uploads/refusal chat "f" 10))))
  (is (= "text/plain" (uploads/file-type "text/plain")))
  (is (= "application/octet-stream" (uploads/file-type "")))
  (is (= "application/octet-stream" (uploads/file-type 3))))

(deftest file-lines-and-status
  (is (= 2 (files/line-count (js/Buffer.from "a\nb"))))
  (is (= 2 (files/line-count (js/Buffer.from "a\nb\n"))))
  (is (= 0 (files/line-count (js/Buffer.from ""))))
  (is (nil? (files/line-count (js/Buffer.from #js [1 0 2]))))
  (is (= ["U" "D" "A" "M"] (map files/status-letter ["??" " D" "A " "RM"])))
  (is (= {"a.txt" "M" "new.txt" "M" "u" "U"}
         (files/parse-status [" M a.txt" "R  new.txt" "old.txt" "?? u" "x"] "/r" "/r")))
  (is (= {} (files/parse-status [" M ../out"] "/r/sub" "/r/sub"))))

(deftest repo-entries-fold-folders
  (let [r {:files ["a.txt" "d/b.txt" "d/c.txt" "e/f"] :status {"a.txt" "M" "d/b.txt" "A"}}]
    (is (= {"a.txt" {:name "a.txt" :dir false :status "M"}
            "d" {:name "d" :dir true :changed 1}
            "e" {:name "e" :dir true}}
           (files/repo-entries r "")))
    (is (= {"b.txt" {:name "b.txt" :dir false :status "A"} "c.txt" {:name "c.txt" :dir false}}
           (files/repo-entries r "d/"))))
  (is (= ["z" "a" "b"] (map :name (files/folders-first [{:name "b"} {:name "z" :dir true} {:name "a"}])))))

(deftest front-routes-by-email
  (let [ps (front/parse-profiles "# who\nann ANN@x.org /s/a\nbob bob@x.org /s/b # note\nbroken line\n")]
    (is (= [{:profile "ann" :email "ann@x.org" :socket "/s/a"} {:profile "bob" :email "bob@x.org" :socket "/s/b"}] ps))
    (is (= {:status 401 :error "No caller."} (front/route-of ps nil nil)))
    (is (= 403 (:status (front/route-of ps "eve@x.org" nil))))
    (is (= 404 (:status (front/route-of ps "ann@x.org" "zed"))))
    (is (= {:own (first ps) :target (second ps) :read-only true} (front/route-of ps "ann@x.org" "bob")))
    (is (= {:own (first ps) :target (first ps) :read-only false} (front/route-of ps "ann@x.org" "ann")))))

(deftest front-headers
  (is (= [["Host" "h"] ["X-Claudia-Profile" "bob"] ["X-Claudia-Read-Only" "1"]]
         (front/runner-headers #js ["Host" "h" "X-Claudia-Email" "e"] {:target {:profile "bob"} :read-only true})))
  (is (= "GET /ws HTTP/1.1\r\nA: 1\r\n\r\n" (front/request-head "GET" "/ws" [["A" "1"]]))))

(deftest render-serves-only-assets
  (is (= "/w/js/main.js" (render/asset-path "/w" "/js/main.js")))
  (is (nil? (render/asset-path "/w" "/secret.txt")))
  (is (= "/w/frame.html" (render/asset-path "/w" "/frame.html")))
  (is (nil? (render/asset-path "/w" "/index.html")))
  (is (= "/w/js/shiki-langs/clojure.mjs" (render/asset-path "/w" "/js/shiki-langs/clojure.mjs")))
  (is (= "/w/etc/passwd" (render/asset-path "/w" "/js/../../etc/passwd")))
  (is (= 120 (render/shot-height 10)))
  (is (= 3200 (render/shot-height 99999)))
  (is (= 700 (render/shot-height 700))))

(deftest the-hubs-token-is-checked-before-it-is-written
  (let [t (str "sk-ant-oat01-" (apply str (repeat 30 "a")))]
    (is (= 501 (:status (tokens/current-problem {:name "Paul" :at 1} t nil))))
    (is (= "Not a Claude token." (:error (tokens/current-problem {:name "Paul" :at 1} "x" "/f"))))
    (is (= "name and at are needed." (:error (tokens/current-problem {:name "" :at 1} t "/f"))))
    (is (nil? (tokens/current-problem {:name "Paul" :at 1} t "/f")))))

(deftest the-front-keeps-the-newest-limits-per-token
  (is (= {"a" {:limits [2] :at 9} "b" {:limits [3] :at nil}}
         (front/newest-heard [{"a" {:limits [1] :at 4} "b" {:limits [3] :at nil}}
                              {"a" {:limits [2] :at 9}}
                              {"a" {:limits [0] :at 1}}]))))

(deftest the-poke-says-check-or-stage
  (let [t (apply str (repeat 40 "a"))]
    (is (= (str "stage " t "\n") (front/trigger-line {:stage t} "now")))
    (is (= (str "main " t "\n") (front/trigger-line {:main t} "now")))
    (is (= "check now\n" (front/trigger-line {:stage "nope"} "now")))
    (is (= "check now\n" (front/trigger-line {} "now")))))

(deftest screenshot-reads-only-its-scenes-roots
  (is (= #{"/a"} (render/scene-roots {:blocks [{:kind "form" :form "scene3d" :data {:root "/a"}}
                                               {:kind "form" :form "files" :data {:root "/b"}}
                                               {:kind "form" :form "scene3d" :data {}}]})))
  (is (= #{} (render/scene-roots {}))))

(deftest the-hubs-saved-tokens-are-checked-before-they-are-written
  (let [t (str "sk-ant-oat01-" (apply str (repeat 30 "a")))]
    (is (= [{:key "paul" :name "Paul" :at 1 :token t}]
           (tokens/clean-saved [{:key "paul" :name "Paul" :at 1 :token (str " " t "\n") :more 1}])))
    (is (= [] (tokens/clean-saved [])))
    (is (nil? (tokens/clean-saved nil)))
    (is (nil? (tokens/clean-saved [{:key "../x" :name "Paul" :at 1 :token t}])) "a key is a file name")
    (is (nil? (tokens/clean-saved [{:key "paul" :name "Paul" :at 1 :token "x"}])))
    (is (nil? (tokens/clean-saved [{:key "paul" :name "" :at 1 :token t}])))))

(deftest saved-tokens-are-kept-one-file-each-and-the-gone-ones-deleted
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "claudia-tokens-"))
        before (.-CLAUDIA_TOKEN_FILE (.-env js/process))
        t (fn [c] (str "sk-ant-oat01-" (apply str (repeat 30 c))))
        row (fn [k n c] {:key k :name n :at 1 :token (t c)})]
    (set! (.-CLAUDIA_TOKEN_FILE (.-env js/process)) (path/join dir "claude-token"))
    (try
      (is (= [] (tokens/saved)))
      (is (= {:tokens [{:key "paul" :name "Paul" :at 1} {:key "peter" :name "Peter" :at 1}]}
             (tokens/write-saved! {:tokens [(row "paul" "Paul" "a") (row "peter" "Peter" "b")]})))
      (is (= (t "b") (tokens/saved-token "peter")))
      (is (nil? (tokens/saved-token "ananda")))
      (is (nil? (tokens/saved-token nil)))
      (is (= {:tokens [{:key "peter" :name "Peter" :at 1}]} (tokens/write-saved! {:tokens [(row "peter" "Peter" "b")]})))
      (is (nil? (tokens/saved-token "paul")))
      (is (= ["peter"] (vec (fs/readdirSync (path/join dir "claude-token.d")))))
      (is (:error (tokens/write-saved! {:tokens [{:key "x"}]})))
      (finally
        (if before (set! (.-CLAUDIA_TOKEN_FILE (.-env js/process)) before) (js-delete (.-env js/process) "CLAUDIA_TOKEN_FILE"))
        (fs/rmSync dir #js {:recursive true :force true})))))
