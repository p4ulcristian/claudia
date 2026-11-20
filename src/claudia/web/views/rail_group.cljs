(ns claudia.web.views.rail-group
  "A group on the rail (README decisions 38 and 80): a stack of its chats' colours with the group's
  name and its most pressing state under it, or as a card beside it on the wide rail; pressed, a
  chooser card opens beside the rail with every chat of the group (its circle, its server's mark and
  its full title) and a + for a new chat in the group. A press outside or Escape closes the card;
  picking a chat opens it and closes the card. A chat dropped on the stack goes into the group; the
  chooser's chats are dragged like the rail's (claudia.web.views.chat-drag)."
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.shared.order :as order]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.chat-drag :as drag]
            [claudia.web.views.chat-groups :as groups]
            [claudia.web.views.dot :as dot]
            [claudia.web.views.pure.rail :as pure]
            [claudia.web.views.rail-card :as rail-card]
            [claudia.web.views.sidebar :as sidebar]
            [claudia.web.views.tearoff :as tearoff]))

;; The open card: {:gid :top} (top: the stack's top on the screen, px), or nil.
(defonce ^:private card (r/atom nil))

(defn- close! [] (reset! card nil))

(defn- stack
  "Up to three of the group's chats' colours, one behind the other."
  [rows]
  [:span.rail-stack
   (for [[i {:keys [color] :as rw}] (map-indexed vector (take 3 rows))]
     ^{:key (order/ref-of rw)}
     [:i {:style {"--chat" (sidebar/chat-color-var color) "--i" i}}])])

(defn- card-chat [gid {:keys [open viewing? multi? rows elsewhere]} {:keys [server id title color] :as rw}]
  (let [k [server id] cref (order/ref-of rw)]
    [:button.group-card-chat {:type "button" :class (when (= k open) "on")
                              :data-ref cref :data-in-group gid
                              :style {"--chat" (sidebar/chat-color-var color)}
                              :on-pointer-down #(drag/press! % cref (tearoff/tear-fn k open rows elsewhere))
                              :on-click #(do (sidebar/pick! k (= k open) %) (close!))}
     [dot/chat-dot rw (and (not viewing?) (:unseen rw) (not= k open)) false (when multi? [rail-card/server-badge server])]
     [:span.group-card-title title]]))

(defn- card-head
  "The card's head: the group's name (double-click renames; a new group asks at once) and its +."
  [gid group-name]
  [:div.group-card-head
   (if (= gid @(rf/subscribe [:renaming-group]))
     [groups/name-input gid group-name]
     [:span.group-card-name {:on-double-click #(rf/dispatch [:order/renaming gid])} group-name])
   [:button.group-add {:type "button" :title (str "New chat in " group-name) :aria-label (str "New chat in " group-name)
                       :on-click #(do (rf/dispatch [:order/new-in-group gid]) (close!))} "+"]])

(defn- use-place!
  "A card opened by its new group's naming, not by a press: it takes its place beside the stack in
  box (the group's wrap)."
  [box gid]
  (h/use-layout-effect
   (fn []
     (when-let [^js w (and (not= gid (:gid @card)) (.-current box))]
       (reset! card {:gid gid :top (max 8 (- (.-top (.getBoundingClientRect w)) 6))}))
     js/undefined)
   []))

(defn- card-view [{gid :group group-name :name} rows m]
  (let [box (h/use-ref nil)]
    (hooks/use-click-outside box true close!)
    (hooks/use-key #(when (= "Escape" (.-key %)) (close!)) false [])
    (use-place! box gid)
    ;; The stack and the card are one inside: pressing the stack again closes the card once.
    [:div.group-card {:ref #(set! (.-current box) (some-> % .-parentElement)) :role "dialog" :aria-label group-name
                      :style {:top (:top @card)}}
     [card-head gid group-name]
     (for [rw rows] ^{:key (order/ref-of rw)} [card-chat gid m rw])]))

(defn- open-card! [gid ^js e]
  (let [top (.-top (.getBoundingClientRect (.-currentTarget e)))]
    (if (= gid (:gid @card)) (close!) (reset! card {:gid gid :top (max 8 (- top 6))}))))

(defn- group-body
  "Beside or under the stack: the group's card (the wide rail), else its name and state."
  [group-name rows cards?]
  (if cards?
    [rail-card/group-text group-name rows]
    (let [[kind text] (pure/group-status rows)]
      [:<> [:span.rail-name group-name] [:span.rail-status {:class kind} text]])))

(defn rail-group
  "A group on the rail (entry: {:group :rows} from claudia.shared.order/arrange; m: open, viewing?,
  cards?). Its wrap stands at the rail's top level of the order, its stack takes drops into it."
  [{g :group rows :rows} {:keys [open cards?] :as m}]
  (let [gid (:group g)
        holds-open? (some #(= open [(:server %) (:id %)]) rows)]
    [:div.rail-group-wrap {:data-top true :data-key gid}
     [:button.rail-chat.rail-group {:type "button" :data-slide (str "group/" gid) :data-group gid
                                    :class [(when holds-open? "on") (when (= gid (:gid @card)) "chosen")]
                                    :data-tip (when-not cards? (str (:name g) " · " (count rows) " chats")) :aria-label (:name g)
                                    :aria-expanded (= gid (:gid @card))
                                    :on-click #(open-card! gid %)}
      [stack rows]
      [group-body (:name g) rows cards?]]
     ;; Open when pressed, and while a new group's name is typed (README decision 80).
     (when (or (= gid (:gid @card)) (= gid @(rf/subscribe [:renaming-group]))) [:f> card-view g rows m])]))
