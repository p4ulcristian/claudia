(ns claudia.web.views.shell
  "The layout, as the TypeScript Claudia laid it out (web/app.tsx). Desktop and iPad: the rail of
  chat circles (the only chat list there, README decision 24) | the chat column | a 6 px divider |
  the picture; Settings and the archived chats take the chat column, full height, with a Back to
  the chat. Phone (under 680 px): three full screens, list -> chat -> picture, one shown at a time;
  Settings slides over the list."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rdb]
            [reagent.hooks :as h]
            [claudia.web.admin :as admin]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.asks :as asks]
            [claudia.web.views.attach :as attach]
            [claudia.web.views.chat :as chat]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.confirm :as confirm]
            [claudia.web.views.rail :as rail]
            [claudia.web.views.picture :as picture]
            [claudia.web.views.pure.shell :as pure]
            [claudia.web.views.sidebar :as sidebar]
            [claudia.web.views.pure.reach :as reach]
            [claudia.web.views.thumb-edge :as thumb-edge]
            [claudia.web.views.claudia-rain :as claudia-rain]
            [claudia.web.world :as world]))

(defn- chat-colour!
  "The open chat's colour tints the whole window (claudia.css, \":root[data-chat]\"); Claudia's own
  chat wears her green gradient instead (\":root[data-claudia]\", README decision 50)."
  [color claudia?]
  (h/use-effect
   (fn []
     (let [root js/document.documentElement
           v (sidebar/chat-color-var color)]
       (if v
         (do (.setAttribute root "data-chat" color)
             (.setProperty (.-style root) "--hue" v))
         (do (.removeAttribute root "data-chat")
             (.removeProperty (.-style root) "--hue")))
       (if claudia? (.setAttribute root "data-claudia" "") (.removeAttribute root "data-claudia")))
     nil)
   [color claudia?]))

(defn- keys!
  "Escape stops the running turn, Alt+N starts a new chat, Ctrl+K (⌘K) opens Search in the right half."
  []
  (h/use-effect
   (fn []
     (let [k (fn [^js e]
               (cond
                 (and (= "Escape" (.-key e)) (not (.-defaultPrevented e))
                      (let [db @rdb/app-db] (get-in db [:chats (:open db) :running])))
                 (do (.preventDefault e) (rf/dispatch [:chat/stop]))

                 (and (.-altKey e) (= "KeyN" (.-code e)))
                 (do (.preventDefault e) (rf/dispatch [:nav/new]))

                 (and (or (.-ctrlKey e) (.-metaKey e)) (= "k" (.toLowerCase (.-key e))))
                 (do (.preventDefault e) (rf/dispatch [:right/view :search]))))]
       (js/window.addEventListener "keydown" k)
       #(js/window.removeEventListener "keydown" k)))
   []))

(def ^:private chat-width 400)

(defn- stored-width []
  (let [v (js/Number (try (js/localStorage.getItem "chatW") (catch :default _ nil)))]
    (if (pos? v) v chat-width)))

(defn- drag-split!
  "The divider: the chat column's width in px (not a share of the window, so maximizing gives the
  room to the picture), kept once dragged."
  [^js e set-w]
  ;; A finger on a touch screen would scroll instead: the drag takes the pointer.
  (.preventDefault e)
  (.setPointerCapture (.-currentTarget e) (.-pointerId e))
  (let [main (.getBoundingClientRect (.. e -currentTarget -parentElement))
        width (atom nil)
        move (fn [^js m]
               (let [w (pure/dragged-width (.-left main) (.-width main) (.-clientX m))]
                 (reset! width w)
                 (set-w w)))
        up (fn up []
             (js/window.removeEventListener "pointermove" move)
             (js/window.removeEventListener "pointerup" up)
             (when @width (try (js/localStorage.setItem "chatW" (str @width)) (catch :default _ nil))))]
    (js/window.addEventListener "pointermove" move)
    (js/window.addEventListener "pointerup" up)))

(defn- column-pane
  "The archived chats in the chat column, over the chat (which stays mounted, hidden)."
  [pane]
  [:div.col-pane
   (case pane
     :archived [:f> sidebar/archived-pane])])

(defn- split-view [{:keys [phone? folded? chat-w set-chat-w pane]}]
  [:main.split {:class [(when folded? "folded") (when pane "col-covered")]
                :style (when-not phone?
                         {:grid-template-columns (pure/split-columns folded? chat-w)})}
   [chat/chat-pane]
   (when pane [column-pane pane])
   [:div.divider {:on-pointer-down (when-not folded? #(drag-split! % set-chat-w))}]
   [picture/picture-pane]])

(defn- layers
  "Over the page: the menus, the drop zone."
  [{:keys [draft-key ro]}]
  [:<>
   [chatmenu/chat-menu]
   [chatmenu/context-menu]
   [:f> attach/drop-zone draft-key (not ro)]])

(defn- chats-view
  "The rail (wider than a phone) or the phone's list, and the chat with its picture: not drawn at
  all while the world covers them (the world shows its own chat), so opening a chat there renders
  it once."
  [{:keys [phone? shown?] :as m}]
  [:<>
   [:div.sidebar-slot
    (if phone?
      [:f> sidebar/sidebar {:shown? shown? :phone? phone?}]
      [:f> rail/rail])]
   (split-view m)])

(defn- app-view [{:keys [screen ro narrow? shown? draft-key world? claudia?] :as m}]
  [:div.app {:class [(when-not shown? "no-sidebar")
                     (when narrow? "narrow") (when ro "read-only")]
             :data-screen (name screen)}
   (when claudia? [:f> claudia-rain/rain])
   [asks/modal]
   [confirm/archive-ask]
   [confirm/root-ask]
   (when-not world? (chats-view m))
   (when (and (:edge? m) (not world?)) [thumb-edge/edge])
   (layers {:ro ro :draft-key draft-key})
   [admin/dialog]
   [:f> world/overlay]])

(defn app []
  (let [screen @(rf/subscribe [:screen])
        color @(rf/subscribe [:open-color])
        claudia? @(rf/subscribe [:open-claudia?])
        open @(rf/subscribe [:open])
        pending @(rf/subscribe [:pending-new])
        ro @(rf/subscribe [:read-only?])
        mode @(rf/subscribe [:list-mode])
        folded? (:folded? @(rf/subscribe [:fold-state]))
        world? @(rf/subscribe [:world?])
        phone? (hooks/use-media "(max-width: 679px)")
        narrow? (hooks/use-media "(max-width: 1099px)")
        edge? (hooks/use-media reach/touch-tablet)
        [chat-w set-chat-w] (h/use-state stored-width)
        ;; Phone: the list shows unless a chat (or its picture) does. Wider: never (the rail is the list).
        shown? (and phone? (= screen :list))]
    (chat-colour! color claudia?)
    (keys!)
    (app-view {:screen screen :ro ro :phone? phone? :narrow? narrow? :folded? folded? :world? world? :edge? edge?
               :claudia? claudia?
               :chat-w chat-w :set-chat-w set-chat-w :pane (pure/column-pane phone? mode)
               :shown? shown?
               :draft-key (if (and open (not (contains? pending (second open)))) open :new)})))
