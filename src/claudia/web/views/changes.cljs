(ns claudia.web.views.changes
  "The pill in the chat's header: the files Claude changed in a turn with +/−, opening a list of them
  under the pill, each file's diff on click (one column or side by side)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.web.views.diff :as diff]))

(defn- short-path [p folder]
  (let [p (str p)]
    (cond (and folder (str/starts-with? p (str folder "/"))) (subs p (inc (count folder)))
          :else (str/replace p #"^/home/[^/]+" "~"))))

(defn- clip
  "Long paths keep their end, where the file and project names are."
  [p max]
  (if (<= (count p) max)
    p
    (loop [parts (vec (str/split p #"/")) out nil]
      (let [out (or out (peek parts))
            parts (if (= out (peek parts)) (pop parts) parts)]
        (if (and (seq parts) (<= (+ (count out) (count (peek parts)) 1) (- max 2)))
          (recur (pop parts) (str (peek parts) "/" out))
          (str "…/" out))))))

(defn- turns
  "The chat's changes by turn, newest turn first: [[turn [change …]] …]."
  [changes]
  (->> changes
       (partition-by :turn)
       (map (fn [cs] [(:turn (first cs)) (vec cs)]))
       reverse
       vec))

(defn- when-label [at]
  (when at (.toLocaleTimeString (js/Date. at) js/undefined #js {:hour "2-digit" :minute "2-digit"})))

(defn- file-icon []
  [:svg {:width 13 :height 13 :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width 1.5
         :stroke-linejoin "round" :aria-hidden true}
   [:path {:d "M3.5 1.5h6l3 3v10h-9z"}]
   [:path {:d "M9.5 1.5v3h3M6 8.5h4M8 6.5v4M6 12.5h4" :stroke-linecap "round"}]])

(defn- diff-view [ck change split?]
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

(defn changes-panel
  "A turn's changed files; each opens its diff."
  [ck changes folder label]
  (let [shown (r/atom nil)]
    (fn [ck changes folder label]
      (let [split? @(rf/subscribe [:diff-split])
            added (reduce + (map :added changes))
            removed (reduce + (map :removed changes))]
        [:section.panel.changes
         [:div.changes-head
          [:b "Changes"]
          [:span.muted.small label " · " (count changes) (if (= 1 (count changes)) " file" " files") " · "
           [:span.plus "+" added] " " [:span.minus "−" removed]]
          [:span.grow]
          [:button.link.toggle-split {:on-click #(rf/dispatch [:changes/split (not split?)])}
           (if split? "One column" "Side by side")]]
         [:ul
          (for [c changes]
            ^{:key (:id c)}
            [:li.change {:class (when (= @shown (:id c)) "open")}
             [:button.change-row {:on-click #(swap! shown (fn [s] (when-not (= s (:id c)) (:id c))))}
              [:span.chev {:class (when (= @shown (:id c)) "open")} "›"]
              [:span.change-path {:title (:path c)} (clip (short-path (:path c) folder) 44)]
              (when (not= "modified" (:status c))
                [:span.chip {:class (if (= "added" (:status c)) "green" "red")}
                 (if (= "added" (:status c)) "new" "deleted")])
              [:span.counts [:span.plus "+" (:added c)] " " [:span.minus "−" (:removed c)]]]
             (when (= @shown (:id c)) [diff-view ck c split?])])]]))))

(defn pill
  "The header's pill for the chat's newest turn with changes (older turns from its menu)."
  [ck state folder]
  (let [open? (r/atom false)
        pick (r/atom nil)]
    (fn [ck state folder]
      (let [ts (turns (:changes state))
            [turn cs] (or (some #(when (= @pick (first %)) %) ts) (first ts))
            newest? (= turn (ffirst ts))]
        (when (seq ts)
          (let [added (reduce + (map :added cs))
                removed (reduce + (map :removed cs))
                label (if newest? (if (:running state) "this turn" "last turn") (str "turn at " (when-label (:at (first cs)))))]
            [:div.act
             [:div.act-pill
              [:button.act-part {:class (when @open? "on")
                                 :title (str "Changes · " label ": " (count cs) (if (= 1 (count cs)) " file" " files")
                                             ", +" added " −" removed)
                                 :on-click #(swap! open? not)}
               [file-icon] [:span (count cs)] [:span.plus "+" added] [:span.minus "−" removed]]]
             (when @open?
               [:<>
                [:div.menu-away {:on-click #(reset! open? false)}]
                [:div.act-pop
                 (when (> (count ts) 1)
                   [:div.act-turns
                    (for [[i [t tcs]] (map-indexed vector ts)]
                      ^{:key t}
                      [:button {:class (when (= t turn) "on") :on-click #(reset! pick t)}
                       (if (zero? i) "Newest" (when-label (:at (first tcs))))])])
                 ^{:key turn} [changes-panel ck cs folder label]]])]))))))
