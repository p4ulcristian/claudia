(ns claudia.web.views.chatmenu
  "Right click on a chat (a rail circle, a list row) or its header's ⋯: colour (16 colours, or
  passive ash, which remembers the colour it had), rename, fork, move to another project, archive."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.views.pure.menu :as pure]
            [claudia.web.views.pure.words :as words]))

(def colours words/chat-colours)

(defn open-at!
  "The menu for chat k at the mouse (or under an element)."
  [k ^js e]
  (.preventDefault e)
  (.stopPropagation e)
  (rf/dispatch [:ops/menu {:k k :x (.-clientX e) :y (.-clientY e)}]))

(defn use-dismiss!
  "While mounted, a pointer down where inside? says no, Escape, or the window losing focus calls
  close."
  [inside? close]
  (h/use-effect
   (fn []
     (let [away (fn [e] (when-not (inside? (.-target e)) (close)))
           esc (fn [e] (when (= "Escape" (.-key e)) (close)))]
       (js/window.addEventListener "pointerdown" away true)
       (js/window.addEventListener "keydown" esc)
       (js/window.addEventListener "blur" close)
       #(do (js/window.removeEventListener "pointerdown" away true)
            (js/window.removeEventListener "keydown" esc)
            (js/window.removeEventListener "blur" close))))
   []))

(defn- use-in-view!
  "The menu in box, opened at x y, kept inside the page's visible layer (the body; above a phone's
  keyboard while it is up)."
  [box x y set-pos deps]
  (h/use-layout-effect
   (fn []
     (when-let [el (.-current box)]
       (let [rc (.getBoundingClientRect el)
             l (.getBoundingClientRect js/document.body)]
         (set-pos (pure/menu-pos x y {:left (.-left l) :top (.-top l) :width (.-width l) :height (.-height l)}
                                 (.-width rc) (.-height rc)))))
     nil)
   deps))

(defn- inside-of [box] (fn [t] (some-> (.-current box) (.contains t))))

(defn- swatches [k color act]
  [:div.cm-swatches
   (for [c colours]
     ^{:key c}
     [:button.swatch {:class (when (= c color) "on") :style {:background (str "var(--c-" c ")")}
                      :title c :aria-label c :on-click (act #(rf/dispatch [:ops/color k c]))}])])

(defn- rename-input [k title close set-renaming]
  [:input.cm-rename {:auto-focus true :default-value title
                     :on-focus #(.select (.-currentTarget %))
                     :on-key-down (fn [e]
                                    (case (.-key e)
                                      "Enter" (let [t (str/trim (.. e -currentTarget -value))]
                                                (when (and (seq t) (not= t title)) (rf/dispatch [:chat/rename k t]))
                                                (close))
                                      "Escape" (do (.stopPropagation e) (set-renaming false))
                                      nil))}])

(defn- move-item [label n on? on-click]
  [:button.cm-item.cm-project {:role "menuitem" :class (when on? "on") :on-click on-click}
   [:span.l label] (when n [:span.n n])])

(defn- move-input [targets pick act set-text]
  [:input.cm-rename {:auto-focus true :placeholder "Project name or folder path"
                     :on-change #(set-text (.. % -target -value))
                     :on-key-down (fn [e]
                                    (when (= "Enter" (.-key e))
                                      (when-let [f (pure/typed-folder targets (.. e -currentTarget -value))]
                                        ((act #(pick f))))))}])

(defn project-list
  "The projects of server a chat can go to (most chats first, current marked), a typed project
  name or folder path, or no project (none?: offered). pick gets the folder (\"\": no project);
  act: what wraps a pick (it closes the menu)."
  [{:keys [server current none? pick act]}]
  (let [[text set-text] (h/use-state "")
        targets (pure/move-targets @(rf/subscribe [:all-rows]) server text)]
    [:<>
     [move-input targets pick act set-text]
     (for [{root :key label :label n :count} (take 12 targets)]
       ^{:key root}
       [move-item label n (= root current) (act #(pick root))])
     (when none?
       [:button.cm-item.cm-none {:role "menuitem" :on-click (act #(pick ""))} "No project"])]))

(defn chat-projects
  "project-list for the chat k (row: its row): a pick moves it."
  [k row act]
  [:f> project-list {:server (first k) :current (get-in row [:project :root]) :none? (boolean (:folder row))
                     :pick #(rf/dispatch [:chat/folder k %]) :act act}])

(defn- move-panel
  "Move to project: back to the menu, then the projects."
  [k row act set-moving]
  [:<>
   [:button.cm-item.cm-back {:role "menuitem" :on-click #(set-moving false)} "‹ Move to project"]
   [chat-projects k row act]])

(defn- menu-items [{:keys [k forking error]} {:keys [title color archived]} {:keys [close act renaming set-renaming set-moving]}]
  [:<>
   (if (= color "ash")
     [:button.cm-item {:role "menuitem" :on-click (act #(rf/dispatch [:ops/color k "wake"]))} "Make active"]
     [:button.cm-item {:role "menuitem" :on-click (act #(rf/dispatch [:ops/color k "ash"]))} "Make passive"])
   (if renaming
     (rename-input k title close set-renaming)
     [:button.cm-item {:role "menuitem" :on-click #(set-renaming true)} "Rename"])
   [:button.cm-item {:role "menuitem" :disabled (boolean forking)
                     :title "A new chat with this one up to its last finished turn, in the same folder"
                     :on-click #(rf/dispatch [:ops/fork k nil])}
    (if forking "Forking…" "Fork chat")]
   (when error [:div.cm-error error])
   [:button.cm-item {:role "menuitem" :on-click #(set-moving true)} "Move to project…"]
   [:button.cm-item {:role "menuitem" :on-click (act #(rf/dispatch [:chat/archive k (not archived)]))}
    (if archived "Unarchive" "Archive")]])

(defn- menu-box [{:keys [k x y error] :as m} row]
  (let [box (h/use-ref nil)
        [pos set-pos] (h/use-state {:left x :top y})
        [renaming set-renaming] (h/use-state false)
        [moving set-moving] (h/use-state false)
        close #(rf/dispatch [:ops/menu nil])
        act (fn [f] (fn [] (f) (close)))]
    (use-in-view! box x y set-pos [x y renaming moving error])
    (use-dismiss! (inside-of box) close)
    [:div.chat-menu {:ref #(set! (.-current box) %) :style pos :role "menu" :on-context-menu #(.preventDefault %)}
     [:div.cm-title (:title row)]
     (if moving
       [move-panel k row act set-moving]
       [:<>
        (swatches k (:color row) act)
        (menu-items m row {:close close :act act :renaming renaming :set-renaming set-renaming :set-moving set-moving})])]))

(defn chat-menu []
  (let [m @(rf/subscribe [:menu])
        ro @(rf/subscribe [:read-only?])
        row (when m @(rf/subscribe [:row (:k m)]))]
    (when (and m row (not ro))
      ^{:key (str (:k m) (:x m) (:y m))} [:f> menu-box m row])))

;; ---- right click on the context meter: compact ----

(rf/reg-event-db :ops/ctx-menu (fn [db [_ m]] (assoc db :ctx-menu m)))
(rf/reg-sub :ctx-menu (fn [db] (:ctx-menu db)))

(defn ctx-menu-at!
  "Right click on the context ring (the rail) or Settings' Context bar."
  [^js e]
  (.preventDefault e)
  (rf/dispatch [:ops/ctx-menu {:x (.-clientX e) :y (.-clientY e)}]))

(defn- ctx-box [{:keys [x y]} {:keys [ck state]} ro]
  (let [box (h/use-ref nil)
        [pos set-pos] (h/use-state {:left x :top y})
        close #(rf/dispatch [:ops/ctx-menu nil])
        c (:context state)
        can? (and (not ro) (not (:running state)))]
    (use-in-view! box x y set-pos [x y])
    (use-dismiss! (inside-of box) close)
    [:div.chat-menu {:ref #(set! (.-current box) %) :style pos :role "menu" :on-context-menu #(.preventDefault %)}
     [:div.cm-title (str "Context " (:percent c) "% · " (pure/k-tokens (:used c)) " of " (pure/k-tokens (:max c)))]
     [:button.cm-item {:role "menuitem" :disabled (not can?)
                       :title (if can? "Run /compact: summarise the chat so far to free context" "Working: compact when it's done")
                       :on-click #(do (rf/dispatch [:chat/compact ck]) (close))}
      "Compact chat"]]))

(defn context-menu []
  (let [m @(rf/subscribe [:ctx-menu])
        oc @(rf/subscribe [:open-chat])
        ro @(rf/subscribe [:read-only?])]
    (when (and m oc (not ro) (get-in oc [:state :context]))
      ^{:key (str (:x m) (:y m))} [:f> ctx-box m oc ro])))
