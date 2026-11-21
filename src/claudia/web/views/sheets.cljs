(ns claudia.web.views.sheets
  "The sheets of paper on the Drawing board (README decision 91): every view but the board (File
  Browser, Search, Settings, System, Prompts, Forms) is a sheet laid on it, each one a
  step down and right of the one under it, the newest on top. Only the top sheet is drawn in full and
  takes input; the ones under it show their edges, and a click on one brings it to the top. Its ×
  closes it (claudia.web.right keeps the pile)."
  (:require [re-frame.core :as rf]
            [claudia.shared.views :as views]
            [claudia.web.folders :as folders]
            [claudia.web.views.codeforms :as codeforms]
            [claudia.web.views.folders :as folder-views]
            [claudia.web.views.prompts :as prompts]
            [claudia.web.views.search-panel :as search-panel]
            [claudia.web.views.settings :as settings]
            [claudia.web.views.systems :as systems]))

(def ^:private icon-parts
  "Each sheet's icon, drawn in a 20×20 box."
  {:files [[:path {:d "M2.5 5.5A1.5 1.5 0 0 1 4 4h3.5l2 2H16a1.5 1.5 0 0 1 1.5 1.5v7A1.5 1.5 0 0 1 16 16H4a1.5 1.5 0 0 1-1.5-1.5z"}]]
   :search [[:circle {:cx 9 :cy 9 :r 5}] [:path {:d "M16.5 16.5l-3.8-3.8"}]]
   :settings [[:circle {:cx 10 :cy 10 :r 2.6}] [:circle {:cx 10 :cy 10 :r 5.4}]
              [:path {:d "M10 2.5v2.2M10 15.3v2.2M2.5 10h2.2M15.3 10h2.2M4.7 4.7l1.6 1.6M13.7 13.7l1.6 1.6M4.7 15.3l1.6-1.6M13.7 6.3l1.6-1.6"}]]
   :system systems/icon-parts
   :prompts [[:rect {:x 4 :y 2.5 :width 12 :height 15 :rx 1.5}] [:path {:d "M7 6.5h6M7 10h6M7 13.5h3.5"}]]
   :forms [[:rect {:x 3 :y 3 :width 6 :height 6 :rx 1.5}] [:circle {:cx 14 :cy 6 :r 3}] [:path {:d "M3 15h6M11 12.5l3 3 3-3"}]]})

(defn- icon [v]
  (into [:svg.sheet-icon {:viewBox "0 0 20 20" :aria-hidden true}] (icon-parts v)))

(defn- forms-pane
  "The Forms gallery for the open chat's server, else the new chat's."
  [oc]
  (when-let [server (or (:server oc) @(rf/subscribe [:new-server]))]
    ^{:key server} [:f> codeforms/gallery server]))

(defn- body
  "The view v's content."
  [v oc]
  (case v
    :files [folder-views/pane (folders/chat-place oc @(rf/subscribe [:new-server]) @(rf/subscribe [:new-settings]))]
    :search [:div.right-scroll [search-panel/pane]]
    :settings [:div.right-scroll [settings/pane]]
    :system [:div.right-scroll [systems/pane]]
    :prompts [:div.right-scroll [prompts/pane]]
    :forms [:div.right-scroll [forms-pane oc]]
    nil))

(defn- head
  "The sheet's title strip: its icon and name, and on the top sheet the × that closes it."
  [v top?]
  (let [label (views/label v)]
    [:header.sheet-head
     [icon v]
     [:h2.sheet-title label]
     (when top?
       [:button.sheet-x {:type "button" :aria-label (str "Close " label) :title (str "Close " label)
                         :on-click #(rf/dispatch [:right/close v])}
        "×"])]))

(defn- sheet
  "One sheet, i steps up the pile: the top one in full; one under it only its paper, which a click
  brings to the top."
  [v i top? oc]
  [:section.sheet {:class (when-not top? "under") :style {"--i" i}
                   :role "region" :aria-label (views/label v)}
   [head v top?]
   (if top?
     [:div.sheet-body [body v oc]]
     [:button.sheet-up {:type "button" :aria-label (str "Bring " (views/label v) " to the top")
                        :on-click #(rf/dispatch [:right/view v])}])])

(defn pile
  "The sheets on the board, bottom to top (none: nothing over the board)."
  [shown oc]
  (when (seq shown)
    (let [n (count shown)]
      [:div.sheets {:style {"--n" n}}
       (for [[i v] (map-indexed vector shown)]
         ^{:key v} [sheet v i (= i (dec n)) oc])])))
