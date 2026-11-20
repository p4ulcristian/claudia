(ns claudia.web.views.viewer
  "Photos and videos big over the whole window (README decision 81), the one viewer for every
  preview: a message's photos, a photo or video Claude showed, the composer's photos, the files
  form's and the Folders preview's images, the 3D scene's video. Dark backdrop, the item centred
  and fitted inside the safe areas; a bar on top with the item's title, its place (2 / 5), Save
  (claudia.web.views.media-save) and a large ×. A photo zooms and pans (claudia.web.views.zoom);
  several are swiped through, or stepped with the arrows (on screen and the keys). A video plays
  inline with the player's own controls (no zoom: it would fight them). Closes on ×, Esc, a tap
  beside the item, a photo dragged down, and back (claudia.web.back: back never leaves the chat).
  Put in the page's body (or the element in full screen), so no container clips it."
  (:require ["react-dom" :as react-dom]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.back :as back]
            [claudia.web.views.media-save :as media-save]
            [claudia.web.views.pure.viewer :as pv]
            [claudia.web.views.zoom :as zoom]))

(defn- video-stage
  "The video, centred, playing inline; a tap beside it closes."
  [{:keys [src]} close]
  [:div.viewer-touch {:on-click #(when-not (= "VIDEO" (.. % -target -tagName)) (close))}
   [:div.viewer-stage
    [:video.viewer-media {:src src :controls true :auto-play true :plays-inline true :preload "auto"}]]])

(defn- bar [items i close]
  (let [{:keys [title note save] :as item} (nth items i)
        n (count items)]
    [:div.viewer-bar
     [:div.viewer-head
      (when title [:span.viewer-title title])
      (when (or note (> n 1))
        [:span.viewer-sub (str (when (> n 1) (str (inc i) " / " n)) (when (and note (> n 1)) " · ") note)])]
     (when save ^{:key (:src item)} [:f> media-save/save-button item])
     [:button.viewer-btn.viewer-close {:type "button" :title "Close (Esc)" :aria-label "Close" :on-click close} "×"]]))

(defn- arrows [i n go]
  [:<>
   (when (pv/step i n -1)
     [:button.viewer-btn.viewer-nav.viewer-prev {:type "button" :aria-label "Previous" :on-click #(go -1)} "‹"])
   (when (pv/step i n 1)
     [:button.viewer-btn.viewer-nav.viewer-next {:type "button" :aria-label "Next" :on-click #(go 1)} "›"])])

(defn- use-keys!
  "Esc closes, the left and right arrows step (ahead of the page's own keys)."
  [go close]
  (let [fs (h/use-ref nil)]
    (set! (.-current fs) {:go go :close close})
    (h/use-effect
     (fn []
       (let [k (fn [^js e]
                 (when-let [f (case (.-key e) "Escape" #((:close (.-current fs)))
                                    "ArrowLeft" #((:go (.-current fs)) -1) "ArrowRight" #((:go (.-current fs)) 1) nil)]
                   (.stopImmediatePropagation e) (.preventDefault e) (f)))]
         (js/window.addEventListener "keydown" k true)
         #(js/window.removeEventListener "keydown" k true)))
     [])))

(defn- use-back!
  "A history entry while open (back closes it); returns the close the viewer's own ways call."
  [on-close]
  (let [id (h/use-ref nil) latest (h/use-ref on-close)]
    (set! (.-current latest) on-close)
    (h/use-effect (fn []
                    (set! (.-current id) (back/open! #((.-current latest))))
                    #(back/drop! (.-current id)))
                  [])
    #(back/close! (.-current id))))

(defn- stage [items i close go]
  (let [{:keys [kind] :as item} (nth items i)
        n (count items)]
    (if (= :video kind)
      ^{:key (str i)} [video-stage item close]
      ^{:key (str i)} [:f> zoom/photo item
                       {:n n :on-backdrop close
                        :on-swipe #(case % :close (do (close) true)
                                         :next (go 1) :prev (go -1) nil)}])))

(defn viewer
  "items [{:src :kind (:image | :video) :alt? :title? :note? :save? :name? (the saved file's name;
  its extension follows the file's type)}] big, item at first; on-close called when it closes."
  [{:keys [items at on-close]}]
  (let [[i set-i] (h/use-state (or at 0))
        n (count items)
        i (min i (dec n))
        close (use-back! on-close)
        go (fn [dir] (when-let [j (pv/step i n dir)] (set-i j) true))]
    (use-keys! go close)
    (react-dom/createPortal
     (r/as-element
      [:div.viewer {:role "dialog" :aria-modal true :aria-label "Viewer" :data-no-swipe true}
       [stage items i close go]
       [arrows i n go]
       [bar items i close]])
     (or js/document.fullscreenElement js/document.body))))
