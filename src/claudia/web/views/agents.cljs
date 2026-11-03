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
            [claudia.web.views.pure.agents :as pure]
            [claudia.web.views.steps :as steps]))

;; ---- small words ----

(def dur pure/dur)
(def tokens pure/tokens)

(def ^:private running? pure/running?)

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
  [:span.ag-facts (str/join " · " (pure/facts-words a (js/Date.now)))])

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

(defn- chip-sum [ck {:keys [more finished failed done]}]
  [:div.ag-sum
   (when (pos? more)
     [:button.ag-pill {:title "Every agent of this chat" :on-click #(rf/dispatch [:agents/open ck ""])}
      (str "+" more " more")])
   (when (seq finished)
     [:button.ag-pill.done {:title (str done " finished" (when (pos? failed) (str ", " failed " failed")) ": every agent of this chat")
                            :on-click #(rf/dispatch [:agents/open ck ""])}
      (when (pos? done) [:span.ok (str "✓ " done)])
      (when (pos? failed) [:span.bad (str "✕ " failed)])])])

(defn chips
  "The running ones (as many as fit: up to four on a desktop, two on a phone), +N more, ✓ N ✕ M."
  [{:keys [ck state]}]
  (let [box (h/use-ref nil)
        agents (:agents state)
        width (hooks/use-width box [(boolean (seq agents))])
        {:keys [shown more finished columns] :as row} (pure/chip-row agents width (< js/window.innerWidth 680))]
    (use-tick! (boolean (some #(and (running? %) (or (= "agent" (:kind %)) (:background %))) agents)))
    (when (seq agents)
      [:div.ag-row {:ref #(set! (.-current box) %)}
       (when (seq shown)
         [:div.ag-chips {:style {:grid-template-columns (str "repeat(" columns ", minmax(0, 1fr))")}}
          (for [a shown] ^{:key (:id a)} [chip ck a @(rf/subscribe [:read-only?])])])
       (when (or (pos? more) (seq finished))
         (chip-sum ck row))])))

;; ---- the list and one agent ----

(defn- agent-list [ck agents]
  (use-tick! (boolean (some running? agents)))
  (let [n (count (filter running? agents))]
    (if (empty? agents)
      [:p.muted.small.ag-pad "No agents in this chat."]
      [:div.ag-scroll
       [:h2.ag-title "Agents " [:span.muted.small (str (when (pos? n) (str n " running · ")) (count agents) " in this chat")]]
       [:div.ag-list (for [a agents] ^{:key (:id a)} [chip ck a @(rf/subscribe [:read-only?])])]])))

(defn- file-row [_f]
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

(defn- commit-list [g rev pick!]
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
   (when (empty? (:commits g)) [:li.muted.small "No commits on the branch yet."])])

(defn- patch-of [p]
  (cond (nil? p) [:p.muted.small "Loading…"]
        (:error p) [:p.muted.small (:error p)]
        (str/blank? (:patch p)) [:p.muted.small "No changes."]
        :else [diff/patch-view (:patch p)]))

(defn- branch-section
  "An agent in a worktree of its own: its branch, the commits on it and the whole change since it
  branched, each opening its patch."
  [_ck _id _wt _g]
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
            (commit-list g rev pick!)
            (when @rev (patch-of p))])]))))

(defn- steps-tab [a d shell?]
  (cond
    (nil? d) [:p.muted.small "Loading…"]
    shell? (if (seq (:output d))
             [:pre.ag-prompt (:output d)]
             [:p.muted.small "No output yet."])
    (seq (:steps d)) [:div.ag-steps [steps/transcript (:steps d) (running? a)]]
    :else [:p.muted.small "Its steps show once its file is written."]))

(defn- files-tab [ck id d live]
  (if (nil? d)
    [:p.muted.small "Loading…"]
    [:div.ag-files
     (if (seq (:files d))
       [:ul.ag-file-list (for [f (:files d)] ^{:key (:path f)} [file-row f])]
       [:p.muted.small (str "No Edit or Write calls" (when live " yet") ".")])
     (when (:worktree d) [branch-section ck id (:worktree d) (:git d)])]))

(defn- tab-body [tab {:keys [ck id a d live shell?]}]
  (case tab
    "steps" (steps-tab a d shell?)
    "files" (files-tab ck id d live)
    "prompt" (let [p (if shell? (:command d) (:prompt d))]
               (cond (nil? d) [:p.muted.small "Loading…"]
                     (seq p) [:pre.ag-prompt p]
                     :else [:p.muted.small "The prompt isn't known."]))
    "report" (cond live [:p.muted.small "It reports when it finishes."]
                   (nil? d) [:p.muted.small "Loading…"]
                   (seq (:report d)) [:div.md.ag-report {:dangerouslySetInnerHTML (r/unsafe-html (md/html (:report d)))}]
                   :else [:p.muted.small "No report."])
    nil))

(defn- agent-head [{:keys [ck a d live]}]
  (let [[chip-class word] (pure/status-chip (:status a))]
    [:div.ag-head
     [:h2.ag-title.ag-name-line [status-mark a] [:span.ag-title-text (:name a)]]
     [:div.ag-state
      [:span.chip {:class chip-class} word]
      (when (and live (:task a) (not @(rf/subscribe [:read-only?])))
        [:button.task-stop {:on-click #(rf/dispatch [:agents/stop ck (:task a)])} "Stop"])]
     [:dl.ag-dl (for [[k v] (pure/facts a d (js/Date.now))] ^{:key k} [:<> [:dt k] [:dd v]])]
     (when (and live (:step a)) [:p.ag-now [:span.muted "Now"] " " [:code (:step a)]])]))

(defn- agent-page [tab set-tab {:keys [d shell?] :as props}]
  [:div.ag-scroll
   (agent-head props)
   [:div.ag-tabs {:role "tablist"}
    (for [[t label] (pure/tabs shell? (:files d))]
      ^{:key t} [:button {:role "tab" :aria-selected (= tab t) :class (when (= tab t) "on") :on-click #(set-tab t)} label])]
   [:div.ag-body (tab-body tab props)]])

(defn- use-agent-reads!
  "Its view again every 2 s while it runs (steps grow without a task event)."
  [ck id live end]
  (h/use-effect (fn []
                  (rf/dispatch [:agents/fetch ck id])
                  (when live
                    (let [t (js/setInterval #(rf/dispatch [:agents/fetch ck id]) 2000)]
                      #(js/clearInterval t))))
                [(str ck) id live end]))

(defn- agent-view [ck id info]
  (let [[tab set-tab] (h/use-state "steps")
        d @(rf/subscribe [:agent-detail ck id])
        a (pure/merged-info info d)
        live (running? a)]
    (use-tick! live)
    (use-agent-reads! ck id live (:end a))
    (cond
      (and (nil? a) (:error d)) [:p.muted.small.ag-pad (:error d)]
      (nil? a) [:p.muted.small.ag-pad "Loading…"]
      :else (agent-page tab set-tab {:ck ck :id id :a a :d d :live live :shell? (= "shell" (:kind a))}))))

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
