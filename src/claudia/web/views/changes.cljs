(ns claudia.web.views.changes
  "The pill in the chat's header: the files Claude changed in a turn with +/−, opening a list of them
  under the pill, each file's diff on click (one column or side by side)."
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.views.diff :as diff]
            [claudia.web.views.pure.changes :as pure]
            [claudia.web.views.pure.words :as words]))

(defn- file-icon []
  [:svg {:width 13 :height 13 :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width 1.5
         :stroke-linejoin "round" :aria-hidden true}
   [:path {:d "M3.5 1.5h6l3 3v10h-9z"}]
   [:path {:d "M9.5 1.5v3h3M6 8.5h4M8 6.5v4M6 12.5h4" :stroke-linecap "round"}]])

(defn- diff-view [ck change _split?]
  (let [whole? (r/atom false)]
    (rf/dispatch [:changes/diff ck (:id change)])
    (fn [ck change split?]
      (let [d @(rf/subscribe [:diff ck (:id change)])]
        (cond
          (:skipped change) [:p.muted.small (str "Not shown: " (:skipped change) ".")]
          (nil? d) [:p.muted.small "Loading…"]
          (:error d) [:p.muted.small (:error d)]
          :else [:div.diff-wrap
                 [diff/diff-table (:before d) (:after d) split? @whole?]
                 [:button.link {:on-click #(swap! whole? not)}
                  (if @whole? "Show only the changes" "Show the whole file")]])))))

(defn- change-row [ck c folder shown split?]
  [:li.change {:class (when (= @shown (:id c)) "open")}
   [:button.change-row {:on-click #(swap! shown (fn [s] (when-not (= s (:id c)) (:id c))))}
    [:span.chev {:class (when (= @shown (:id c)) "open")} "›"]
    [:span.change-path {:title (:path c)} (words/clip (pure/short-path (:path c) folder) 44)]
    (when (not= "modified" (:status c))
      [:span.chip {:class (if (= "added" (:status c)) "green" "red")}
       (if (= "added" (:status c)) "new" "deleted")])
    [:span.counts [:span.plus "+" (:added c)] " " [:span.minus "−" (:removed c)]]]
   (when (= @shown (:id c)) [diff-view ck c split?])])

(defn changes-panel
  "A turn's changed files; each opens its diff. In the pill's dropdown it is always open."
  [_ck _changes _folder _label]
  (let [shown (r/atom nil)]
    (fn [ck changes folder label]
      (let [split? @(rf/subscribe [:diff-split])
            {:keys [added removed]} (pure/totals changes)]
        [:section.panel.changes
         [:div.changes-head
          [:button.tasks-head {:disabled true}
           [:b "Changes"]
           [:span.muted.small label " · " (count changes) (if (= 1 (count changes)) " file" " files") " · "
            [:span.plus "+" added] " " [:span.minus "−" removed]]]
          [:button.link.toggle-split {:on-click #(rf/dispatch [:changes/split (not split?)])}
           (if split? "One column" "Side by side")]]
         [:ul
          (for [c changes]
            ^{:key (:id c)} [change-row ck c folder shown split?])]]))))

(defn- use-pill-closes!
  "The list closes for another chat, when the turn has no changes left, on a click outside, on
  Escape."
  [ck box open set-open n]
  (h/use-effect (fn [] (set-open false) nil) [(str ck)])
  (h/use-effect (fn [] (when (and open (zero? n)) (set-open false)) nil) [open n])
  (h/use-effect
   (fn []
     (when open
       (let [down (fn [e] (when-not (some-> (.-current box) (.contains (.-target e))) (set-open false)))
             k (fn [e] (when (= "Escape" (.-key e)) (.stopPropagation e) (set-open false)))]
         (js/document.addEventListener "pointerdown" down)
         (js/window.addEventListener "keydown" k true)
         #(do (js/document.removeEventListener "pointerdown" down)
              (js/window.removeEventListener "keydown" k true)))))
   [open]))

(defn- pill-view [ck state folder]
  (let [[open set-open] (h/use-state false)
        box (h/use-ref nil)
        {:keys [turn label added removed] cs :changes} (pure/turn-changes state @(rf/subscribe [:viewing-msg ck]))]
    (use-pill-closes! ck box open set-open (count cs))
    (when (seq cs)
      [:div.act {:ref #(set! (.-current box) %)}
       [:div.act-pill
        [:button.act-part {:class (when open "on")
                           :title (str "Changes · " label ": " (count cs) (if (= 1 (count cs)) " file" " files")
                                       ", +" added " −" removed)
                           :on-click #(set-open not)}
         [file-icon] [:span (count cs)] [:span.plus "+" added] [:span.minus "−" removed]]]
       (when open
         [:div.act-pop
          ^{:key turn} [changes-panel ck cs folder label]])])))

(defn pill
  "The header's pill: the changes of the turn looked at (activity.tsx of the TypeScript Claudia)."
  [ck state folder]
  [:f> pill-view ck state folder])
