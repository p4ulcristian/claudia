(ns claudia.web.views.system-lists
  "System's list tabs (README decision 95): Ports (port, reach, the process and its owner, the
  project), Services (grouped: Claudia, projects, no project, containers, the system's own folded),
  Processes (grouped by owner, then the leftovers) and Log (the week's events, newest first)."
  (:require [clojure.string :as str]
            [reagent.hooks :as h]
            [claudia.web.format :as format]
            [claudia.web.views.pure.system-lists :as pure]
            [claudia.web.views.system-bits :as bits]))

(defn- empty-note [text] [:div.sy-empty text])

;; ---- Ports ----

(defn- port-row [sc p]
  [:div.sy-row.sy-port {:data-tip (str (:proto p) " on " (str/join ", " (:addrs p)))}
   [:span.sy-port-n (:port p) (when (= "udp" (:proto p)) [:small " udp"])]
   [:span [:span.sy-reach {:class (:reach p)} (pure/reach-text (:reach p))]]
   [:span.sy-proc
    (if (:name p) [:span.sy-mono (:name p)] [:span.sy-muted "no process seen"])
    (when (pure/owner-text (:owner p)) [:<> [:span.sy-dot-sep " · "] [bits/owner-tag (:owner p)]])]
   [:f> bits/project-cell sc (pure/label-keys p) :full]])

(defn ports
  "Every listener, one row each, port order."
  [{:keys [details] :as sc}]
  (let [rows (pure/port-rows (:ports details))]
    (if (empty? rows)
      [empty-note "No open ports."]
      [:div.sy-rows (for [p rows] ^{:key (str (:proto p) (:port p) (:pid p))} [port-row sc p])])))

;; ---- Services ----

(defn- service-row
  "A service: its dot, name, how long it has been so, restarts, memory, project (cell: its group's
  :cell, claudia.web.views.system-bits/project-cell's mode)."
  [{:keys [now] :as sc} s cell]
  [:div.sy-row.sy-service
   [:i.sy-dot {:class (pure/service-tone (:state s)) :data-tip (str (:state s) " · " (:sub s))}]
   [:span.sy-svc-name {:data-tip (:desc s)} [:span.sy-mono (:name s)]
    (when (= "user" (:scope s)) [:span.sy-muted " " (:user s)])]
   [:span.sy-svc-up {:class (when (= "failed" (:state s)) "red")} (pure/service-up-text s now)]
   [:span.sy-restarts (when (pos? (or (:restarts s) 0)) (str "↻ " (:restarts s)))]
   [:span.sy-mono.sy-mem (when (:rss s) (format/bytes-text (:rss s)))]
   [:f> bits/project-cell sc {:unit (:name s) :claudia? (= "claudia" (:origin s))} cell]])

(defn- group-head
  "A group's header: its name, its counts; a folded group's is a button that opens it."
  [{:keys [title services folded?]} open? toggle!]
  (let [counts [:span.sy-counts {:class (when (some #(= "failed" (:state %)) services) "bad")}
                (pure/group-counts services)]]
    (if folded?
      [:button.sy-group-head.fold {:type "button" :aria-expanded open? :on-click toggle!}
       [:span (if open? "▾ " "▸ ") title] counts]
      [:div.sy-group-head [:span title] counts])))

(defn- service-group [sc g]
  (let [[open? set-open] (h/use-state false)]
    [:section.sy-group
     [group-head g open? #(set-open not)]
     (when (or open? (not (:folded? g)))
       [:div.sy-rows (for [s (:services g)]
                       ^{:key (str (:scope s) (:user s) (:name s))} [service-row sc s (:cell g)])])]))

(defn services
  "The services, grouped."
  [{:keys [details labels tab] :as sc}]
  (let [gs (pure/service-groups (:services details) labels (:name tab))]
    (if (empty? gs)
      [empty-note "No services seen."]
      [:<> (for [g gs] ^{:key (:id g)} [:f> service-group sc g])])))

;; ---- Processes ----

(defn- group-who [{:keys [owner]}]
  (if-let [c (:chat owner)]
    [:span.sy-owner [bits/chat-square (:colour c)] (:title c)]
    [:span.sy-owner (or (pure/owner-text owner) (:name owner) "other")]))

(defn- process-row [g]
  [:div.sy-row.sy-proc-row
   [group-who g]
   [:span.sy-mono.sy-muted.sy-names (str/join " " (:names g)) (when (> (:count g) (count (:names g))) " …")]
   [:span.sy-mono (pure/cores-text (:cores g))]
   [:span.sy-mono.sy-mem (format/bytes-text (:rss g))]])

(defn- leftover-row [all-ports p]
  (let [ps (pure/ports-of all-ports (:pid p))]
    [:div.sy-row.sy-proc-row
     [:span.sy-owner.sy-mono (:name p)]
     [:span.sy-muted (pure/old-text (or (:age p) 0)) (when (seq ps) (str " · ports " (str/join ", " ps)))]
     [:span.sy-mono (pure/cores-text (:cores p))]
     [:span.sy-mono.sy-mem (format/bytes-text (:rss p))]]))

(defn- leftover-section [{:keys [leftovers] :as details}]
  (when (seq leftovers)
    [:section.sy-group
     [:div.sy-group-head.amber [:span "Leftovers"] [:span.sy-counts "nothing owns them"]]
     [:div.sy-rows (for [p leftovers] ^{:key (:pid p)} [leftover-row (:ports details) p])]]))

(defn processes
  "Every process grouped by its owner, most memory first, then the leftovers."
  [{:keys [details]}]
  [:<>
   (for [{:keys [id title groups]} (pure/process-groups (:groups details))]
     ^{:key id}
     [:section.sy-group
      [:div.sy-group-head [:span title] [:span.sy-counts (str (pure/process-count groups) " processes")]]
      [:div.sy-rows (for [[i g] (map-indexed vector groups)] ^{:key i} [process-row g])]])
   [leftover-section details]])

;; ---- Log ----

(def ^:private event-icons
  "Each event's mark (text, not emoji: U+FE0E asks for the text look)."
  {"failed" "✕" "crash" "✕" "oom" "⚠\uFE0E" "reboot" "⟳"})

(defn- event-row [{:keys [tab now]} e]
  (let [[log toggle-log] (bits/use-unit-log tab (when (:unit e) {:unit (:unit e) :scope "system"}))]
    [:div.sy-event
     [:div.sy-row.sy-event-row
      [:span.sy-mono.sy-muted (format/clock-text (:at e) now)]
      [:span.sy-event-icon {:class (if (= "reboot" (:kind e)) "amber" "red")} (event-icons (:kind e) "•")]
      [:span.sy-event-text (:text e)]
      (when toggle-log [bits/button {:on-click toggle-log} (if log "Hide" "Log")])]
     (when log [bits/log-lines log now])]))

(defn log-tab
  "The week's events, newest first."
  [{:keys [details] :as sc}]
  (if (empty? (:log details))
    [empty-note "Nothing happened this week: no crash, out-of-memory kill or failed unit."]
    [:div.sy-rows (for [e (:log details)] ^{:key (str (:at e) (:kind e) (:pid e) (:unit e))} [:f> event-row sc e])]))
