(ns claudia.web.views.system-bits
  "Small parts the System sheet's rows share (README decision 96): a chat's colour square, who owns
  a process, a row's project chip (admins add or take off a label there), a unit's journal lines
  under a row, and the buttons."
  (:require [reagent.hooks :as h]
            [claudia.web.api :as api]
            [claudia.web.format :as format]
            [claudia.web.views.pure.system-feed :as feed]
            [claudia.web.views.pure.system-lists :as lists]
            [claudia.web.views.pure.words :as words]))

(defn chat-square
  "A chat's colour as a small square."
  [colour]
  [:i.sy-square {:style {:background (words/colour-fill (or colour "ash"))}}])

(defn owner-tag
  "Who owns a process: a chat's square and title, else the owner in words."
  [owner]
  (when-let [t (lists/owner-text owner)]
    [:span.sy-owner
     (when-let [c (:chat owner)] [chat-square (:colour c)])
     t]))

(defn button
  "A row's small button; primary: filled with the accent."
  [{:keys [primary? on-click disabled tip]} word]
  [:button.sy-btn {:type "button" :class (when primary? "primary") :disabled disabled
                   :data-tip tip :on-click on-click}
   word])

;; ---- labels ----

(defn- label-call!
  "A label written or taken off on the hub; then (refresh!), or (failed message)."
  [method url body refresh! failed]
  (-> (api/call-json method url body)
      (.then (fn [_] (refresh!)))
      (.catch #(failed (.-message %)))))

(defn- label-input
  "The admin's field for a row's project: Enter keeps it, Escape or leaving it empty cancels."
  [{:keys [save! cancel! error]}]
  [:span.sy-label-edit
   [:input.sy-input {:auto-focus true :placeholder "project" :max-length 120 :aria-label "Project"
                     :on-key-down #(case (.-key %)
                                     "Enter" (save! (.. % -currentTarget -value))
                                     "Escape" (cancel!)
                                     nil)
                     :on-blur #(when (= "" (.. % -target -value)) (cancel!))}]
   (when error [:span.sy-err error])])

(defn- chip
  "A row's project; admins get an × that takes the label off. grouped? (its group's title already
  names it): only the admin's ×, shown on hover."
  [l admin? remove! grouped?]
  (let [x (when admin?
            [:button.sy-chip-x {:type "button" :aria-label (str "Take off the label " (:project l))
                                :data-tip (str "Take off the label " (:project l)) :on-click remove!} "×"])]
    (if grouped?
      [:span.sy-ghost x]
      [:span.sy-chip {:data-tip (str "Label by " (:by l) (when (= "*" (:server l)) ", for every server"))}
       (:project l) x])))

(defn project-cell
  "A row's project from the labels, or \"unknown\"; an admin may label it here (README decision 96).
  keys-of: what the row can be labelled by (claudia.web.views.pure.system-lists/label-keys);
  Claudia's own rows are Claudia's. mode: :full, :quiet (no \"unknown\") or :grouped (the group's
  title names the project: no chip)."
  [{:keys [admin? server labels refresh!]} keys-of mode]
  (let [[editing set-editing] (h/use-state false)
        [error set-error] (h/use-state nil)
        l (lists/label-for labels server keys-of)
        target (lists/label-target keys-of)
        done! #(do (set-editing false) (set-error nil) (refresh!))
        save! #(label-call! "PUT" "/hub/system/labels" (assoc target :server server :project %) done! set-error)]
    [:span.sy-project
     (when (and admin? target (not l) (not editing) (not (:claudia? keys-of)))
       [:button.sy-ghost {:type "button" :data-tip (str "Name the project of " (:kind target) " " (:match target))
                          :on-click #(set-editing true)} "label"])
     (cond editing [label-input {:save! save! :cancel! #(set-editing false) :error error}]
           (:claudia? keys-of) (when-not (= mode :grouped) [:span.sy-chip "Claudia"])
           l [chip l admin? #(label-call! "DELETE" (str "/hub/system/labels/" (:id l)) nil refresh! set-error) (= mode :grouped)]
           (= mode :full) [:span.sy-chip.unknown {:data-tip "No label names its project"} "unknown"])]))

;; ---- a unit's journal ----

(defn use-unit-log
  "A unit's last journal lines, asked on a press: [log toggle!] (log nil, {:loading true},
  {:lines} or {:error}); nil toggle! when the tab reads no journal (the gate)."
  [tab unit]
  (let [[log set-log] (h/use-state nil)
        url (when unit (feed/unit-log-url tab unit 50))]
    [log (when url
           (fn []
             (if log
               (set-log nil)
               (do (set-log {:loading true})
                   (-> (api/fetch-json url)
                       (.then #(set-log {:lines (:lines %)}))
                       (.catch #(set-log {:error (.-message %)})))))))]))

(defn- prio-class [p] (cond (nil? p) nil (<= p 3) "red" (= p 4) "amber" :else nil))

(defn log-lines
  "The journal lines under a row, oldest first."
  [{:keys [loading lines error]} now]
  [:div.sy-log
   (cond loading [:div.sy-log-note "Reading the journal…"]
         error [:div.sy-log-note.red error]
         (empty? lines) [:div.sy-log-note "No lines in its journal."]
         :else (for [[i {:keys [at prio text]}] (map-indexed vector lines)]
                 ^{:key i} [:div.sy-log-line {:class (prio-class prio)}
                            [:span.sy-log-at (format/clock-text at now)] [:span.sy-log-text text]]))])
