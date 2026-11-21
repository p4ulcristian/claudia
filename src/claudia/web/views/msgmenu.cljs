(ns claudia.web.views.msgmenu
  "The menu on one of the person's own messages (README decision 96): a right click, or a long
  press on a touch screen, opens it at the pointer; Fork from here makes a new chat with this one up
  to the end of that turn, in the same folder. Only on a message a fork may start from
  (claudia.web.views.pure.chat/user-marks). The look is the chat menu's (claudia.web.views.chatmenu)."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.pure.menu :as pure]))

(def ^:private press-ms
  "How long a finger rests on a message before its menu opens (iOS sends no contextmenu)."
  450)

;; nil, or {:timer :x :y}: a finger resting on a message, its menu still to open.
(defonce ^:private press (atom nil))

(defn- cancel! []
  (some-> @press :timer js/clearTimeout)
  (reset! press nil))

(defn- open! [ck id x y]
  (cancel!)
  (rf/dispatch [:ops/msg-menu {:k ck :id id :x x :y y}]))

(defn- start!
  "A finger came down on the message: its menu opens if it rests there press-ms."
  [ck id ^js e]
  (cancel!)
  (let [x (.-clientX e) y (.-clientY e)]
    (reset! press {:x x :y y :timer (js/setTimeout #(open! ck id x y) press-ms)})))

(defn- moved! [^js e]
  (when-let [{:keys [x y]} @press]
    (when (pure/moved-far? [x y] [(.-clientX e) (.-clientY e)]) (cancel!))))

(defn handlers
  "What message id of chat ck carries for its menu: a right click opens it; a finger resting on it
  opens it too, a move of more than 10 px or lifting it first cancels."
  [ck id]
  {:on-context-menu (fn [^js e] (.preventDefault e) (open! ck id (.-clientX e) (.-clientY e)))
   :on-pointer-down (fn [^js e] (when (= "touch" (.-pointerType e)) (start! ck id e)))
   :on-pointer-move moved!
   :on-pointer-up cancel!
   :on-pointer-cancel cancel!})

(defn- menu-box [{:keys [k id x y]}]
  (let [{:keys [forking? error]} (pure/fork-state @(rf/subscribe [:fork]) k)
        box (h/use-ref nil)
        [pos set-pos] (h/use-state {:left x :top y})
        close #(rf/dispatch [:ops/msg-menu nil])]
    (chatmenu/use-in-view! box x y set-pos [x y error])
    (chatmenu/use-dismiss! (chatmenu/inside-of box) close)
    [:div.chat-menu.msg-menu {:ref #(set! (.-current box) %) :style pos :role "menu"
                              :on-context-menu #(.preventDefault %)}
     [:button.cm-item {:role "menuitem" :disabled forking?
                       :title "A new chat with this one up to the end of this turn, in the same folder"
                       :on-click #(rf/dispatch [:ops/fork k id])}
      (if forking? "Forking…" "Fork from here")]
     (when error [:div.cm-error error])]))

(defn msg-menu
  "The open message menu, if any (never in a read-only view)."
  []
  (let [m @(rf/subscribe [:msg-menu])]
    (when (and m (not @(rf/subscribe [:read-only?])))
      ^{:key (str (:id m) (:x m) (:y m))} [:f> menu-box m])))
