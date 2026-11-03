(ns claudia.web.views.shell
  "The layout, as the TypeScript Claudia laid it out (web/app.tsx). Desktop: the rail of chat circles
  | the chat column | a 6 px divider | the picture | the forms rail; the full chat list (and Settings
  in its place) opens over the rail as a drawer. Phone (under 680 px): three full screens, list ->
  chat -> picture, one shown at a time."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rdb]
            [reagent.hooks :as h]
            [claudia.web.admin :as admin]
            [claudia.web.views.asks :as asks]
            [claudia.web.views.attach :as attach]
            [claudia.web.views.chat :as chat]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.codeforms :as codeforms]
            [claudia.web.views.rail :as rail]
            [claudia.web.views.settings :as settings]
            [claudia.web.views.picture :as picture]
            [claudia.web.views.sidebar :as sidebar]))

(defn- chat-colour!
  "The open chat's colour tints the whole window (claudia.css, \":root[data-chat]\")."
  [color]
  (h/use-effect
   (fn []
     (let [root js/document.documentElement
           v (sidebar/chat-color-var color)]
       (if v
         (do (.setAttribute root "data-chat" color)
             (.setProperty (.-style root) "--hue" v))
         (do (.removeAttribute root "data-chat")
             (.removeProperty (.-style root) "--hue"))))
     nil)
   [color]))

(defn use-media
  "Whether the media query matches, kept up to date."
  [q]
  (let [[on set-on] (h/use-state #(.-matches (js/matchMedia q)))]
    (h/use-effect
     (fn []
       (let [m (js/matchMedia q)
             f #(set-on (.-matches m))]
         (.addEventListener m "change" f)
         #(.removeEventListener m "change" f)))
     [q])
    on))

(defn- keys!
  "Escape stops the running turn, Alt+N starts a new chat, Ctrl+B (⌘B) opens or closes the list."
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

                 (and (or (.-ctrlKey e) (.-metaKey e)) (= "b" (.toLowerCase (.-key e))))
                 (do (.preventDefault e) (rf/dispatch [:sidebar/toggle]))))]
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
  (let [main (.getBoundingClientRect (.. e -currentTarget -parentElement))
        width (atom nil)
        move (fn [^js m]
               (let [w (js/Math.round (min (- (.-width main) 6 280) (max 300 (- (.-clientX m) (.-left main)))))]
                 (reset! width w)
                 (set-w w)))
        up (fn up []
             (js/window.removeEventListener "pointermove" move)
             (js/window.removeEventListener "pointerup" up)
             (when @width (try (js/localStorage.setItem "chatW" (str @width)) (catch :default _ nil))))]
    (js/window.addEventListener "pointermove" move)
    (js/window.addEventListener "pointerup" up)))

(rf/reg-event-db :gallery/open (fn [db [_ v]] (assoc db :gallery? v)))
(rf/reg-sub :gallery? (fn [db] (:gallery? db)))

(defn- gallery
  "The Forms window (the rail's and Settings' Forms): every form on sample data, the templates."
  []
  (when @(rf/subscribe [:gallery?])
    (let [server (or (:server @(rf/subscribe [:open-chat])) @(rf/subscribe [:new-server]))]
      (when server [:f> codeforms/gallery server #(rf/dispatch [:gallery/open false])]))))

(defn app []
  (let [screen @(rf/subscribe [:screen])
        drawer? @(rf/subscribe [:sidebar?])
        color @(rf/subscribe [:open-color])
        open @(rf/subscribe [:open])
        pending @(rf/subscribe [:pending-new])
        ro @(rf/subscribe [:read-only?])
        {:keys [settings?]} @(rf/subscribe [:list-mode])
        draft-key (if (and open (not (contains? pending (second open)))) open :new)
        folded? (:folded? @(rf/subscribe [:fold-state]))
        phone? (use-media "(max-width: 679px)")
        narrow? (use-media "(max-width: 1099px)")
        [chat-w set-chat-w] (h/use-state stored-width)
        ;; Phone: the list shows unless a chat (or its picture) does. Desktop: the drawer.
        shown? (if phone? (= screen :list) drawer?)
        settings-shown? (and shown? settings?)]
    (chat-colour! color)
    (keys!)
    [:div.app {:class [(when-not shown? "no-sidebar") (when settings-shown? "settings-shown")
                       (when narrow? "narrow") (when ro "read-only")]
               :data-screen (name screen)}
     [asks/modal]
     [:button.icon.menu-toggle {:title (if shown? "Hide chats (Ctrl+B)" "Show chats (Ctrl+B)")
                                :aria-expanded shown?
                                :on-click #(rf/dispatch [:sidebar/toggle])}
      [:svg.burger {:viewBox "0 0 18 18" :aria-hidden true} [:path {:d "M3 4.6h12M3.4 9h11.3M3 13.4h12"}]]]
     [:div.sidebar-slot
      (when (and (not shown?) (not phone?)) [:f> rail/rail])
      [:f> sidebar/sidebar {:shown? shown? :settings? settings-shown? :phone? phone?}]]
     (when (and shown? (not phone?))
       [:div.backdrop {:on-click #(rf/dispatch [:sidebar/toggle false])}])
     [chatmenu/chat-menu]
     [chatmenu/context-menu]
     (when-not settings-shown? [settings/github-prompt])
     [:f> attach/drop-zone draft-key (not ro)]
     [:main.split {:class (when folded? "folded")
                   :style (when-not phone?
                            {:grid-template-columns
                             (if folded?
                               "minmax(300px, 1fr) 6px 16px"
                               (str "max(300px, min(" chat-w "px, calc(100% - 286px))) 6px minmax(280px, 1fr)"))})}
      [chat/chat-pane]
      [:div.divider {:on-pointer-down (when-not folded? #(drag-split! % set-chat-w))}]
      [picture/picture-pane]]
     ;; The right rail, mirroring the chats rail: this chat's forms (claudia.web.views.picture/right-rail).
     (when-not phone? [picture/right-rail])
     [gallery]
     [admin/dialog]]))
