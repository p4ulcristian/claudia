(ns claudia.web.views.agents
  "The work Claude started beside the chat (README decision 25): chips under the chat's title for the
  running subagents and background shells (+N more, finished and failed folded into one chip), the
  list of every one of them, and one agent's view in the chat column (‹ Back, its facts, tabs Steps /
  Files / Prompt / Report; a shell: Output / Command). Stop on hover. The rail's third state (turn
  ended, agents still working) is agents-ring."
  (:require [clojure.string :as str]
            [claudia.web.hooks :as hooks]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.md :as md]
            [claudia.web.views.diff :as diff]
            [claudia.web.views.steps :as steps]))

;; ---- small words ----

(defn dur [ms]
  (let [s (max 0 (js/Math.floor (/ ms 1000)))]
    (cond (< s 60) (str s "s")
          (< s 3600) (str (quot s 60) "m " (mod s 60) "s")
          :else (str (quot s 3600) "h " (quot (mod s 3600) 60) "m"))))

(defn tokens
  "Tokens only (no cost, no share of a limit): 950, 155k, 1.2M."
  [n]
  (cond (nil? n) ""
        (< n 1000) (str n)
        (< n 1e6) (str (js/Math.round (/ n 1000)) "k")
        :else (str (.toFixed (/ n 1e6) 1) "M")))

(defn- running? [a] (= "running" (:status a)))
(defn- elapsed [a] (- (or (:end a) (js/Date.now)) (:start a)))

(defn- use-tick!
  "Re-renders every second while on."
  [on]
  (let [[_ set-n] (h/use-state 0)]
    (h/use-effect (fn []
                    (when on
                      (let [t (js/setInterval #(set-n inc) 1000)]
                        #(js/clearInterval t))))
                  [on])))

(defn- facts-line [a]
  [:span.ag-facts
   (str/join " · " (remove nil? [(dur (elapsed a))
                                 (when (= "agent" (:kind a)) (str (:tools a) (if (= 1 (:tools a)) " tool" " tools")))
                                 (when (:tokens a) (tokens (:tokens a)))]))])

(defn- status-mark [a]
  (if (running? a)
    [:span.ag-spin {:aria-label "running"}]
    [:span.ag-mark {:class (:status a)} (if (= "failed" (:status a)) "✕" "✓")]))

(defn- bar [{:keys [done total label]} status]
  (let [pct (js/Math.round (* 100 (/ done total)))]
    [:span.ag-bar {:class status :role "progressbar" :aria-valuemin 0 :aria-valuemax total :aria-valuenow done
                   :title (str done " of " total)}
     [:span.ag-bar-fill {:style {:width (str pct "%")}}]
     [:span.ag-bar-text (str done "/" total (when (seq label) (str " " label)) " · " pct "%")]]))

(defn- chip [ck a read-only?]
  [:div.ag-chip {:class (:status a) :role "button" :tab-index 0 :title (:name a)
                 :on-click #(rf/dispatch [:agents/open ck (:id a)])
                 :on-key-down #(when (#{"Enter" " "} (.-key %))
                                 (.preventDefault %)
                                 (rf/dispatch [:agents/open ck (:id a)]))}
   [:span.ag-l1 [status-mark a] [:b.ag-name (:name a)] [facts-line a]]
   (when (:progress a) [bar (:progress a) (:status a)])
   [:span.ag-l2 (str (or (:step a) (when (= "agent" (:kind a)) "starting…"))
                     (when (pos? (:children a)) (str " · +" (:children a))))]
   (when (and (not read-only?) (:task a) (running? a))
     [:button.ag-stop {:title "Stop this agent"
                       :on-click (fn [e] (.stopPropagation e) (rf/dispatch [:agents/stop ck (:task a)]))}
      "Stop"])])

;; ---- the chips under the title ----

(def ^:private chip-min 250)
(def ^:private gap 6)

(defn chips
  "The running ones (as many as fit: up to four on a desktop, two on a phone), +N more, ✓ N ✕ M."
  [{:keys [ck state]}]
  (let [box (h/use-ref nil)
        agents (:agents state)
        width (hooks/use-width box [(boolean (seq agents))])
        running (filter #(and (running? %) (or (= "agent" (:kind %)) (:background %))) agents)
        phone? (< js/window.innerWidth 680)]
    (use-tick! (boolean (seq running)))
    (when (seq agents)
      (let [finished (remove running? agents)
            failed (count (filter #(= "failed" (:status %)) finished))
            done (- (count finished) failed)
            cols (max 1 (js/Math.floor (/ (+ width gap) (+ chip-min gap))))
            room (cond phone? 2 (= cols 1) 3 :else (min 4 (* cols 2)))
            shown (->> running (sort-by #(- (:active %))) (take room) (sort-by :start))
            more (- (count running) (count shown))]
        [:div.ag-row {:ref #(set! (.-current box) %)}
         (when (seq shown)
           [:div.ag-chips {:style {:grid-template-columns (str "repeat(" (min cols (count shown) (if phone? 2 4)) ", minmax(0, 1fr))")}}
            (for [a shown] ^{:key (:id a)} [chip ck a @(rf/subscribe [:read-only?])])])
         (when (or (pos? more) (seq finished))
           [:div.ag-sum
            (when (pos? more)
              [:button.ag-pill {:title "Every agent of this chat" :on-click #(rf/dispatch [:agents/open ck ""])}
               (str "+" more " more")])
            (when (seq finished)
              [:button.ag-pill.done {:title (str done " finished" (when (pos? failed) (str ", " failed " failed")) ": every agent of this chat")
                                     :on-click #(rf/dispatch [:agents/open ck ""])}
               (when (pos? done) [:span.ok (str "✓ " done)])
               (when (pos? failed) [:span.bad (str "✕ " failed)])])])]))))

;; ---- the list and one agent ----

(defn- agent-list [ck agents]
  (use-tick! (boolean (some running? agents)))
  (let [n (count (filter running? agents))]
    (if (empty? agents)
      [:p.muted.small.ag-pad "No agents in this chat."]
      [:div.ag-scroll
       [:h2.ag-title "Agents " [:span.muted.small (str (when (pos? n) (str n " running · ")) (count agents) " in this chat")]]
       [:div.ag-list (for [a agents] ^{:key (:id a)} [chip ck a @(rf/subscribe [:read-only?])])]])))

(defn- file-row [f]
  (let [open? (r/atom false)]
    (fn [f]
      [:li.change {:class (when @open? "open")}
       [:button.change-row {:on-click #(swap! open? not)}
        [:span.chev {:class (when @open? "open")} "›"]
        [:span.change-path {:title (:path f)} (:rel f)]
        (when (> (count (:edits f)) 1) [:span.muted.small (str (count (:edits f)) " edits")])
        [:span.counts [:span.plus "+" (:added f)] " " [:span.minus "−" (:removed f)]]]
       (when @open?
         (for [[i e] (map-indexed vector (:edits f))]
           ^{:key i}
           [:div.ag-diff
            [:span.muted.small (if (= "Write" (:name e)) "Written whole" (:name e))]
            [diff/diff-table (:before e) (:after e) false false]]))])))

(defn- branch-section
  "An agent in a worktree of its own: its branch, the commits on it and the whole change since it
  branched, each opening its patch."
  [ck id wt g]
  (let [rev (r/atom nil)]
    (fn [ck id wt g]
      (let [p (when @rev @(rf/subscribe [:agent-git ck id @rev]))
            pick! (fn [x] (if (= @rev x) (reset! rev nil) (do (reset! rev x) (rf/dispatch [:agents/git ck id x]))))]
        [:section.ag-git
         [:h3 "Branch " [:code (:branch wt)]]
         (if-not g
           [:p.muted.small "The branch is gone."]
           [:<>
            (when-not (:worktree g) [:p.muted.small "Its worktree folder is gone; the branch is read from the repo."])
            [:ul.ag-commits
             [:li [:button.ag-commit {:class (when (= @rev "BRANCHED") "on") :disabled (not (:base g))
                                      :on-click #(pick! "BRANCHED")}
                   [:b "All of it since it branched"]
                   [:span.muted.small (if (:base g)
                                        (str "from " (subs (:base g) 0 8) (when (:worktree g) ", uncommitted work too"))
                                        "where it branched isn't known")]]]
             (for [c (:commits g)]
               ^{:key (:hash c)}
               [:li [:button.ag-commit {:class (when (= @rev (:hash c)) "on") :on-click #(pick! (:hash c))}
                     [:code.h (subs (:hash c) 0 8)] [:span.ag-subj (:subject c)] [:span.muted.small (:when c)]]])
             (when (empty? (:commits g)) [:li.muted.small "No commits on the branch yet."])]
            (when @rev
              (cond (nil? p) [:p.muted.small "Loading…"]
                    (:error p) [:p.muted.small (:error p)]
                    (str/blank? (:patch p)) [:p.muted.small "No changes."]
                    :else [diff/patch-view (:patch p)]))])]))))

(defn- agent-view [ck id info]
  (let [[tab set-tab] (h/use-state "steps")
        d @(rf/subscribe [:agent-detail ck id])
        a (when (or info (:info d)) (merge (:info d) (into {} (remove (comp nil? val)) info)))
        live (running? a)
        shell? (= "shell" (:kind a))]
    (use-tick! live)
    ;; Its view again every 2 s while it runs (steps grow without a task event).
    (h/use-effect (fn []
                    (rf/dispatch [:agents/fetch ck id])
                    (when live
                      (let [t (js/setInterval #(rf/dispatch [:agents/fetch ck id]) 2000)]
                        #(js/clearInterval t))))
                  [(str ck) id live (:end a)])
    (cond
      (and (nil? a) (:error d)) [:p.muted.small.ag-pad (:error d)]
      (nil? a) [:p.muted.small.ag-pad "Loading…"]
      :else
      (let [tabs (if shell?
                   [["steps" "Output"] ["prompt" "Command"]]
                   [["steps" "Steps"] ["files" (if (seq (:files d)) (str "Files " (count (:files d))) "Files")]
                    ["prompt" "Prompt"] ["report" "Report"]])
            facts (remove nil?
                          [[(if live "Running for" "Took") (dur (elapsed a))]
                           (when-not shell? ["Tools" (str (:tools a))])
                           (when (:tokens a) ["Tokens" (tokens (:tokens a))])
                           (when (:model a) ["Model" (:model a)])
                           ["Kind" (if shell? "background shell" (str (:type a) (when (:background a) " · background")))]
                           (when-let [wt (:worktree d)]
                             ["Worktree" (str (:branch wt) (when-let [g (:git d)]
                                                             (let [n (count (:commits g))] (str " · " n (if (= 1 n) " commit" " commits")))))])])]
        [:div.ag-scroll
         [:div.ag-head
          [:h2.ag-title.ag-name-line [status-mark a] [:span.ag-title-text (:name a)]]
          [:div.ag-state
           [:span.chip {:class (case (:status a) "failed" "red" "running" "amber" "completed" "green" "")}
            (case (:status a) "completed" "done" (:status a))]
           (when (and live (:task a) (not @(rf/subscribe [:read-only?])))
             [:button.task-stop {:on-click #(rf/dispatch [:agents/stop ck (:task a)])} "Stop"])]
          [:dl.ag-dl (for [[k v] facts] ^{:key k} [:<> [:dt k] [:dd v]])]
          (when (and live (:step a)) [:p.ag-now [:span.muted "Now"] " " [:code (:step a)]])]
         [:div.ag-tabs {:role "tablist"}
          (for [[t label] tabs]
            ^{:key t} [:button {:role "tab" :aria-selected (= tab t) :class (when (= tab t) "on") :on-click #(set-tab t)} label])]
         [:div.ag-body
          (case tab
            "steps" (cond
                      (nil? d) [:p.muted.small "Loading…"]
                      shell? (if (seq (:output d))
                               [:pre.ag-prompt (:output d)]
                               [:p.muted.small "No output yet."])
                      (seq (:steps d)) [:div.ag-steps [steps/transcript (:steps d) (running? a)]]
                      :else [:p.muted.small "Its steps show once its file is written."])
            "files" (if (nil? d)
                      [:p.muted.small "Loading…"]
                      [:div.ag-files
                       (if (seq (:files d))
                         [:ul.ag-file-list (for [f (:files d)] ^{:key (:path f)} [file-row f])]
                         [:p.muted.small (str "No Edit or Write calls" (when live " yet") ".")])
                       (when (:worktree d) [branch-section ck id (:worktree d) (:git d)])])
            "prompt" (let [p (if shell? (:command d) (:prompt d))]
                       (cond (nil? d) [:p.muted.small "Loading…"]
                             (seq p) [:pre.ag-prompt p]
                             :else [:p.muted.small "The prompt isn't known."]))
            "report" (cond live [:p.muted.small "It reports when it finishes."]
                           (nil? d) [:p.muted.small "Loading…"]
                           (seq (:report d)) [:div.md.ag-report {:dangerouslySetInnerHTML (r/unsafe-html (md/html (:report d)))}]
                           :else [:p.muted.small "No report."])
            nil)]]))))

(defn screen
  "Over the chat in its column: the list (id \"\") or one agent."
  [{:keys [ck state]} id]
  [:div.ag-screen
   [:div.ag-top
    [:button.ag-back {:on-click #(rf/dispatch [:agents/close ck])}
     [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M12.5 4L6.5 10l6 6"}]]
     "Back to the chat"]]
   (if (= "" id)
     [:f> agent-list ck (:agents state)]
     ^{:key id} [:f> agent-view ck id (some #(when (= id (:id %)) %) (:agents state))])])

;; ---- the rail's third state ----

(defn agents-ring
  "Claude's turn ended but work it started still runs: a dashed amber ring round the chat's circle,
  with how many."
  [n]
  [:<>
   [:svg.ag-ring {:viewBox "0 0 48 48" :aria-hidden true} [:circle {:cx 24 :cy 24 :r 21}]]
   [:span.state-badge.agents {:title (str n (if (= 1 n) " agent" " agents") " still working")} (when (pos? n) n)]])
