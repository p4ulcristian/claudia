(ns claudia.web.views.folder-contents
  "The Folders modal's middle pane (README decision 68): what the folder selected in the tree holds,
  as a grid (images as their own thumbnails, other files as their tag) or a list (name, size,
  modified). A click picks an item (the preview pane shows it); a click on the empty space picks
  none (the preview shows the folder); a second click on the same folder within
  pure/double-click-ms, or Enter, opens it in the tree. The two clicks are timed here, not by the
  browser's dblclick: the first click redraws the panes, and a touch screen sends no dblclick.
  Grid or list stays as the person left it, in the page's storage."
  (:require [reagent.hooks :as h]
            [claudia.web.folders :as folders]
            [claudia.web.format :as format]
            [claudia.web.views.files :as files]
            [claudia.web.views.pure.folders :as pure]))

(def ^:private mode-key "claudia.folders.view")

(defn- stored-mode [] (pure/view-mode (try (js/localStorage.getItem mode-key) (catch :default _ nil))))

(defn- use-mode
  "[mode set-mode]: :grid or :list, kept in the page's storage."
  []
  (let [[mode set-mode] (h/use-state stored-mode)]
    [mode (fn [m] (try (js/localStorage.setItem mode-key (name m)) (catch :default _ nil)) (set-mode m))]))

(def ^:private grid-icon
  [:svg.fd-mode-icon {:viewBox "0 0 16 16" :aria-hidden true}
   [:rect {:x 2 :y 2 :width 5 :height 5 :rx 1}] [:rect {:x 9 :y 2 :width 5 :height 5 :rx 1}]
   [:rect {:x 2 :y 9 :width 5 :height 5 :rx 1}] [:rect {:x 9 :y 9 :width 5 :height 5 :rx 1}]])

(def ^:private list-icon
  [:svg.fd-mode-icon {:viewBox "0 0 16 16" :aria-hidden true}
   [:path {:d "M2 4h12M2 8h12M2 12h12"}]])

(defn- mode-switch [mode set-mode]
  [:div.fd-modes {:role "group" :aria-label "View"}
   [:button {:type "button" :class (when (= mode :grid) "on") :aria-pressed (= mode :grid) :title "Grid"
             :on-click #(set-mode :grid)} grid-icon]
   [:button {:type "button" :class (when (= mode :list) "on") :aria-pressed (= mode :list) :title "List"
             :on-click #(set-mode :list)} list-icon]])

(defn- clicked!
  "A click on item: opens a folder clicked a second time in a row (pure/opens?), else picks item.
  last-click: a ref of the last click {:path :at}."
  [item {:keys [pick! open! last-click]}]
  (let [click {:path (:path item) :at (js/Date.now)}]
    (if (pure/opens? item (.-current last-click) click)
      (do (set! (.-current last-click) nil) (open! (:path item)))
      (do (set! (.-current last-click) click) (pick! (:path item))))))

(defn- item-props
  "What every item does, in the grid and the list."
  [item {:keys [picked open!] :as ctx}]
  {:type "button" :class (when (= picked (:path item)) "on") :title (pure/link-title (:name item) (:link item))
   :on-click (fn [e] (.stopPropagation e) (clicked! item ctx))
   :on-key-down #(when (and (:dir item) (= "Enter" (.-key %))) (.preventDefault %) (open! (:path item)))})

(defn- face [server item]
  (cond (:dir item) [:span.fd-face.dir pure/folder-icon]
        (pure/thumb? item) [:img.fd-thumb {:src (folders/file-url server (:path item)) :alt "" :loading "lazy" :decoding "async"}]
        :else [:span.fd-face [files/ext-tag (:name item)]]))

(defn- link-badge "The symlink mark on an item, nil for others." [item]
  (when (some? (:link item)) [:span.fd-link-badge pure/link-mark]))

(defn- tile [server item ctx]
  [:button.fd-gi (item-props item ctx)
   [:span.fd-gi-face [face server item] [link-badge item]]
   [:span.fd-gi-name (:name item)]])

(defn- row [item ctx]
  [:button.fd-li (item-props item ctx)
   [:span.fd-li-face (if (:dir item) pure/folder-icon [files/ext-tag (:name item)]) [link-badge item]]
   [:span.fd-li-name (:name item)]
   [:span.fd-li-size (when-not (:dir item) (some-> (:size item) format/bytes-text))]
   [:span.fd-li-at (format/when-text (:mtime item))]])

(defn- items-view [{:keys [server items error pick!] :as ctx} mode]
  (cond error [:p.fd-warn.fd-pad error]
        (nil? items) [:p.muted.fd-pad "…"]
        (empty? items) [:p.muted.fd-pad "This folder is empty."]
        (= mode :grid) [:div.fd-grid {:on-click #(pick! nil)}
                        (for [it items] ^{:key (:path it)} [tile server it ctx])]
        :else [:div.fd-list {:on-click #(pick! nil)}
               (for [it items] ^{:key (:path it)} [row it ctx])]))

(defn pane
  "The middle pane. opts: :server, :folder, :items (shown, nil while loading), :more, :error,
  :picked (the picked item's path), :pick! (a path or nil), :open! (a folder's path)."
  [{:keys [folder items more] :as opts}]
  (let [[mode set-mode] (use-mode)
        last-click (h/use-ref nil)
        ctx (assoc opts :last-click last-click)]
    [:section.fd-mid
     [:header.fd-mid-bar
      [:span.fd-mid-name (pure/base-name folder)]
      [:span.fd-mid-count (when items (pure/count-text items more))]
      [mode-switch mode set-mode]]
     [:div.fd-mid-body (items-view ctx mode)]]))
