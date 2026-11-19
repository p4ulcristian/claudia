(ns claudia.web.views.folder-tree
  "The Folders modal's tree (README decision 62): react-arborist from Reagent, rooted at \"/\", the
  work folder's path open at first. A folder's sub-folders load when it is first opened; dot
  folders show only with \"show hidden\". Only the rows are ours (chalk look); arborist does the
  rest (keys, scrolling only the rows on screen, the filter). Each row pins with one click (☆/★ at
  its right end). No dragging, no renaming."
  (:require ["react-arborist" :refer [Tree]]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.pure.folders :as pure]))

(defn- caret [^js node]
  [:span.fd-caret {:class (when (.-isOpen node) "open")
                   :on-click (fn [e] (.stopPropagation e) (.toggle node))}
   [:svg {:viewBox "0 0 12 12" :aria-hidden true} [:path {:d "M4 2.5l4 3.5-4 3.5"}]]])

(defn- pin-toggle
  "The row's ☆/★ at its right end: one click pins or unpins the folder."
  [pinned? on-pin]
  [:button.fd-pin {:type "button" :class (when pinned? "on") :aria-label (if pinned? "Unpin" "Pin")
                   :title (if pinned? "Unpin this folder" "Pin this folder")
                   :on-click (fn [e] (.stopPropagation e) (on-pin))}
   (if pinned? "★" "☆")])

(defn- node-row
  "One folder's row (a React function component: react-arborist calls it with node and style)."
  [^js props]
  (let [^js node (.-node props)
        ^js d (.-data node)
        bare? (and (.-loaded d) (zero? (.-length (.-children d))))]
    (r/as-element
     [:div.fd-node {:style (js->clj (.-style props) :keywordize-keys true)
                    :class [(when (.-isSelected node) "on") (when bare? "bare")]
                    :title (pure/link-title (.-id d) (.-link d))}
      [caret node]
      pure/folder-icon
      (when (some? (.-link d)) pure/link-mark)
      [:span.fd-name (.-name d)]
      [pin-toggle (.-pinned d) #(.. props -tree -props (onPin (.-id d)))]])))

(defn- reveal!
  "Opens every folder down to p in the tree (api: react-arborist's tree), selects p and scrolls to it,
  once its node is there; true when done."
  [^js api p]
  (when (and api (.get api p))
    (doseq [up (butlast (pure/lineage p))] (.open api up))
    (.select api p)
    (.scrollTo api p)
    true))

(defn- use-reveal!
  "Reveals the path reveal once the tree has its node, then calls done!."
  [api-ref reveal loaded done!]
  (h/use-effect
   (fn [] (when (and reveal (reveal! (.-current api-ref) reveal)) (done!)) js/undefined)
   [reveal loaded]))

(defn tree
  "The tree of loaded folders (path -> entries). opts: :cwd, :hidden?, :pinned (bookmarked folders,
  a set), :term (the filter field's text), :selected, :on-open (a folder opened: load it),
  :on-select, :on-pin (a row's ☆/★), :reveal (a path to open and select), :revealed!."
  [{:keys [loaded cwd hidden? pinned term selected on-open on-select on-pin reveal revealed!]}]
  (let [box (h/use-ref nil)
        api-ref (h/use-ref nil)
        [height set-height] (h/use-state 300)
        data (h/use-memo #(clj->js (pure/tree-data {:loaded loaded :hidden? hidden? :pinned pinned})) [loaded hidden? pinned])]
    (hooks/use-resize box #(set-height (max 120 (.-clientHeight ^js %))) [])
    (use-reveal! api-ref reveal loaded revealed!)
    [:div.fd-tree {:ref #(set! (.-current box) %)}
     [:> Tree {:data data :ref #(set! (.-current api-ref) %) :initialOpenState (clj->js (pure/open-map cwd))
               :selection selected :width "100%" :height height :rowHeight 28 :indent 16
               :disableDrag true :disableDrop true :disableEdit true :disableMultiSelection true
               :openByDefault false :aria-label "Folders"
               :searchTerm (or term "") :searchMatch (fn [^js n t] (pure/name-matches? (.. n -data -name) t))
               :onToggle on-open :onPin on-pin
               :onSelect (fn [nodes] (when-let [^js n (first nodes)] (on-select (.-id n))))
               :children node-row}]]))
