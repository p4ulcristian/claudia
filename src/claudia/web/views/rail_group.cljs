(ns claudia.web.views.rail-group
  "A group on the rail (README decision 38): a stack of its chats' colours with the group's name
  under it; pressed, a chooser card opens beside the rail with every chat of the group (its circle
  and full title) and a + for a new chat in the group. A press outside or Escape closes the card;
  picking a chat opens it and closes the card."
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.shared.order :as order]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.dot :as dot]
            [claudia.web.views.sidebar :as sidebar]))

;; The open card: {:gid :top} (top: the stack's top on the screen, px), or nil.
(defonce ^:private card (r/atom nil))

(defn- close! [] (reset! card nil))

(defn- stack
  "Up to three of the group's chats' colours, one behind the other."
  [rows]
  [:span.rail-stack
   (for [[i {:keys [color] :as rw}] (map-indexed vector (take 3 rows))]
     ^{:key (order/ref-of rw)}
     [:i {:style (assoc (sidebar/chat-colour-style color) "--i" i)}])])

(defn- card-chat [{:keys [open viewing?]} {:keys [server id title color] :as rw}]
  (let [k [server id]]
    [:button.group-card-chat {:type "button" :class (when (= k open) "on")
                              :style (sidebar/chat-colour-style color)
                              :on-click #(do (sidebar/pick! k (= k open) %) (close!))}
     [dot/chat-dot rw (and (not viewing?) (:unseen rw) (not= k open))]
     [:span.group-card-title title]]))

(defn- card-view [{gid :group group-name :name} rows m]
  (let [box (h/use-ref nil)]
    (hooks/use-click-outside box true close!)
    (hooks/use-key #(when (= "Escape" (.-key %)) (close!)) false [])
    ;; The stack and the card are one inside: pressing the stack again closes the card once.
    [:div.group-card {:ref #(set! (.-current box) (some-> % .-parentElement)) :role "dialog" :aria-label group-name
                      :style {:top (:top @card)}}
     [:div.group-card-head
      [:span.group-card-name group-name]
      [:button.group-add {:type "button" :title (str "New chat in " group-name) :aria-label (str "New chat in " group-name)
                          :on-click #(do (rf/dispatch [:order/new-in-group gid]) (close!))} "+"]]
     (for [rw rows] ^{:key (order/ref-of rw)} [card-chat m rw])]))

(defn- open-card! [gid ^js e]
  (let [top (.-top (.getBoundingClientRect (.-currentTarget e)))]
    (if (= gid (:gid @card)) (close!) (reset! card {:gid gid :top (max 8 (- top 6))}))))

(defn rail-group
  "A group on the rail (entry: {:group :rows} from claudia.shared.order/arrange; m: open, viewing?)."
  [{g :group rows :rows} {:keys [open] :as m}]
  (let [gid (:group g)
        holds-open? (some #(= open [(:server %) (:id %)]) rows)]
    [:div.rail-group-wrap
     [:button.rail-chat.rail-group {:type "button" :data-slide (str "group/" gid)
                                    :class [(when holds-open? "on") (when (= gid (:gid @card)) "chosen")]
                                    :data-tip (str (:name g) " · " (count rows) " chats") :aria-label (:name g)
                                    :aria-expanded (= gid (:gid @card))
                                    :on-click #(open-card! gid %)}
      [stack rows]
      [:span.rail-name (:name g)]]
     (when (= gid (:gid @card)) [:f> card-view g rows m])]))
