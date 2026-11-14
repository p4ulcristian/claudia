(ns claudia.web.views.update-pill
  "The update pill at the rail's foot (README decision 32): while an update moves (the builder
  checking and building, then every server and the hub taking it) it says where it stands; a click
  lists every step. It asks GET /hub/progress every 2 s while something moves, every 15 s otherwise,
  and is not there when nothing moves."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.api :as api]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.pure.update-pill :as pure]))

(defn- use-progress
  "The hub's latest word on updates, asked again sooner while the pill shows."
  []
  (let [[p set-p] (h/use-state nil)]
    (h/use-effect
     (fn []
       (let [timer (atom nil) alive (atom true)
             ask (fn ask []
                   (-> (api/fetch-json "/hub/progress")
                       (.then (fn [v] (when @alive (set-p v)) v))
                       (.catch (fn [_] nil))
                       (.then (fn [v] (when @alive
                                        (reset! timer (js/setTimeout ask (if (pure/pill v (js/Date.now)) 2000 15000))))))))]
         (ask)
         #(do (reset! alive false) (some-> @timer js/clearTimeout))))
     [])
    p))

(defn- step-row [[what st note]]
  [:li.up-step {:class st}
   [:span.up-what what] [:span.up-state st] (when note [:span.up-note note])])

(defn- view []
  (let [p (use-progress)
        [open set-open] (h/use-state false)
        shown (when p (pure/pill p (js/Date.now)))]
    (when shown
      [:div.up-box
       [:button.up-pill {:class (:tone shown) :type "button" :aria-expanded open
                         :on-click #(set-open (not open))}
        (:label shown)]
       (when open
         [:ul.up-panel (for [[i s] (map-indexed vector (pure/steps p))] ^{:key i} [step-row s])])])))

(defn pill "The rail's update pill." [] [:f> view])

(defn- updated-view []
  (let [t (hooks/use-now)]
    (when-let [text (pure/updated-text (:commit @(rf/subscribe [:me])) (:updatedAt @(rf/subscribe [:me])) t)]
      [:div.up-ago {:title (str "This version went live " (.toLocaleString (js/Date. (:updatedAt @(rf/subscribe [:me])))))} text])))

(defn updated
  "The small line under the rail's gear: when this version went live."
  []
  [:f> updated-view])
