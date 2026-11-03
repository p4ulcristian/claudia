(ns claudia.web.views.steps
  "Claude's tool steps, as the TypeScript Claudia's chat showed them (web/chat.tsx): runs of steps
  folded into groups, a step that showed a picture on its own; each step can be explained in plain
  words (Haiku, on the side)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.web.md :as md]
            [claudia.web.views.pure.steps :as pure]))

(def tool-label pure/tool-label)
(def blocks pure/blocks)

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

(defn- step-detail [{:keys [error detail]} result]
  [:div.step-detail
   (when detail [:pre detail])
   (when result [:pre {:class (when error "err")} (if (str/blank? result) "(no output)" result)])])

(defn- step [_ck _t _busy?]
  (let [open (r/atom false)]
    (fn [ck {:keys [id tool error detail] :as t} busy?]
      (let [summary (pure/step-summary t)
            result (pure/step-result (:result t))]
        [:li.step {:class (cond busy? "busy" error "err" :else "ok")}
         [:div.step-line
          [:button.step-row {:on-click #(swap! open not)}
           [:span.step-dot]
           [:b (tool-label (or tool "tool"))]
           [:span.step-title (or summary "")]]
          (when ck [explain-button ck id false])]
         (when ck [explain-note ck id])
         (when (and @open (or detail result))
           (step-detail t result))]))))

(defn- steps-head [open tools current]
  (let [errors (count (filter :error tools))]
    [:button.steps-head {:on-click #(swap! open not)}
     [:span.chev {:class (when @open "open")} "›"]
     [:span (str (count tools) (if (= 1 (count tools)) " step" " steps"))]
     [:span.muted (if current
                    (str (tool-label (:tool current)) " " (pure/step-summary current))
                    (pure/group-names tools))]
     (when (pos? errors) [:span.err-count (str errors " failed")])]))

(defn steps
  "A run of tool steps, folded: › N steps, what they were (the current one while it runs), N failed;
  open, a row per step. ck nil: no explain (an agent's transcript)."
  [_ck _tools _live?]
  (let [open (r/atom false)]
    (fn [ck tools live?]
      (let [current (when live? (peek tools))]
        [:div.steps {:class (when live? "busy")}
         [:div.steps-head-line
          (steps-head open tools current)
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
