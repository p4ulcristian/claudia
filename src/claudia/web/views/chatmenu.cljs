(ns claudia.web.views.chatmenu
  "Right click on a chat (a rail circle, a list row) or its header's ⋯: colour (16 colours, or
  passive ash, which remembers the colour it had), rename, fork, archive."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]))

(def colours ["burgundy" "red" "orange" "amber" "yellow" "lime" "green" "teal" "cyan"
              "sky" "blue" "indigo" "violet" "purple" "magenta" "pink" "brown"])

(defn open-at!
  "The menu for chat k at the mouse (or under an element)."
  [k ^js e]
  (.preventDefault e)
  (.stopPropagation e)
  (rf/dispatch [:ops/menu {:k k :x (.-clientX e) :y (.-clientY e)}]))

(defn- menu-box [{:keys [k x y forking error]} row]
  (let [box (h/use-ref nil)
        [pos set-pos] (h/use-state {:left x :top y})
        [renaming set-renaming] (h/use-state false)
        close #(rf/dispatch [:ops/menu nil])
        act (fn [f] (fn [] (f) (close)))
        {:keys [title color archived]} row
        passive? (= color "ash")]
    (h/use-layout-effect
     (fn []
       (when-let [el (.-current box)]
         (let [rc (.getBoundingClientRect el)]
           ;; Inside the page's visible layer (the body; above a phone's keyboard while it is up).
           (let [l (.getBoundingClientRect js/document.body)]
             (set-pos {:left (max 6 (min (- x (.-left l)) (- (.-width l) (.-width rc) 6)))
                       :top (max 6 (min (- y (.-top l)) (- (.-height l) (.-height rc) 6)))}))))
       nil)
     [x y renaming error])
    (h/use-effect
     (fn []
       (let [away (fn [e] (when-not (some-> (.-current box) (.contains (.-target e))) (close)))
             esc (fn [e] (when (= "Escape" (.-key e)) (close)))]
         (js/window.addEventListener "pointerdown" away true)
         (js/window.addEventListener "keydown" esc)
         (js/window.addEventListener "blur" close)
         #(do (js/window.removeEventListener "pointerdown" away true)
              (js/window.removeEventListener "keydown" esc)
              (js/window.removeEventListener "blur" close))))
     [])
    [:div.chat-menu {:ref #(set! (.-current box) %) :style pos :role "menu" :on-context-menu #(.preventDefault %)}
     [:div.cm-title title]
     [:div.cm-swatches
      (for [c colours]
        ^{:key c}
        [:button.swatch {:class (when (= c color) "on") :style {:background (str "var(--c-" c ")")}
                         :title c :aria-label c :on-click (act #(rf/dispatch [:ops/color k c]))}])
      [:button.swatch.none {:class (when-not color "on") :title "No colour" :aria-label "No colour"
                            :on-click (act #(rf/dispatch [:ops/color k nil]))}]]
     (if passive?
       [:button.cm-item {:role "menuitem" :on-click (act #(rf/dispatch [:ops/color k "wake"]))} "Make active"]
       [:button.cm-item {:role "menuitem" :on-click (act #(rf/dispatch [:ops/color k "ash"]))} "Make passive"])
     (if renaming
       [:input.cm-rename {:auto-focus true :default-value title
                          :on-focus #(.select (.-currentTarget %))
                          :on-key-down (fn [e]
                                         (case (.-key e)
                                           "Enter" (let [t (str/trim (.. e -currentTarget -value))]
                                                     (when (and (seq t) (not= t title)) (rf/dispatch [:chat/rename k t]))
                                                     (close))
                                           "Escape" (do (.stopPropagation e) (set-renaming false))
                                           nil))}]
       [:button.cm-item {:role "menuitem" :on-click #(set-renaming true)} "Rename"])
     [:button.cm-item {:role "menuitem" :disabled (boolean forking)
                       :title "A new chat with this one up to its last finished turn, in the same folder"
                       :on-click #(rf/dispatch [:ops/fork k nil])}
      (if forking "Forking…" "Fork chat")]
     (when error [:div.cm-error error])
     [:button.cm-item {:role "menuitem" :on-click (act #(rf/dispatch [:chat/archive k (not archived)]))}
      (if archived "Unarchive" "Archive")]]))

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

(defn- k-tokens [n] (str (js/Math.round (/ (or n 0) 1000)) "k"))

(defn- ctx-box [{:keys [x y]} {:keys [ck state]} ro]
  (let [box (h/use-ref nil)
        [pos set-pos] (h/use-state {:left x :top y})
        close #(rf/dispatch [:ops/ctx-menu nil])
        c (:context state)
        can? (and (not ro) (not (:running state)))]
    (h/use-layout-effect
     (fn []
       (when-let [el (.-current box)]
         (let [rc (.getBoundingClientRect el)
               l (.getBoundingClientRect js/document.body)]
           (set-pos {:left (max 6 (min (- x (.-left l)) (- (.-width l) (.-width rc) 6)))
                     :top (max 6 (min (- y (.-top l)) (- (.-height l) (.-height rc) 6)))})))
       nil)
     [x y])
    (h/use-effect
     (fn []
       (let [away (fn [e] (when-not (some-> (.-current box) (.contains (.-target e))) (close)))
             esc (fn [e] (when (= "Escape" (.-key e)) (close)))]
         (js/window.addEventListener "pointerdown" away true)
         (js/window.addEventListener "keydown" esc)
         (js/window.addEventListener "blur" close)
         #(do (js/window.removeEventListener "pointerdown" away true)
              (js/window.removeEventListener "keydown" esc)
              (js/window.removeEventListener "blur" close))))
     [])
    [:div.chat-menu {:ref #(set! (.-current box) %) :style pos :role "menu" :on-context-menu #(.preventDefault %)}
     [:div.cm-title (str "Context " (:percent c) "% · " (k-tokens (:used c)) " of " (k-tokens (:max c)))]
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
