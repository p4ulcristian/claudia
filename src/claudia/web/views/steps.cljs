(ns claudia.web.views.steps
  "Claude's tool steps, as the TypeScript Claudia's chat showed them (web/chat.tsx): runs of steps
  folded into groups, a step that showed a picture on its own; each step can be explained in plain
  words (Haiku, on the side)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.web.md :as md]))

(defn tool-label
  "A tool's name as the chat shows it: Draw, Folder, Ask; an MCP tool without its mcp__ prefix."
  [name]
  (let [name (str name)]
    (case name
      "mcp__picture__draw" "Draw"
      "mcp__workspace__set_folder" "Folder"
      "AskUserQuestion" "Ask"
      (if (str/starts-with? name "mcp__") (str/join " " (rest (str/split name #"__"))) name))))

(defn blocks
  "The messages as the page shows them: runs of tool steps folded into one group; a step that showed
  a picture stands on its own, its picture after it."
  [msgs]
  (reduce (fn [out m]
            (cond
              (and (= "tool" (:role m)) (:image m))
              (conj out {:k :steps :id (:id m) :tools [m]} {:k :shown :tool m})
              (= "tool" (:role m))
              (if (= :steps (:k (peek out)))
                (conj (pop out) (update (peek out) :tools conj m))
                (conj out {:k :steps :id (:id m) :tools [m]}))
              :else (conj out {:k :msg :m m})))
          [] msgs))

(rf/reg-event-db :explain/close (fn [db [_ k]] (assoc-in db [:explain k :open] false)))

(defn- explain-note [ck id]
  (when-let [{:keys [busy text error open]} @(rf/subscribe [:explain (conj ck id)])]
    (when open
      [:div.explain-note {:class [(when busy "busy") (when error "err")]}
       [:span (cond busy "explaining…" error (str "Couldn't explain: " error) :else text)]
       (when-not busy [:button.explain-x {:aria-label "Close" :on-click #(rf/dispatch [:explain/close (conj ck id)])} "×"])])))

(defn- explain-button [ck id live?]
  (let [{:keys [busy]} @(rf/subscribe [:explain (conj ck id)])]
    [:button.explain-btn {:class (when live? "live")
                          :title (if live? "Explain what this step is doing (Haiku, on the side)" "Explain this step in plain words (Haiku, on the side)")
                          :on-click #(rf/dispatch [:ops/explain ck id])}
     (if busy "…" "explain")]))

(defn- step [ck {:keys [id tool summary error detail result] :as _t} busy?]
  (let [open (r/atom false)]
    (fn [ck {:keys [id tool summary error detail result]} busy?]
      [:li.step {:class (cond busy? "busy" error "err" :else "ok")}
       [:div.step-line
        [:button.step-row {:on-click #(swap! open not)}
         [:span.step-dot]
         [:b (tool-label (or tool "tool"))]
         [:span.step-title (or summary "")]]
        (when ck [explain-button ck id false])]
       (when ck [explain-note ck id])
       (when (and @open (or detail result))
         [:div.step-detail
          (when detail [:pre detail])
          (when result [:pre {:class (when error "err")} (if (str/blank? result) "(no output)" result)])])])))

(defn steps
  "A run of tool steps, folded: › N steps, what they were (the current one while it runs), N failed;
  open, a row per step. ck nil: no explain (an agent's transcript)."
  [ck tools live?]
  (let [open (r/atom false)]
    (fn [ck tools live?]
      (let [current (when live? (peek tools))
            errors (count (filter :error tools))
            names (str/join ", " (take 4 (distinct (map #(tool-label (or (:tool %) "tool")) tools))))]
        [:div.steps {:class (when live? "busy")}
         [:div.steps-head-line
          [:button.steps-head {:on-click #(swap! open not)}
           [:span.chev {:class (when @open "open")} "›"]
           [:span (str (count tools) (if (= 1 (count tools)) " step" " steps"))]
           [:span.muted (if current (str (tool-label (:tool current)) " " (:summary current)) names)]
           (when (pos? errors) [:span.err-count (str errors " failed")])]
          (when (and ck current (not @open))
            [:<>
             [explain-button ck (:id current) true]
             (when @(rf/subscribe [:explain (conj ck (:id current))])
               [:div.explain-under [explain-note ck (:id current)]])])]
         (when @open
           [:ul (for [t tools] ^{:key (:id t)} [step ck t (and live? (= t current))])])]))))


(defn transcript
  "A subagent's messages, in the same compact form as the main chat."
  [msgs live?]
  (let [bs (blocks msgs)]
    (into [:div.transcript]
          (for [[i b] (map-indexed vector bs)]
            (case (:k b)
              :steps ^{:key (str "s" (:id b))} [steps nil (:tools b) (and live? (= i (dec (count bs))))]
              :shown nil
              (let [{:keys [id role text]} (:m b)]
                (when (= "assistant" role)
                  ^{:key id} [:div.msg.claude.small [:div.md {:dangerouslySetInnerHTML (r/unsafe-html (md/html text))}]])))))))
