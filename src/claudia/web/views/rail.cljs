(ns claudia.web.views.rail
  "The rail (desktop, the list closed; web/app.tsx of the TypeScript Claudia): + for a new chat, the
  search, then a circle per chat with its title under it and, on the hub, its server's name; passive
  (ash) chats last, above a chalk line. At its foot: sounds, the forms, the usage rings. Circles
  slide to their new places when the order changes; a new one grows in."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.alerts :as alerts]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.dot :as dot]
            [claudia.web.views.sidebar :as sidebar]
            [claudia.web.views.usage :as usage]))

(defn- use-slide!
  "Each [data-slide] child of box is measured after every change of order and animated from where
  it was; a new one grows in."
  [box order]
  (let [last (h/use-ref nil)]
    (h/use-layout-effect
     (fn []
       (if-let [^js el (.-current box)]
         (let [top (- (.-top (.getBoundingClientRect el)) (.-scrollTop el))
               items (array-seq (.querySelectorAll el "[data-slide]"))
               now (into {} (for [^js d items] [(.. d -dataset -slide) (- (.-top (.getBoundingClientRect d)) top)]))
               before (.-current last)]
           (set! (.-current last) now)
           (when (and before (not (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)"))))
             (doseq [^js d items
                     :let [k (.. d -dataset -slide) was (get before k) is (get now k)]]
               (cond
                 (nil? was) (.animate d #js [#js {:opacity 0 :transform "scale(0.4)"} #js {:opacity 1 :transform "none"}]
                                      #js {:duration 260 :easing "cubic-bezier(.2,.8,.3,1.2)"})
                 (> (js/Math.abs (- was is)) 1)
                 (.animate d #js [#js {:transform (str "translateY(" (- was is) "px)")} #js {:transform "none"}]
                           #js {:duration 420 :easing "cubic-bezier(.3,.7,.2,1)"})))))
         (set! (.-current last) nil))
       nil)
     [order])))

(defn- speaker [on?]
  [:svg {:viewBox "0 0 20 20" :aria-hidden true}
   [:path {:d "M3 8h3l4-3.5v11L6 12H3z"}]
   (if on?
     [:path {:d "M13 7.5c1 1.4 1 3.6 0 5M15.4 5.5c2 2.5 2 6.5 0 9"}]
     [:path {:d "M13.5 8l4 4M17.5 8l-4 4"}])])

(def forms-icon
  [:svg {:viewBox "0 0 20 20" :aria-hidden true}
   [:rect {:x 3 :y 3 :width 6 :height 6 :rx 1.5}] [:circle {:cx 14 :cy 6 :r 3}] [:path {:d "M3 15h6M11 12.5l3 3 3-3"}]])

(defn rail []
  (let [rows @(rf/subscribe [:rows])
        open @(rf/subscribe [:open])
        multi? @(rf/subscribe [:multi-server?])
        ro @(rf/subscribe [:read-only?])
        projects @(rf/subscribe [:projects])
        {:keys [text]} @(rf/subscribe [:search])
        sounds? (:sounds @alerts/on)
        q (str/lower-case (str/trim (str text)))
        rows (if (seq q) (filter #(str/includes? (str/lower-case (str (:title %))) q) rows) rows)
        ;; Passive chats last, in their own order.
        rows (vec (concat (remove #(= "ash" (:color %)) rows) (filter #(= "ash" (:color %)) rows)))
        box (h/use-ref nil)]
    (use-slide! box (str/join "," (map #(str (:server %) "/" (:id %)) rows)))
    [:nav.rail {:aria-label "Chats"}
     [:div.rail-dots {:ref #(set! (.-current box) %)}
      (when-not ro
        [:button.rail-dot.add {:title "New chat (Alt+N)" :on-click #(rf/dispatch [:nav/new])} "+"])
      [:button.rail-dot.add.rail-search {:data-tip "Search chats" :aria-label "Search chats"
                                         :on-click #(rf/dispatch [:sidebar/search])}
       [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:circle {:cx 8.5 :cy 8.5 :r 5}] [:path {:d "M12.3 12.3L17 17"}]]]
      (for [[i {:keys [server id title color off folder] :as r}] (map-indexed vector rows)
            :let [k [server id]
                  passive? (= "ash" color)
                  project (:name (get projects [server folder]))
                  label @(rf/subscribe [:server-label server])]]
        ^{:key (str server "/" id)}
        [:button.rail-chat {:data-slide (str server "/" id)
                            :class [(when (= k open) "on") (when off "off")
                                    (when passive? "passive")
                                    (when (and passive? (not= "ash" (:color (get rows (dec i))))) "first")
                                    (when (and passive? (= i (dec (count rows)))) "last")]
                            :style {"--chat" (sidebar/chat-color-var color)}
                            :data-tip (str title (when project (str " · " project))
                                           (when multi? (str " · " label (when off " is off"))))
                            :data-tip-color (sidebar/chat-color-var color)
                            :aria-label title
                            :on-click #(sidebar/pick! k (= k open) %)
                            :on-context-menu #(chatmenu/open-at! k %)}
         [dot/chat-dot r (and (not ro) (:unseen r) (not= k open))]
         [:span.rail-name title]
         (when multi? [:span.rail-server label])])]
     [:button.rail-forms.rail-sound {:class (when sounds? "on")
                                     :data-tip (if sounds? "Sounds on: click to mute" "Sounds off: click to hear when a chat finishes or waits")
                                     :aria-label "Sounds" :aria-pressed (boolean sounds?)
                                     :on-click #(alerts/set-sounds! (not sounds?))}
      [speaker sounds?]]
     [:button.rail-forms {:data-tip "Forms: everything Claude can draw, and your templates" :aria-label "Forms"
                          :on-click #(rf/dispatch [:gallery/open true])}
      forms-icon]
     [:f> usage/rail-meters]]))
