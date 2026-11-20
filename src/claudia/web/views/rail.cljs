(ns claudia.web.views.rail
  "The rail, the one chat list wider than a phone (README decision 24; web/app.tsx of the
  TypeScript Claudia): at its top Claudia's face, bare (a click: :claudia/open), and under it the 2D/3D switch as two small circles
  (claudia.web.views.logotools); then a circle per chat with its title under it; passive (ash)
  chats last, after a chalk line, with the same spacing. At its foot: your profile circle
  (claudia.web.views.menu). Circles
  slide to their new places when the order changes; a new one grows in. Every circle with its title
  takes the same height (a title is cut after two lines; one line keeps the room of two), so the
  spacing never changes. On a touch screen, pulling the circles down from the top and letting go
  reloads the page (claudia.web.updates keeps what is typed). A new chat has its circle at the top
  at once, before its first message (fresh-dot). On a touch tablet the circles sit centred at middle
  height, under the left thumb, and opening a chat scrolls its circle to the middle (README decision
  40). Its right edge drags it wider, and from 170 px on each circle becomes a card as tall as its
  row (claudia.web.views.rail-width, rail-card); with more than one server every circle carries its
  server's mark; a chat dragged on the rail moves in the order or into a group, and out of it tears
  into a window of its own (claudia.web.views.chat-drag; README decision 80)."
  (:require [claudia.web.search-open :as search-open]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [claudia.shared.order :as order]
            [reagent.hooks :as h]
            [claudia.web.face.view :as face]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.dot :as dot]
            [claudia.web.views.pure.pull :as pull]
            [claudia.web.views.pure.reach :as reach]
            [claudia.web.views.pure.sidebar :as sidebar-pure]
            [claudia.web.views.sidebar :as sidebar]
            [claudia.web.views.menu :as menu]
            [claudia.web.views.tearoff :as tearoff]
            [claudia.web.views.chat-drag :as drag]
            [claudia.web.views.rail-card :as rail-card]
            [claudia.web.views.rail-width :as rail-width]
            [claudia.web.views.update-pill :as update-pill]
            [claudia.web.views.logotools :as logotools]
            [claudia.web.views.rail-group :as rail-group]))

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

(defn- use-centre-open!
  "On a touch tablet, scrolls the rail (box) so the open chat's circle sits in its middle."
  [box open]
  (h/use-effect
   (fn []
     (when-let [^js el (.-current box)]
       (when-let [^js on (and (.-matches (js/matchMedia reach/touch-tablet)) (.querySelector el ".rail-chat.on"))]
         (let [r (.getBoundingClientRect on) b (.getBoundingClientRect el)]
           (.scrollBy el #js {:top (reach/centre-offset (.-top r) (.-height r) (.-top b) (.-clientHeight el))
                              :behavior "smooth"}))))
     js/undefined)
   [(str open)]))

(defn- pull-handlers
  "Touch listeners on the list el for pull to reload: start at the top, follow the finger, reload
  when let go far enough. set-d tells the view how far it is pulled."
  [^js el set-d]
  (let [start (atom nil) d (atom 0)
        move! (fn [n] (reset! d n) (set-d n))]
    {"touchstart" (fn [^js e] (reset! start (when (<= (.-scrollTop el) 0) (.. e -touches (item 0) -clientY))))
     "touchmove" (fn [^js e]
                   ;; A held chat being dragged is no pull (claudia.web.views.chat-drag).
                   (when (drag/dragging?) (reset! start nil) (move! 0))
                   (when-let [y0 @start]
                     (let [n (pull/distance (- (.. e -touches (item 0) -clientY) y0))]
                       (when (pos? n) (.preventDefault e))
                       (move! n))))
     "touchend" (fn [_]
                  (when (pull/ready? @d) (rf/dispatch [:version/reload]))
                  (reset! start nil) (move! 0))}))

(defn- use-pull!
  "How far the rail's list (in box) is pulled down now (px); listens while mounted."
  [box]
  (let [[d set-d] (h/use-state 0)]
    (h/use-effect
     (fn []
       (when-let [^js el (.-current box)]
         (let [hs (pull-handlers el set-d)]
           (doseq [[ev f] hs] (.addEventListener el ev f #js {:passive false}))
           #(doseq [[ev f] hs] (.removeEventListener el ev f)))))
     [])
    d))

(defn- pull-mark
  "The chalk arrow that shows over the circles while they are pulled; green once letting go reloads."
  [d]
  (when (pos? d)
    [:div.rail-pull {:class (when (pull/ready? d) "ready")
                     :style {:opacity (min 1 (/ d pull/ready-px)) :transform (str "rotate(" (* 3 d) "deg)")}}
     [:svg {:viewBox "0 0 24 24" :aria-hidden true :fill "none" :stroke "currentColor" :stroke-width 2 :stroke-linecap "round"}
      [:path {:d "M20 12a8 8 0 1 1-2.3-5.6M20 4v4h-4"}]]]))

(defn status
  "The line under a chat's name (README decision 46): running time and agents, a question waiting,
  done since, or last active; it ticks every second."
  [r done?]
  (let [now (hooks/use-now)
        [kind text] (sidebar-pure/status-line r done? now)]
    [:span.rail-status {:class kind} text]))

(def ^:private done-check
  "The big check drawn through a finished chat's circle, from its middle out."
  [:svg.rail-check {:viewBox "0 0 42 42" :aria-hidden true}
   [:path {:d "M11 22 L18 29 L32 13"}]])

(defn- chat-classes
  "A chat's classes on the rail: open, its server off, open in another tab, passive (and the chalk
  line before the passive ones only where they sit last: a search)."
  [{:keys [rows i open elsewhere ordered?]} {:keys [off] :as r}]
  (let [k [(:server r) (:id r)]
        steps-back? #(sidebar-pure/passive? % elsewhere open)
        passive? (steps-back? r)]
    [(when (= k open) "on") (when off "off") (when (contains? elsewhere k) "elsewhere")
     (when passive? "passive")
     (when (and passive? (not ordered?) (not (some-> (get rows (dec i)) steps-back?))) "first")
     (when (and passive? (not ordered?) (= i (dec (count rows)))) "last")]))

(defn- tip
  "The hover text of a circle: its title, project, server (with more than one) and whether another
  tab has it; none on cards, which say it all."
  [{:keys [multi? projects elsewhere cards?]} {:keys [server id title off folder]}]
  (when-not cards?
    (str title (some->> (:name (get projects [server folder])) (str " \u00b7 "))
         (when multi? (str " \u00b7 " @(rf/subscribe [:server-label server]) (when off " is off")))
         (when (contains? elsewhere [server id]) " \u00b7 open in another tab"))))

(defn- chat-body
  "Beside or under the circle: a card (the wide rail), else the title and the status line."
  [{:keys [cards? multi?]} r unseen?]
  (if cards?
    [:f> rail-card/chat-text r multi? unseen?]
    [:<> [:span.rail-name (:title r)] [:f> status r unseen?]]))

(defn- rail-chat
  "A chat's circle with its title, or its card: a click opens it, the context menu has its actions,
  a drag moves it in the order or into a group, and a mouse dragging it out of the rail tears it
  into a window of its own (claudia.web.views.chat-drag)."
  [{:keys [rows open multi? viewing? elsewhere] :as m} {:keys [server id title color] :as r}]
  (let [k [server id] cref (order/ref-of r)
        unseen? (and (not viewing?) (:unseen r) (not= k open))]
    [:button.rail-chat {:data-slide cref :data-ref cref :data-top true
                        :class (chat-classes m r)
                        :style {"--chat" (sidebar/chat-color-var color)}
                        :data-tip (tip m r) :data-tip-color (sidebar/chat-color-var color)
                        :aria-label title
                        :on-pointer-down #(drag/press! % cref (tearoff/tear-fn k open rows elsewhere))
                        :on-click #(sidebar/pick! k (= k open) %)
                        :on-context-menu #(chatmenu/open-at! k %)}
     [dot/chat-dot r unseen? true (when multi? [rail-card/server-badge server])]
     (when (sidebar-pure/done-mark? r unseen?) done-check)
     [chat-body m r unseen?]]))

(defn- fresh-dot
  "The chat being started, at the top at once (README decision 31): the open one, in its colour,
  until the list has it; it goes when another chat is opened before anything was sent."
  [{:keys [title color server] :as fresh} {:keys [cards? multi?]}]
  [:div.rail-chat.on.fresh {:style {"--chat" (sidebar/chat-color-var color)} :data-tip (when-not cards? title) :aria-label title}
   [dot/chat-dot fresh false true (when (and multi? server) [rail-card/server-badge server])]
   (if cards? [rail-card/fresh-text fresh multi?] [:span.rail-name title])])

(defn- rail-acts
  "Search and New chat, side by side (a mouse's way to both; the iPad's edge has its own)."
  []
  [:div.rail-acts
   [:button.rail-dot.search {:title "Search chats (\u2318K)" :aria-label "Search chats"
                             :on-click search-open/open!}
    [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:circle {:cx 9 :cy 9 :r 5}] [:path {:d "M16 16l-3.5-3.5"}]]]
   (when-not @(rf/subscribe [:new-chat-read-only?])
     [:button.rail-dot.search {:title "New chat (Alt+N)" :aria-label "New chat" :on-click #(rf/dispatch [:nav/new])}
      [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M10 4.5v11M4.5 10h11"}]]])])

(defn- rail-top
  "Search and New chat where the screen has no right-edge column (the iPad's edge has both), and
  the chat being started. A new chat also starts from Alt+N."
  [edge? m]
  (let [fresh @(rf/subscribe [:fresh-chat])]
    [:<>
     (when-not edge? [rail-acts])
     (when fresh [fresh-dot fresh m])]))

(defn- rail-head
  "Claudia's face, no frame round her so her animations show whole (a click opens the chat her
  lenses talk about, else her own chat), and the 2D/3D switch under it."
  []
  [:div.rail-head
   [:button.rail-face {:aria-label "Claudia" :data-tip "Claudia" :on-click face/click!}
    [:f> face/face]]
   [logotools/tools]])

(defn- entry-view [m i {:keys [kind row] :as e}]
  (if (= kind :group)
    ^{:key (str "group/" (:group (:group e)))} [rail-group/rail-group e m]
    ^{:key (str (:server row) "/" (:id row))} [rail-chat (assoc m :i i) row]))

(defn- rail-state
  "What every entry on the rail needs: the chats in order (a group's in it), the open one, the
  servers, whose chats, the projects, the tabs, and whether the rail shows cards."
  [entries]
  {:rows (vec (mapcat #(or (:rows %) [(:row %)]) entries)) :open @(rf/subscribe [:open])
   :multi? @(rf/subscribe [:multi-server?]) :viewing? @(rf/subscribe [:viewing-other?])
   :projects @(rf/subscribe [:projects]) :elsewhere @(rf/subscribe [:open-elsewhere]) :ordered? true
   :cards? (rail-width/cards?)})

(defn rail []
  (let [entries @(rf/subscribe [:arranged])
        {:keys [rows open cards?] :as m} (rail-state entries)
        box (h/use-ref nil)
        pulled (use-pull! box)
        edge? (hooks/use-media reach/touch-tablet)]
    (use-centre-open! box open)
    (sidebar/scroll-open-into-view! [(str open) (count rows)])
    (use-slide! box (str/join "," (map #(if-let [g (:group %)] (:group g) (order/ref-of (:row %))) entries)))
    ;; The rail is its chats' drag home: let go outside it, a chat tears out (claudia.web.views.chat-drag).
    [:nav.rail {:aria-label "Chats" :class (when cards? "cards") :data-drag-home true}
     [rail-head]
     [:div.rail-scroll
      (pull-mark pulled)
      [:div.rail-dots {:ref #(set! (.-current box) %)
                       :style (when (pos? pulled) {:transform (str "translateY(" pulled "px)")})}
       (rail-top edge? m)
       (map-indexed #(entry-view m %1 %2) entries)]]
     ;; The update pill, your profile circle (whose chats, the menu) and the version; on a touch
     ;; tablet they stand at the foot of the mic's rail instead (thumb_edge.cljs, README decision 73).
     (when-not edge?
       [:<> [update-pill/pill] [:f> menu/button] [update-pill/updated]])
     [rail-width/edge]]))
