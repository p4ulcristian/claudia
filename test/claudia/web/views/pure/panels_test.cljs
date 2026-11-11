(ns claudia.web.views.pure.panels-test
  "The asks, agents, usage, settings, list, menu, changes, picture, attach and layout helpers."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.views.pure.agents :as agents]
            [claudia.web.views.pure.asks :as asks]
            [claudia.web.views.pure.attach :as attach]
            [claudia.web.views.pure.changes :as changes]
            [claudia.web.views.pure.menu :as menu]
            [claudia.web.views.pure.picture :as picture]
            [claudia.web.views.pure.settings :as settings]
            [claudia.web.views.pure.shell :as shell]
            [claudia.web.views.pure.sidebar :as sidebar]
            [claudia.web.views.pure.usage :as usage]))

(def qs [{:question "Q1" :options [{:label "a" :preview "pa"} {:label "b"}]}
         {:question "Q2" :multiSelect true :options [{:label "x"} {:label "y"}]}])

(deftest asks-answers
  (let [st (-> {} (asks/toggle qs 0 "a") (asks/toggle qs 1 "x") (asks/toggle qs 1 "y"))]
    (is (= {0 {:picked ["a"]} 1 {:picked ["x" "y"]}} st))
    (is (asks/ready? st qs))
    (is (= {"Q1" "a" "Q2" "x, y"} (asks/answers st qs)))
    (testing "one pick: picking it again unpicks"
      (is (= [] (get-in (asks/toggle st qs 0 "a") [0 :picked]))))
    (testing "the other text unpicks a pick-one question"
      (is (= {:picked [] :other "z"} (get (asks/typed-other st 0 false "z") 0))))
    (is (not (asks/ready? {0 {:picked ["a"]}} qs)))
    (is (= 1 (asks/first-open {0 {:picked ["a"]}} qs)))
    (is (= 0 (asks/first-open st qs)))
    (is (= "pa" (asks/preview (first qs) {:picked ["a"]})))
    (is (= "a, more" (asks/answer-of {:picked ["a"] :other " more "})))
    (is (= "1–2 to pick · Enter to answer · Esc to see the picture" (asks/key-hint qs)))))

(deftest agents-chips-and-facts
  (let [as [{:id 1 :status "running" :kind "agent" :active 1 :start 10}
            {:id 2 :status "running" :kind "agent" :active 5 :start 20}
            {:id 3 :status "running" :kind "shell"}
            {:id 4 :status "failed"} {:id 5 :status "completed"}]
        row (agents/chip-row as 256 false)]
    (is (= [1 2] (map :id (:shown row))))
    (is (= 0 (:more row)))
    (is (= [1 1] [(:failed row) (:done row)]))
    (is (= 1 (:columns row)))
    (testing "a phone shows two"
      (is (= 2 (count (:shown (agents/chip-row (conj as {:id 6 :status "running" :kind "agent"}) 2000 true)))))))
  (is (= "1m 5s" (agents/dur 65000)))
  (is (= "155k" (agents/tokens 155000)))
  (is (= "1.2M" (agents/tokens 1200000)))
  (is (= ["5s" "1 tool" "2k"] (agents/facts-words {:kind "agent" :tools 1 :tokens 2000 :start 0 :end 5000} 0)))
  (is (= [["Took" "5s"] ["Tools" "3"] ["Kind" "Explore · background"] ["Worktree" "fix · 1 commit"]]
         (agents/facts {:start 0 :end 5000 :tools 3 :type "Explore" :background true}
                       {:worktree {:branch "fix"} :git {:commits [{}]}} 0)))
  (is (= [["steps" "Output"] ["prompt" "Command"]] (agents/tabs true nil)))
  (is (= "Files 2" (second (second (agents/tabs false [1 2])))))
  (is (= ["green" "done"] (agents/status-chip "completed")))
  (is (= {:a 1 :b 3} (agents/merged-info {:b 3 :c nil} {:info {:a 1 :b 2}})))
  (is (nil? (agents/merged-info nil {}))))

(deftest usage-words
  (is (= "Opus 5.5 1M" (usage/pretty-model "claude-opus-5-5[1m]")))
  (is (= "Sonnet 4" (usage/pretty-model "claude-sonnet-4-20250514")))
  (is (= "1.2M" (usage/k-tokens 1200000)))
  (is (= "?" (usage/k-tokens nil)))
  (is (= "red" (usage/level 95)))
  (is (= "2d 4h" (usage/countdown (* 1000 (+ (* 2 86400) (* 4 3600))))))
  (is (= "14m 05s" (usage/countdown (* 1000 (+ (* 14 60) 5)))))
  (is (= "now" (usage/countdown 0)))
  (is (= ["five_hour" "seven_day"] (map :key (usage/two [{:key "seven_day"} {:key "x"} {:key "five_hour"}]))))
  (is (= "5h 10% · in 1m 00s" (usage/limits-tip [{:label "5h" :percent 10 :resetsAt 60000}] 0)))
  (is (= ["clear" "xclear"] (map :name (usage/matches "/cl" {:state {:commands ["xclear" "clear" "x"]}} {})))
      "a command not starting with the text comes after")
  (is (= "compact" (:name (first (usage/matches "/com" {} {:commands [{:name "compact"}]})))))
  (is (nil? (usage/matches "hello" {} {})))
  (is (= {:value "b"} (usage/chosen-model [{:value "a"} {:value "b"}] {:model "b"}))))

(deftest settings-title-menu
  (is (= "Paul's chats" (settings/list-title false "paul")))
  (is (= "Archived chats" (settings/list-title true "paul")))
  (let [servers [{:name "a" :label "A"} {:name "b" :label "B"}]
        {:keys [usable ticked]} (settings/servers-in-view servers "paul" {"a" {:profiles ["paul"]}} #{})]
    (is (= ["a"] (map :name usable)))
    (is (= ["a"] (map :name ticked))))
  (is (= "A" (settings/servers-hint [{:label "A"} {:label "B"}] [{:label "A"}])))
  (is (nil? (settings/servers-hint [{:label "A"}] [{:label "A"}])))
  (is (= [[nil "My chats"] ["ann" "Ann's chats"]] (settings/whose-items ["ann"]))))

(def ^:private projected
  [{:server "s" :id "1" :title "Logo" :at 3 :project {:name "iris" :root "/w/iris"}}
   {:server "s" :id "2" :title "Menu" :at 1 :project {:name "claudia" :root "/w/claudia"}}
   {:server "s" :id "3" :title "Chips" :at 2 :project {:name "claudia" :root "/w/claudia"}}
   {:server "s" :id "4" :title "Misc" :at 0}])

(deftest projects-of-the-list
  (is (= "/w/iris" (sidebar/project-key (first projected))))
  (is (= "" (sidebar/project-key (last projected))))
  (is (= "No project" (sidebar/project-label (last projected))))
  (is (= ["2" "3"] (map :id (sidebar/matching projected (sidebar/search-key "CLAU")))) "a project name matches")
  (is (= ["1"] (map :id (sidebar/matching projected "logo"))))
  (is (= ["2" "3"] (map :id (sidebar/in-project projected "/w/claudia"))))
  (is (= ["4"] (map :id (sidebar/in-project projected ""))))
  (is (= 4 (count (sidebar/in-project projected nil))))
  (is (= [{:key "/w/claudia" :label "claudia" :count 2} {:key "/w/iris" :label "iris" :count 1}
          {:key "" :label "No project" :count 1}]
         (sidebar/project-chips projected)))
  (is (= {"s" [{:id "3" :count 2} {:id "2" :count 0 :hits []}]}
         (sidebar/with-project-finds {"s" [{:id "3" :count 2}]} projected "claudia"))
      "chats of a matching project join the search's finds once"))

(deftest time-sections
  (let [now (.getTime (js/Date. 2026 10 9 15 0))
        at (fn [m d] (.getTime (js/Date. 2026 m d 12 0)))]
    (is (= 0 (sidebar/days-ago (.getTime (js/Date. 2026 10 9 0 5)) now)))
    (is (= "Today" (sidebar/time-section (at 10 9) now)))
    (is (= "Yesterday" (sidebar/time-section (at 10 8) now)))
    (is (= "Past 7 days" (sidebar/time-section (at 10 3) now)))
    (is (= "Past 2 weeks" (sidebar/time-section (at 10 1) now)))
    (is (= "October" (sidebar/time-section (at 9 20) now)))
    (is (= "December 2025" (sidebar/time-section (.getTime (js/Date. 2025 11 1)) now)))
    (is (= [["Today" [{:at (at 10 9)}]] ["October" [{:at (at 9 20)} {:at (at 9 2)}]]]
           (sidebar/in-sections [{:at (at 9 2)} {:at (at 10 9)} {:at (at 9 20)}] :at now))
        "newest first, one section per run")))

(deftest list-and-rail
  (let [rows [{:title "Fix login" :color "ash"} {:title "Build page"} {:title "fix CSS"}]]
    (is (= ["Fix login" "fix CSS"] (map :title (sidebar/matching rows (sidebar/search-key " FIX ")))))
    (is (= 3 (count (sidebar/matching rows ""))))
    (is (= ["Build page" "fix CSS" "Fix login"] (map :title (sidebar/passive-last rows)))))
  (let [rows [{:server "s" :id "a" :color "ash"} {:server "s" :id "b"} {:server "s" :id "c"} {:server "s" :id "d"}]
        elsewhere #{["s" "b"] ["s" "c"]}]
    (is (= ["c" "d" "a" "b"] (map :id (sidebar/passive-last rows elsewhere ["s" "c"])))
        "open in another tab steps back, unless it is open here too")
    (is (true? (sidebar/passive? {:server "s" :id "b"} elsewhere nil)))
    (is (false? (sidebar/passive? {:server "s" :id "b"} elsewhere ["s" "b"])))
    (is (true? (sidebar/passive? {:color "ash"} #{} nil))))
  (is (= "now" (sidebar/ago-text 0 30000)))
  (is (= "5m" (sidebar/ago-text 0 300000)))
  (is (nil? (sidebar/ago-text 0 (* 8 86400000))))
  (is (= ["waiting" "Asks you: which one?"] (sidebar/second-line {:waiting true :asks [{:kind "question" :text "which\n one?"}]})))
  (is (= ["working" "Claude working · 2 agents"] (sidebar/second-line {:running true :agents 2})))
  (is (= ["reply" "done"] (sidebar/second-line {:preview " done "})))
  (is (= "1 hit" (sidebar/hits-label 1)))
  (is (= "No match." (sidebar/empty-note "x" true))))

(deftest menus
  (let [rows [{:server "a" :project {:name "iris" :root "/w/iris"}} {:server "a" :project {:name "claudia" :root "/w/claudia"}}
              {:server "a" :project {:name "claudia" :root "/w/claudia"}} {:server "b" :project {:name "x" :root "/w/x"}} {:server "a"}]]
    (is (= ["/w/claudia" "/w/iris"] (map :key (menu/move-targets rows "a" ""))) "this server's projects, most chats first")
    (is (= ["/w/iris"] (map :key (menu/move-targets rows "a" "IR"))))
    (is (= "/w/iris" (menu/typed-folder (menu/move-targets rows "a" "ir") "ir")) "one project left: that one")
    (is (= "~/Work/new" (menu/typed-folder [] " ~/Work/new ")))
    (is (= "apps/new" (menu/typed-folder [{:key "/w/apps"}] "apps/new")) "a path stays a path")
    (is (nil? (menu/typed-folder [] "  "))))
  (is (= {:left 6 :top 50} (menu/menu-pos 0 50 {:left 0 :top 0 :width 400 :height 300} 100 100)))
  (is (= {:left 294 :top 194} (menu/menu-pos 390 290 {:left 0 :top 0 :width 400 :height 300} 100 100)))
  (is (= "AM" (menu/initials "Ananda Mishung")))
  (is (= "P" (menu/initials " Paul ")))
  (is (= "155k" (menu/k-tokens 155000))))

(deftest changes-of-the-turn
  (let [state {:messages [{:id "u1" :role "user"} {:id "u2" :role "user"}]
               :changes [{:turn "u1" :added 1 :removed 0} {:turn "u2" :added 2 :removed 1} {:turn "u2" :added 3 :removed 0}]}]
    (is (= {:turn "u2" :label "This turn" :added 5 :removed 1} (dissoc (changes/turn-changes state nil) :changes)))
    (is (= "Earlier turn" (:label (changes/turn-changes state "u1"))))
    (is (= 1 (count (:changes (changes/turn-changes state "u1"))))))
  (is (= "src/a.clj" (changes/short-path "/w/p/src/a.clj" "/w/p")))
  (is (= "~/x" (changes/short-path "/home/someone/x" "/w/p"))))

(deftest picture-keeps-blocks
  (let [prev {:blocks [{:id "a" :kind "text"} {:id "g" :kind "graph" :nodes [{:id "n1"}] :edges [{:from "n1" :to "n2"}]}]}
        scene {:blocks [{:id "a" :kind "text"} {:id "g" :kind "graph" :nodes [{:id "n1" :label "x"}] :edges [{:from "n1" :to "n2"}]}
                        {:id "new"}]}]
    (is (= #{"g" "n1" "new"} (picture/changed-ids prev scene)))
    (is (= {:k 1 :scene scene :changed #{} :seq 0} (picture/next-shown nil 1 scene)))
    (is (= 1 (:seq (picture/next-shown {:k 1 :scene prev :seq 0} 1 scene)))))
  (let [copies (picture/copies-of {:k 1} 1 [{:id "a"} {:id "f"}] ["f"])]
    (is (= {:k 1 "f" {:block {:id "f"} :at 1}} copies))
    (is (= {:k 2} (picture/copies-of copies 2 [] ["f"])))
    (testing "Claude took f off: it comes back at its place"
      (let [gone (picture/gone-ids [{:id "a"}] ["f"] copies true)]
        (is (= #{"f"} gone))
        (is (= [{:id "a"} {:id "f"}] (picture/with-gone [{:id "a"}] ["f"] gone copies)))))
    (is (= #{} (picture/gone-ids [{:id "a"}] ["f"] copies false))))
  (is (= {:k 1 :ids ["f" "g"]} (picture/kept-with {:k 1 :ids ["f"]} 1 "g")))
  (is (= {:k 2 :ids ["g"]} (picture/kept-with {:k 1 :ids ["f"]} 2 "g"))))

(deftest attachments
  (let [{:keys [media reading? uploads]} (attach/groups [{:id 1 :kind "media" :url "u"} {:id 2 :busy true}
                                                         {:id 3 :busy true :progress 0.5} {:id 4 :kind "media"}])]
    (is (= [1] (map :id media)))
    (is (true? reading?))
    (is (= [3] (map :id uploads))))
  (is (= "f: too big" (attach/upload-title {:name "f" :error "too big"})))
  (is (= "Sending f…" (attach/upload-title {:name "f"})))
  (is (= {:folder "abc" :saved "f.txt"} (attach/upload-path "/up/abc/f.txt" "f"))))

(deftest layout-numbers
  (is (= 300 (shell/dragged-width 0 1000 100)))
  (is (= 714 (shell/dragged-width 0 1000 900)))
  (is (= "minmax(300px, 1fr) 6px 16px" (shell/split-columns true 400)))
  (is (= 50 (shell/mark-at 3 1)))
  (is (= 100 (shell/mark-at 1 0)))
  (is (= 2 (shell/nearest-mark 3 0 100 90)))
  (is (= "packed" (shell/density 100 200)))
  (is (= "" (shell/density 2 200)))
  (is (= 8 (shell/tip-left 0 50 0 500))))

(deftest a-servers-token-state
  (is (= :off (settings/sync-state {:ok false} "Paul")))
  (is (= :on (settings/sync-state {:ok true :current "Paul"} "Paul")))
  (is (= :behind (settings/sync-state {:ok true :current "Ananda"} "Paul")))
  (is (= "Box not reached: down" (settings/sync-title {:label "Box" :error "down"} :off)))
  (is (= "Box still runs on a token set by hand; the hub writes it within a minute"
         (settings/sync-title {:label "Box"} :behind))))

(deftest freshness-says-how-old
  (is (= "not heard yet" (usage/freshness nil 0)))
  (is (= "at a time not kept" (usage/freshness 0 5)))
  (is (= "now" (usage/freshness 1000 30000)))
  (is (= "10 min ago" (usage/freshness 1 (+ 1 (* 10 60000)))))
  (is (= "3 h ago" (usage/freshness 1 (+ 1 (* 3 3600000)))))
  (is (= "1 day ago" (usage/freshness 1 (+ 1 (* 25 3600000)))))
  (is (= "2 days ago" (usage/freshness 1 (+ 1 (* 50 3600000))))))

(deftest a-window-past-its-reset-no-longer-holds
  (is (usage/reset-since? {:resetsAt 5} 5))
  (is (not (usage/reset-since? {:resetsAt 6} 5)))
  (is (not (usage/reset-since? {} 5))))

(deftest token-rows-take-the-newer-of-hub-and-page
  (let [week #(hash-map :key "seven_day" :percent %)
        tokens [{:name "Ana" :limits [(week 40)] :heardAt 10}
                {:name "Paul" :limits [(week 31) {:key "five_hour" :percent 2}] :heardAt 7}
                {:name "New" :limits [] :heardAt nil}]]
    (is (= [{:name "Ana" :in-use true :five nil :week (week 74) :heard-at 20}
            {:name "Paul" :in-use false :five {:key "five_hour" :percent 2} :week (week 31) :heard-at 7}
            {:name "New" :in-use false :five nil :week nil :heard-at nil}]
           (usage/token-rows tokens "Ana" {:limits [(week 74)] :at 20})))
    (is (= (week 40) (:week (first (usage/token-rows tokens "Ana" {:limits [(week 74)] :at 5})))))))

(deftest a-plans-limits-as-lines-for-the-picker
  (let [hour 3600000]
    (is (= "52 h" (usage/hours-left (* 52 hour))))
    (is (= "45 min" (usage/hours-left (* 45 60000))))
    (is (= "now" (usage/hours-left 0)))
    (is (= "Week 56% · resets in 52 h" (usage/limit-text "Week" {:percent 56 :resetsAt (+ 1000 (* 52 hour))} 1000)))
    (is (= "Week 0%" (usage/limit-text "Week" {:percent 56 :resetsAt 500} 1000)) "reset since it was heard")
    (is (= "Week 12%" (usage/limit-text "Week" {:percent 12} 1000)))
    (is (= "5-hour not heard yet" (usage/limit-text "5-hour" nil 1000)))
    (is (= [{:key "paul" :name "Paul" :week {:percent 5} :five nil} {:key "new" :name "New"}]
           (usage/plan-rows [{:key "paul" :name "Paul"} {:key "new" :name "New"}]
                            [{:name "Paul" :in-use true :week {:percent 5} :five nil :heard-at 1}])))))

(deftest a-limits-ring-shows-zero-once-its-window-has-reset
  (is (= 56 (usage/ring-percent {:percent 56 :resetsAt 2000} 1000)))
  (is (= 0 (usage/ring-percent {:percent 56 :resetsAt 500} 1000)))
  (is (nil? (usage/ring-percent nil 1000))))
