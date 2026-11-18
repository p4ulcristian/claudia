(ns claudia.web.views.folder-tree
  "The Folders screen's tree (README decision 62): react-arborist from Reagent, rooted at \"/\", the
  work folder's path open at first. A folder's sub-folders load when it is first opened; dot
  folders show only with \"show hidden\". Only the rows are ours (chalk look); arborist does the
  rest (keys, scrolling only the rows on screen). No dragging, no renaming."
  (:require ["react-arborist" :refer [Tree]]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.pure.folders :as pure]))

(defn- caret [^js node]
  [:span.fd-caret {:class (when (.-isOpen node) "open")
                   :on-click (fn [e] (.stopPropagation e) (.toggle node))}
   [:svg {:viewBox "0 0 12 12" :aria-hidden true} [:path {:d "M4 2.5l4 3.5-4 3.5"}]]])

(def folder-icon
  [:svg.fd-icon {:viewBox "0 0 16 16" :aria-hidden true}
   [:path {:d "M1.5 4a1 1 0 0 1 1-1h3.6l1.4 1.6h6a1 1 0 0 1 1 1V12a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1z"}]])

(defn- node-row
  "One folder's row (a React function component: react-arborist calls it with node and style)."
  [^js props]
  (let [^js node (.-node props)
        ^js d (.-data node)
        bare? (and (.-loaded d) (zero? (.-length (.-children d))))]
    (r/as-element
     [:div.fd-node {:style (js->clj (.-style props) :keywordize-keys true)
                    :class [(when (.-isSelected node) "on") (when bare? "bare")]
                    :title (.-id d)}
      [caret node]
      folder-icon
      [:span.fd-name (.-name d)]])))

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
  "The tree of loaded folders (path -> entries). opts: :cwd, :hidden?, :selected, :on-open (a folder
  opened: load it), :on-select, :reveal (a path to open and select), :revealed!."
  [{:keys [loaded cwd hidden? selected on-open on-select reveal revealed!]}]
  (let [box (h/use-ref nil)
        api-ref (h/use-ref nil)
        [height set-height] (h/use-state 300)
        data (h/use-memo #(clj->js (pure/tree-data loaded hidden?)) [loaded hidden?])]
    (hooks/use-resize box #(set-height (max 120 (.-clientHeight ^js %))) [])
    (use-reveal! api-ref reveal loaded revealed!)
    [:div.fd-tree {:ref #(set! (.-current box) %)}
     [:> Tree {:data data :ref #(set! (.-current api-ref) %) :initialOpenState (clj->js (pure/open-map cwd))
               :selection selected :width "100%" :height height :rowHeight 28 :indent 16
               :disableDrag true :disableDrop true :disableEdit true :disableMultiSelection true
               :openByDefault false :aria-label "Folders"
               :onToggle on-open
               :onSelect (fn [nodes] (when-let [^js n (first nodes)] (on-select (.-id n))))
               :children node-row}]]))
