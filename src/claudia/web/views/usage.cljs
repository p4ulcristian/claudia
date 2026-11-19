(ns claudia.web.views.usage
  "Meters and the model: the chalk rings (the chat's context in the header, a click runs /compact;
  the plan's 5-hour and week usage: claudia.web.views.head), where a popup opens beside a button
  (pop-place), the header's model · effort pill's state and popup, and the / command menu."
  (:require [re-frame.core :as rf]
            [claudia.web.views.pure.usage :as pure]))

(def ^:private k-tokens pure/k-tokens)
(def pretty-model pure/pretty-model)
(def ^:private level pure/level)

(defn mini-ring
  "A small chalk ring (18 px) followed by its percent; tip names it. With on-click a button."
  [{:keys [percent tip on-click on-context-menu]}]
  (let [r 7
        len (* 2 js/Math.PI r)]
    [(if on-click :button.mini-ring.action :span.mini-ring)
     {:class (level percent) :data-tip tip :aria-label tip :on-click on-click :on-context-menu on-context-menu}
     [:svg {:viewBox "0 0 18 18" :aria-hidden true}
      [:circle.r-track {:cx 9 :cy 9 :r r}]
      [:circle.r-fill {:cx 9 :cy 9 :r r :transform "rotate(-90 9 9)"
                       :stroke-dasharray (str (* (/ (min 100 percent) 100) len) " " len)}]]
     [:span.mr-num (str percent "%")]]))

(defn pop-place
  "Where the popup opens by the rings in box: below them (the list's header) or beside them (the
  rail; beside is :left for a box on the right wall's rail: it opens to the box's left)."
  [^js box beside?]
  (let [rc (.getBoundingClientRect box)
        bottom (max 6 (- js/innerHeight (.-bottom rc)))]
    (cond
      (= beside? :left) {:right (+ (- js/innerWidth (.-left rc)) 8) :bottom bottom}
      beside? {:left (+ (.-right rc) 8) :bottom bottom}
      :else {:left (max 6 (.-left rc)) :top (+ (.-bottom rc) 6)})))

(defn context-ring
  "The open chat's context use; a click compacts it (not while a turn runs)."
  [{:keys [ck state pending?]}]
  (when-let [c (:context state)]
    (let [can? (and (not pending?) (not (:running state)))]
      [mini-ring {:percent (:percent c)
       :tip (str "Context " (:percent c) "% · " (k-tokens (:used c)) " of " (k-tokens (:max c))
                 (when can? " · click to compact"))
       :on-click (when can? #(rf/dispatch [:chat/compact ck]))
       :on-context-menu (fn [^js e] (.preventDefault e)
                          (rf/dispatch [:ops/ctx-menu {:x (.-clientX e) :y (.-clientY e)}]))}])))

;; ---- model and effort ----

(rf/reg-event-db :new/settings (fn [db [_ m]] (update db :new-settings merge m)))
(rf/reg-sub :new-settings (fn [db] (:new-settings db)))

(defn model-state
  "What the header's model · effort pill and its popup need for the chat ({:ck :server :state}; no ck:
  the new chat): the server's models, the chat's settings, how a pick is put, the picked model's
  efforts, the model and effort it runs with when none is picked, and the pill's text."
  [{:keys [ck server state]}]
  (let [server-meta @(rf/subscribe [:meta server])
        models (:models server-meta)
        settings (if ck (:settings state) @(rf/subscribe [:new-settings]))
        running (pretty-model (or (not-empty (:model state)) (when-not (:model settings) (:defaultModel server-meta))))
        effort-running (pure/effort-shown (:effort state) (:defaultEfforts server-meta) (or (:model settings) ""))]
    {:models models :settings settings :running running :effort-running effort-running
     :efforts (:efforts (pure/chosen-model models settings))
     :put! (fn [m] (if ck (rf/dispatch [:chat/settings ck m]) (rf/dispatch [:new/settings m])))
     :text (pure/model-effort-text models settings running effort-running)}))

(defn- choice [label on? on-click]
  [:button.plan-item {:type "button" :role "menuitemradio" :aria-checked on? :class (when on? "on") :on-click on-click}
   [:span.plan-name label]])

(defn model-effort-pop
  "The models and the efforts to pick (\"Default\": the person's own, naming what it runs with); a
  model pick keeps the popup open for the effort, an effort pick closes it."
  [{:keys [models settings running efforts effort-running put!]} close!]
  [:div.head-pop.model-pop {:role "menu"}
   [:div.plan-title "Model"]
   (choice (str "Default" (when (seq running) (str " (" running ")"))) (nil? (:model settings)) #(put! {:model nil}))
   (for [m models :when (not= "default" (:value m))]
     ^{:key (:value m)} [choice (:name m) (= (:value m) (:model settings)) #(put! {:model (:value m)})])
   (when (empty? models) [:div.pop-note "The other models show once this server's Claude has answered."])
   (when (seq efforts)
     [:<>
      [:div.plan-title "Effort"]
      (choice (str "Default" (when effort-running (str " (" effort-running ")"))) (nil? (:effort settings))
              #(do (put! {:effort nil}) (close!)))
      (for [e efforts] ^{:key e} [choice e (= e (:effort settings)) #(do (put! {:effort e}) (close!))])])])

;; ---- the / menu ----

(def matches pure/matches)

(defn slash-key!
  "The composer's keys while the menu shows: arrows move, Tab and Enter complete. True when handled."
  [e draft-key items pick]
  (when (seq items)
    (let [n (count items)]
      (case (.-key e)
        "ArrowDown" (do (.preventDefault e) (rf/dispatch [:slash/pick (mod (inc pick) n)]) true)
        "ArrowUp" (do (.preventDefault e) (rf/dispatch [:slash/pick (mod (+ (dec pick) n) n)]) true)
        ("Tab" "Enter") (if (and (= "Enter" (.-key e)) (.-shiftKey e))
                          false
                          (do (.preventDefault e)
                              (rf/dispatch [:draft/set draft-key (str "/" (:name (nth items (min pick (dec n)))) " ")])
                              (rf/dispatch [:slash/pick 0])
                              true))
        false))))

(defn slash-menu [draft-key items pick]
  (when (seq items)
    [:ul.slash-menu {:role "listbox"}
     (for [[i c] (map-indexed vector items)]
       ^{:key (:name c)}
       [:li {:role "option" :aria-selected (= i pick) :class (when (= i pick) "on")
             :on-mouse-down (fn [e] (.preventDefault e)
                              (rf/dispatch [:draft/set draft-key (str "/" (:name c) " ")])
                              (rf/dispatch [:slash/pick 0]))
             :on-mouse-enter #(rf/dispatch [:slash/pick i])}
        [:b (str "/" (:name c))] (when (seq (:hint c)) [:span.hint " " (:hint c)])
        [:span.desc (:description c)]])]))
