(ns claudia.web.views.rail
  "The rail (desktop, the list closed; web/app.tsx of the TypeScript Claudia): under Claudia's face
  the sounds and 2D/3D switches (claudia.web.views.logotools), + for a new chat, then a circle per
  chat with its title under it; passive (ash) chats last, above a chalk line. At its foot: the
  settings gear (claudia.web.views.menu). Circles
  slide to their new places when the order changes; a new one grows in."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.dot :as dot]
            [claudia.web.views.pure.sidebar :as sidebar-pure]
            [claudia.web.views.sidebar :as sidebar]
            [claudia.web.views.menu :as menu]
            [claudia.web.views.tearoff :as tearoff]
            [claudia.web.views.logotools :as logotools]))

(defn- use-slide!
  "Each [data-slide] child of box is measured after every change of order and animated from where
  it was; a new one grows in."
  [box order]
  (let [seen (h/use-ref nil)]
    (h/use-layout-effect
     (fn []
       (if-let [^js el (.-current box)]
         (let [top (- (.-top (.getBoundingClientRect el)) (.-scrollTop el))
               items (array-seq (.querySelectorAll el "[data-slide]"))
               now (into {} (for [^js d items] [(.. d -dataset -slide) (- (.-top (.getBoundingClientRect d)) top)]))
               before (.-current seen)]
           (set! (.-current seen) now)
           (when (and before (not (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)"))))
             (doseq [^js d items
                     :let [k (.. d -dataset -slide) was (get before k) is (get now k)]]
               (cond
                 (nil? was) (.animate d #js [#js {:opacity 0 :transform "scale(0.4)"} #js {:opacity 1 :transform "none"}]
                                      #js {:duration 260 :easing "cubic-bezier(.2,.8,.3,1.2)"})
                 (> (js/Math.abs (- was is)) 1)
                 (.animate d #js [#js {:transform (str "translateY(" (- was is) "px)")} #js {:transform "none"}]
                           #js {:duration 420 :easing "cubic-bezier(.3,.7,.2,1)"})))))
         (set! (.-current seen) nil))
       nil)
     [order])))

(defn- rail-chat
  "A chat's circle with its title (its server in the hover text on the hub; the chat's header
  shows it under the title); dragged out of the window, the chat opens in a window of its own."
  [{:keys [rows i open multi? ro projects elsewhere]} {:keys [server id title color off folder] :as r}]
  (let [k [server id]
        steps-back? #(sidebar-pure/passive? % elsewhere open)
        passive? (steps-back? r)
        project (:name (get projects [server folder]))
        label @(rf/subscribe [:server-label server])
        elsewhere? (contains? elsewhere k)]
    [:button.rail-chat (merge (tearoff/drag-props k #(tearoff/move-on! k open rows elsewhere))
                              {:data-slide (str server "/" id)
                        :class [(when (= k open) "on") (when off "off") (when elsewhere? "elsewhere")
                                (when passive? "passive")
                                (when (and passive? (not (some-> (get rows (dec i)) steps-back?))) "first")
                                (when (and passive? (= i (dec (count rows)))) "last")]
                        :style {"--chat" (sidebar/chat-color-var color)}
                        :data-tip (str title (when project (str " · " project))
                                       (when multi? (str " · " label (when off " is off")))
                                       (when elsewhere? " · open in another tab"))
                        :data-tip-color (sidebar/chat-color-var color)
                        :aria-label title
                        :on-click #(sidebar/pick! k (= k open) %)
                        :on-context-menu #(chatmenu/open-at! k %)})
     [dot/chat-dot r (and (not ro) (:unseen r) (not= k open))]
     [:span.rail-name title]]))

(defn- rail-top [ro]
  [:<>
   (when-not ro
     [:button.rail-dot.add {:title "New chat (Alt+N)" :on-click #(rf/dispatch [:nav/new])} "+"])])

(defn rail []
  (let [{:keys [text]} @(rf/subscribe [:search])
        open @(rf/subscribe [:open])
        elsewhere @(rf/subscribe [:open-elsewhere])
        rows (sidebar-pure/passive-last (sidebar-pure/matching @(rf/subscribe [:rows]) (sidebar-pure/search-key text))
                                        elsewhere open)
        ro @(rf/subscribe [:read-only?])
        m {:rows rows :open open :multi? @(rf/subscribe [:multi-server?]) :ro ro
           :projects @(rf/subscribe [:projects]) :elsewhere elsewhere}
        box (h/use-ref nil)]
    (use-slide! box (str/join "," (map #(str (:server %) "/" (:id %)) rows)))
    [:nav.rail {:aria-label "Chats"}
     [logotools/tools]
     [:div.rail-dots {:ref #(set! (.-current box) %)}
      (rail-top ro)
      (for [[i r] (map-indexed vector rows)]
        ^{:key (str (:server r) "/" (:id r))}
        [rail-chat (assoc m :i i) r])]
     [:f> menu/button]]))
