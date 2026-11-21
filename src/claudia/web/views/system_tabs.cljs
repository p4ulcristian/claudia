(ns claudia.web.views.system-tabs
  "System's tabs under \"Needs a look\" (README decision 95): Ports, Services, Processes, History,
  Log and Machine (the old Machines card), each with its count; a server without the root helper
  says why once, quietly, above them."
  (:require [claudia.web.views.pure.system-lists :as lists]
            [claudia.web.views.pure.systems :as systems]
            [claudia.web.views.system-history :as history]
            [claudia.web.views.system-lists :as lists-view]
            [claudia.web.views.system-machine :as machine]))

(def tab-names
  [["ports" "Ports"] ["services" "Services"] ["processes" "Processes"] ["history" "History"]
   ["log" "Log"] ["machine" "Machine"]])

(defn- tab-bar [view set-view details]
  (let [n (lists/tab-counts details)]
    [:div.sy-tabs {:role "tablist"}
     (for [[id label] tab-names]
       ^{:key id}
       [:button.sy-tab {:type "button" :role "tab" :aria-selected (= id view) :class (when (= id view) "on")
                        :on-click #(set-view id)}
        label (when-let [c (get n id)] [:small c])])]))

(defn- machine-tab [{:keys [tab sample error got-at now]}]
  [machine/machine-view {:title (:label tab) :sample sample :error error
                         :age (systems/age-ms sample got-at now)}])

(defn- body [view {:keys [details] :as sc}]
  (cond
    (= view "machine") [machine-tab sc]
    (= view "history") [:f> history/history sc]
    (nil? details) [:div.sy-empty (if (:details-error sc) (str "No details: " (:details-error sc)) "Asking…")]
    :else (case view
            "ports" [lists-view/ports sc]
            "services" [lists-view/services sc]
            "processes" [lists-view/processes sc]
            "log" [lists-view/log-tab sc]
            nil)))

(defn tabs
  "The tab bar and the tab shown (view, set-view: the sheet's)."
  [{:keys [details view set-view] :as sc}]
  [:section.sy-tabbed
   (when (false? (:root details))
     [:p.sy-why (or (:why details) "Without the root helper, System shows only what Claudia's own user can see here.")])
   [tab-bar view set-view details]
   [:div.sy-tab-body [body view sc]]])
