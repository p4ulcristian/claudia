(ns claudia.web.views.usage
  "Meters and the model: the plan's 5-hour and week usage as two small rings in the chat list's
  header (and on the folded rail), bars with their reset countdown at the list's foot, the open
  chat's context use as a ring in its header (a click runs /compact), the model and effort pickers in
  the composer, and the / command menu."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]))

(defn- k-tokens [n]
  (cond (nil? n) "?"
        (>= n 1e6) (str (/ (js/Math.round (/ n 1e5)) 10) "M")
        (>= n 1000) (str (js/Math.round (/ n 1000)) "k")
        :else (str n)))

(defn pretty-model
  "\"claude-opus-5-5[1m]\" -> \"Opus 5.5 1M\"."
  [id]
  (when (seq id)
    (let [long? (re-find #"(?i)\[1m\]" id)
          parts (->> (str/split (-> id (str/replace #"\[.*\]" "") (str/replace #"^claude-" "")) #"-")
                     (remove #(re-matches #"\d{8}" %)))
          family (or (some #(when (re-matches #"(?i)[a-z]+" %) %) parts) (first parts))
          version (str/join "." (filter #(re-matches #"\d+" %) parts))]
      (str/trim (str (str/upper-case (subs family 0 1)) (subs family 1) " " version (when long? " 1M"))))))

(defn- level [p] (cond (>= p 90) "red" (>= p 70) "amber" :else "ok"))

(defn- reset-label
  "When a limit resets: \"today 14:30\", \"tomorrow 09:00\", \"Thu 23 Oct, 09:00\"."
  [ms]
  (let [d (js/Date. ms)
        time (.toLocaleTimeString d js/undefined #js {:hour "2-digit" :minute "2-digit"})
        day #(.getTime (js/Date. (.getFullYear %) (.getMonth %) (.getDate %)))
        days (js/Math.round (/ (- (day d) (day (js/Date.))) 86400000))]
    (case days
      0 (str "today " time)
      1 (str "tomorrow " time)
      (str (.toLocaleDateString d js/undefined #js {:weekday "short" :day "numeric" :month "short"}) ", " time))))

(defn- countdown
  "Time left: \"2d 4h\", \"3h 12m\", seconds in the last hour (\"14m 05s\")."
  [ms]
  (if (<= ms 0)
    "now"
    (let [s (js/Math.floor (/ ms 1000))
          d (quot s 86400) hh (quot (mod s 86400) 3600) m (quot (mod s 3600) 60) sec (mod s 60)]
      (cond (pos? d) (str d "d " hh "h")
            (pos? hh) (str hh "h " m "m")
            :else (str m "m " (when (< sec 10) "0") sec "s")))))

(defn- use-now! []
  (let [[now set-now] (h/use-state (js/Date.now))]
    (h/use-effect (fn [] (let [t (js/setInterval #(set-now (js/Date.now)) 1000)] #(js/clearInterval t))) [])
    now))

(def ^:private short-name {"five_hour" "5h" "seven_day" "wk" "seven_day_opus" "op" "seven_day_sonnet" "so"})

(def ^:private compact-icon
  "M17 7.5v6m-3.2-3.2 3.2 3.2 3.2-3.2M17 26.5v-6m-3.2 3.2 3.2-3.2 3.2 3.2M10.5 17h13")

(defn ring
  "A chalk ring with its percent; with on-click a button whose number turns into its icon on hover."
  [{:keys [short percent tip icon on-click on-context-menu]}]
  (let [r 14
        len (* 2 js/Math.PI r)]
    [(if on-click :button.ring.action :div.ring)
     {:class (level percent) :data-tip tip :on-click on-click :on-context-menu on-context-menu}
     [:svg {:viewBox "0 0 34 34" :aria-hidden true}
      [:circle.r-track {:cx 17 :cy 17 :r r}]
      [:circle.r-fill {:cx 17 :cy 17 :r r :transform "rotate(-90 17 17)"
                       :stroke-dasharray (str (* (/ (min 100 percent) 100) len) " " len)}]
      (when on-click
        [:g.r-icon {:transform "translate(17 17) scale(0.7) translate(-17 -17)"} [:path {:d icon}]])]
     [:span.r-num percent]
     [:span.r-label short]]))

(defn- two [limits]
  (keep (fn [k] (some #(when (= k (:key %)) %) limits)) ["five_hour" "seven_day"]))

(defn limit-rings
  "The list header's two rings (5-hour, week): one button that opens what they summarise (the plan
  bars with their reset times, in Settings)."
  []
  (let [limits @(rf/subscribe [:limits])
        now (use-now!)
        shown (two limits)
        tip (str/join " · " (for [l shown]
                              (str (:label l) " " (:percent l) "%"
                                   (when (:resetsAt l) (str " · in " (countdown (- (:resetsAt l) now)))))))]
    (when (seq shown)
      [:button.limit-rings
       {:aria-label (str "Usage: " (str/join ", " (map #(str (:label %) " " (:percent %) "%") shown)))
        :on-click #(rf/dispatch [:ops/settings true])}
       (for [l shown] ^{:key (:key l)} [ring {:short (short-name (:key l) (:label l)) :percent (:percent l) :tip tip}])])))

(defn- meter [{:keys [label percent title]} & children]
  (into [:div.meter {:class (level percent) :title title}
         [:span.m-label label]
         [:span.m-bar [:span {:style {:width (str (min 100 percent) "%")}}]]
         [:span.m-num (str percent "%")]]
        children))

(defn plan-meters
  "Settings' meters: the open chat's Context bar (right click: compact), whose plan the chats run
  on (a select with two saved tokens or more), each limit as a bar with when it resets and a
  ticking countdown. Nothing until there is a context or a limit."
  [server]
  (let [limits (:limits @(rf/subscribe [:meta server]))
        oc @(rf/subscribe [:open-chat])
        context (get-in oc [:state :context])
        tokens @(rf/subscribe [:tokens server])
        account (get-in tokens [:inUse :name])
        saved (map :name (:tokens tokens))
        plan (if account (str account "'s plan") "Plan")
        now (use-now!)]
    (when (or context (seq limits))
      [:div.meters
       (when context
         [:div.meter {:class (level (:percent context))
                      :title (str "This chat: " (k-tokens (:used context)) " of " (k-tokens (:max context)) " tokens ("
                                  (pretty-model (:model context)) ") · right click to compact")
                      :on-context-menu (fn [^js e] (.preventDefault e)
                                         (rf/dispatch [:ops/ctx-menu {:x (.-clientX e) :y (.-clientY e)}]))}
          [:span.m-label "Context"]
          [:span.m-bar [:span {:style {:width (str (min 100 (:percent context)) "%")}}]]
          [:span.m-num (str (:percent context) "%")]])
       (cond
         (and account (> (count saved) 1))
         [:label.pick.m-account {:title "Switch the Claude subscription for every chat here (applies on each chat's next message)"}
          [:select {:value account
                    :on-change #(rf/dispatch [:ops/put-now {:url (str "/s/" (js/encodeURIComponent server) "/api/claude-token/use")
                                                            :method "POST" :body {:name (.. % -target -value)}
                                                            :on-done (fn [ok _] (when ok (rf/dispatch [:ops/tokens server ok])))}])}
           (when-not (some #{account} saved) [:option {:value ""} plan])
           (for [n saved] ^{:key n} [:option {:value n} (str n "'s plan")])]]
         account [:span.m-account {:title "Add or switch tokens in Settings"} plan])
       (for [l limits]
         ^{:key (:key l)}
         [meter {:label (:label l) :percent (:percent l) :title (str plan ", " (:label l) " limit: " (:percent l) "% used")}
          (when (:resetsAt l)
            [:span.m-reset "resets " (reset-label (:resetsAt l)) " " [:b (str "in " (countdown (- (:resetsAt l) now)))]])])])))

(declare context-ring)

(defn rail-meters
  "The same meters on the rail (a folded list): the open chat's context (a click compacts), then a
  ring per limit, the details in the hover text."
  []
  (let [limits @(rf/subscribe [:limits])
        oc @(rf/subscribe [:open-chat])
        ro @(rf/subscribe [:read-only?])
        now (use-now!)]
    (when (or (seq limits) (get-in oc [:state :context]))
      [:div.rail-meters
       (when oc [context-ring (cond-> oc ro (assoc :pending? true))])
       (for [l limits]
         ^{:key (:key l)}
         [ring {:short (short-name (:key l) (:label l)) :percent (:percent l)
                :tip (str (:label l) " " (:percent l) "%"
                          (when (:resetsAt l) (str " · in " (countdown (- (:resetsAt l) now)) " (" (reset-label (:resetsAt l)) ")")))}])])))

(defn context-ring
  "The open chat's context use; a click compacts it (not while a turn runs)."
  [{:keys [ck state pending?]}]
  (when-let [c (:context state)]
    (let [can? (and (not pending?) (not (:running state)))]
      [ring {:short "ctx" :percent (:percent c) :icon compact-icon
             :tip (str "Context " (:percent c) "% · " (k-tokens (:used c)) " of " (k-tokens (:max c))
                       (when can? " · click to compact"))
             :on-click (when can? #(rf/dispatch [:chat/compact ck]))
             :on-context-menu (fn [^js e] (.preventDefault e)
                                (rf/dispatch [:ops/ctx-menu {:x (.-clientX e) :y (.-clientY e)}]))}])))

;; ---- model and effort ----

(rf/reg-event-db :new/settings (fn [db [_ m]] (update db :new-settings merge m)))
(rf/reg-sub :new-settings (fn [db] (:new-settings db)))

(defn pickers
  "The chat's model and effort (applied on its next start; a live session takes them at once). A
  new chat keeps them until it is made (events.cljs sends them with `created`)."
  [{:keys [ck server state pending?]}]
  (let [server (or server @(rf/subscribe [:new-server]))
        meta @(rf/subscribe [:meta server])
        models (:models meta)
        settings (if ck (:settings state) @(rf/subscribe [:new-settings]))
        put! (fn [m] (if ck (rf/dispatch [:chat/settings ck m]) (rf/dispatch [:new/settings m])))
        chosen (or (some #(when (= (:value %) (or (:model settings) "default")) %) models) (first models))
        efforts (:efforts chosen)
        running (pretty-model (or (not-empty (:model state)) (when-not (:model settings) (:defaultModel meta))))]
    (when (and (not pending?) (seq models) (not @(rf/subscribe [:read-only?])))
      [:<>
       [:label.pick.model {:title (if (seq running) (str "Model: " running (when-not (:model settings) " (your default)")) "Model for this chat")}
        [:select {:value (or (:model settings) "")
                  :on-change #(put! {:model (not-empty (.. % -target -value))})}
         [:option {:value ""} (if (and (not (:model settings)) (seq running)) running "Default model")]
         (for [m models :when (not= "default" (:value m))]
           ^{:key (:value m)} [:option {:value (:value m)} (:name m)])]]
       (when (seq efforts)
         [:label.pick.effort {:title (str "Effort: " (or (:effort settings) "your default"))}
          [:select {:value (or (:effort settings) "")
                    :on-change #(put! {:effort (not-empty (.. % -target -value))})}
           [:option {:value ""} "effort"]
           (for [e efforts] ^{:key e} [:option {:value e} e])]])])))

;; ---- the / menu ----

(defn- commands-of
  "The chat's slash commands: its own (the SDK's init) with the descriptions the probe read, else the
  probe's list."
  [state meta]
  (let [described (into {} (map (juxt :name identity)) (:commands meta))]
    (if (seq (:commands state))
      (vec (for [n (:commands state)] (or (described n) {:name n})))
      (vec (:commands meta)))))

(defn matches
  "\"/\" at the start, before any space: the commands that fit, those starting with it first."
  [text oc meta]
  (when-let [[_ q] (re-matches #"/(\S*)" (or text ""))]
    (let [q (str/lower-case q)]
      (->> (commands-of (:state oc) meta)
           (filter #(str/includes? (str/lower-case (:name %)) q))
           (sort-by #(if (str/starts-with? (str/lower-case (:name %)) q) 0 1))
           (take 8)
           vec))))

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
