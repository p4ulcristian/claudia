(ns claudia.web.views.sidebar
  "The chat list (the phone's list screen and the desktop's drawer) and the Settings screen its gear
  opens over it (web/sidebar.tsx of the TypeScript Claudia). The list holds only chats: a header
  (whose chats as a title menu, the 5-hour and week rings, the gear, + on a desktop), the search, the
  rows; + floats bottom right on a phone. Every server's chats in one list, in the order they were
  started. The archived list and a search's finds run newest first under time sections, each row
  with its project; the archived list's project chips narrow both. Picking a chat from either brings
  the chats list back (events/on-chat-screen)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.views.chat :as chat]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.format :as format]
            [claudia.web.views.dot :as dot]
            [claudia.web.views.pure.sidebar :as pure]
            [claudia.web.views.pure.words :as words]
            [claudia.web.views.settings :as settings]
            [claudia.web.views.logotools :as logotools]))

(def ^:private chat-colors (set words/colours-with-ash))

(defn chat-color-var [c] (when (chat-colors c) (str "var(--c-" c ")")))

(defn ago
  "How long ago: now, 5m, 3h, 2d, then the date (Oct 3)."
  [t]
  (when t
    (or (pure/ago-text t (js/Date.now))
        (.toLocaleDateString (js/Date. t) js/undefined #js {:month "short" :day "numeric"}))))

(defn pick!
  "A chat picked from the list or the rail nods \"yes\" and opens; the open one shakes \"no, already
  here\" (on a phone it shows its screen). Web Animations, so a re-render doesn't cut them short."
  [k active? ^js e]
  (let [el (.-currentTarget e)
        phone? (.-matches (js/matchMedia "(max-width: 679px)"))]
    (cond
      (and active? phone?) (rf/dispatch [:nav/open k])
      active? (.animate el (clj->js (map (fn [x] {:translate (str x "px 0")}) [0 -4 4 -3 2 0]))
                        #js {:duration 360 :easing "ease-in-out"})
      :else (do (.animate el (clj->js (map (fn [y] {:translate (str "0 " y "px")}) [0 4 -3 3 0]))
                          #js {:duration 420 :easing "ease-in-out"})
                (rf/dispatch [:nav/open k])))))

(defn- marked-title
  "The words of a search marked in a title."
  [text words]
  (let [ws (re-seq #"[\p{L}\p{N}_]+" (str/lower-case (str words)))]
    (if (empty? ws)
      text
      (let [re (js/RegExp. (str "(" (str/join "|" (map #(str/replace % #"[.*+?^${}()|\[\]\\]" "\\$&") ws)) ")") "gi")]
        (into [:<>] (map-indexed (fn [i p] (if (odd? i) ^{:key i} [:mark p] p)) (.split (str text) re)))))))

(defn elsewhere-mark
  "Two windows, one behind the other: this chat is open in another tab of this browser."
  []
  [:span.cs-elsewhere {:data-tip "Open in another tab" :aria-label "Open in another tab"}
   [:svg {:viewBox "0 0 16 16" :aria-hidden true}
    [:rect {:x 1.5 :y 5 :width 9.5 :height 8 :rx 1.5}]
    [:path {:d "M5 5V3.5A1.5 1.5 0 0 1 6.5 2h7A1.5 1.5 0 0 1 15 3.5v5a1.5 1.5 0 0 1-1.5 1.5H11"}]]])

(defn- row-view
  [{:keys [id server title color off] :as row} {:keys [active? done? multi? read-only? title-view when-text line2? on-click tag project? passive? first-passive?]
                                                :or {line2? true}}]
  (let [k [server id]
        [kind text] (when line2? (pure/second-line row))
        elsewhere? (contains? @(rf/subscribe [:open-elsewhere]) k)]
    [:button.chat-row.cs-row {:class [(when active? "active") (when off "off") (when done? "unseen") (when elsewhere? "elsewhere")
                                      (when passive? "passive") (when first-passive? "first")]
                              :style {"--chat" (chat-color-var color)}
                              :on-click (or on-click #(pick! k active? %))
                              :on-context-menu #(chatmenu/open-at! k %)}
     [dot/chat-dot row done?]
     [:span.cs-text
      [:span.cs-l1
       [:span.t (or title-view title)]
       (when read-only? [settings/lock-icon])
       (when multi? [chat/server-mark server])
       (when project? [:span.cs-project (pure/project-label row)])
       (when tag [:span.cs-tag tag])
       (when elsewhere? [elsewhere-mark])
       [:span.when (or when-text (ago (:at row)))]]
      (when line2? [:span.cs-l2 {:class kind} (if (str/blank? text) " " text)])]]))

(defn- found-hit [k h]
  [:button.found-hit {:class (:role h) :on-click #(rf/dispatch [:nav/jump k (:uuid h)])}
   [:span.who (if (= "user" (:role h)) "you" "claude")]
   (into [:span.snip]
         (map-indexed (fn [i part] (if (odd? i) ^{:key i} [:mark part] part))
                      (str/split (str (:snippet h)) #"[\u0001\u0002]")))])

(defn- row-key [r] (str (:server r) "/" (:id r)))

(defn- sectioned
  "items under their time sections (at-of: an item's time), each drawn by item-view, keyed by key-of."
  [items at-of key-of item-view]
  [:<>
   (for [[label part] (pure/in-sections items at-of (js/Date.now))]
     ^{:key label}
     [:<>
      [:div.cs-section label]
      (for [it part] ^{:key (key-of it)} [item-view it])])])

(defn- found-chat [r f {:keys [multi? ro search-text tag-archived?]}]
  (let [k [(:server r) (:id r)]]
    [:div.found {:style {"--chat" (chat-color-var (:color r))}}
     [row-view r {:multi? multi? :read-only? ro :line2? false :project? true
                  :tag (when (and tag-archived? (:archived r)) "archived")
                  :title-view (if (:title f) [marked-title (:title r) search-text] (:title r))
                  :when-text (if (pos? (:count f)) (pure/hits-label (:count f)) (ago (:at r)))
                  :on-click #(rf/dispatch [:nav/open k])}]
     (for [h (:hits f)]
       ^{:key (:uuid h)} [found-hit k h])]))

(defn- found-list
  "The search's chats with their hits (and the chats of a project whose name matches), in the project
  chip's project, under time sections; an archived one is tagged when the list mixes them (not the
  archived list)."
  [found rows-by {:keys [archived? q chip in-scope] :as m} search-text]
  (let [found (pure/with-project-finds found in-scope q)
        hits (for [[server fs] found, f fs
                   :let [r (rows-by [server (:id f)])]
                   :when (and r (seq (pure/in-project [r] chip)))]
               [r f])
        o {:multi? (:multi? m) :ro (:ro m) :search-text search-text :tag-archived? (not archived?)}]
    (if (and (empty? hits) (seq found))
      [:p.muted.small.cs-empty "Nothing said matches."]
      [sectioned hits (comp :at first) (comp row-key first) (fn [[r f]] [found-chat r f o])])))

(defn- sessions-list [sessions]
  (when (seq (mapcat val sessions))
    [:<>
     [:div.cs-section "~/Work sessions"]
     (for [[server server-sessions] sessions, s server-sessions]
       ^{:key (str server "/" (:id s))}
       [:button.chat-row.cs-row.session-row {:on-click #(rf/dispatch [:ops/adopt server (:id s)])}
        [:span.rail-dot [:span.rail-blank]]
        [:span.cs-text [:span.cs-l1 [:span.t (:title s)] [:span.when (ago (:at s))]]]])]))

(defn- off-notes
  "Servers that are off: their chats show as last seen."
  []
  (let [servers @(rf/subscribe [:server-list])
        hidden @(rf/subscribe [:hidden-servers])
        all @(rf/subscribe [:servers])]
    (when (> (count servers) 1)
      [:<>
       (for [{server-name :name :keys [label]} servers
             :when (and (not (hidden server-name)) (= :closed (get-in all [server-name :status])))]
         ^{:key server-name} [:p.server-off-note (str (or label server-name) " is off · its chats as last seen")])])))

(defn- scroll-open-into-view!
  "The open chat stays in view in the list and the rail (only the list's own scroll moves)."
  [deps]
  (h/use-effect
   (fn []
     (doseq [^js el (array-seq (js/document.querySelectorAll ".cs-list .chat-row.active, .rail-dots .rail-chat.on"))]
       (let [^js scroller (.-parentElement el)
             r (.getBoundingClientRect el)
             l (.getBoundingClientRect scroller)]
         (cond (> (.-bottom r) (.-bottom l)) (set! (.-scrollTop scroller) (+ (.-scrollTop scroller) (- (.-bottom r) (.-bottom l)) 8))
               (< (.-top r) (.-top l)) (set! (.-scrollTop scroller) (- (.-scrollTop scroller) (- (.-top l) (.-top r)) 8)))))
     nil)
   deps))

(defn- list-head [ro phone? archived? n-archived]
  [:<>
   [:header.cs-head
    [settings/title-menu]
    [logotools/tools]
    [settings/gear-button]
    (when (and (not ro) (not phone?))
      [:button.cs-icon.cs-add {:aria-label "New chat" :data-tip "New chat (Alt+N)" :on-click #(rf/dispatch [:nav/new])}
       [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M10 4.5v11M4.5 10h11"}]]])]
   (when archived?
     [:div.cs-archived-head
      [:button.cs-archived {:on-click #(rf/dispatch [:ops/archived false])}
       [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M12.5 4L6.5 10l6 6"}]] "Back to chats"]
      [:span.cs-count (str n-archived " archived")]])])

(defn- chip-button [label n on? tip on-click]
  [:button.cs-chip {:class (when on? "on") :title tip :on-click on-click}
   label [:span.n n]])

(defn- project-chips
  "The archived list's projects as chips (All first); one picked narrows the list and the search."
  [rows chosen]
  (let [chips (pure/project-chips rows)]
    (when (> (count chips) 1)
      [:div.cs-chips {:role "toolbar" :aria-label "Projects"}
       [chip-button "All" (count rows) (nil? chosen) "Every archived chat" #(rf/dispatch [:ops/project-filter nil])]
       (for [{k :key label :label n :count} chips]
         ^{:key k}
         [chip-button label n (= k chosen) (if (seq k) (format/tilde k) "Chats with no project folder")
          #(rf/dispatch [:ops/project-filter k])])])))

(defn- search-box [search-ref text rail-search? archived?]
  [:label.search-box.cs-search
   [:input.search {:ref #(set! (.-current search-ref) %)
                   :placeholder (if archived? "Search archived: titles, projects, what was said" "Search chats")
                   :value (or text "")
                   :on-change #(rf/dispatch [:ops/search (.. % -target -value)])
                   :on-key-down #(when (and (= "Escape" (.-key %)) rail-search?)
                                   (.stopPropagation %)
                                   (rf/dispatch [:sidebar/toggle false]))}]])

(defn- archived-rows
  "The archived list: newest first under time sections, each row with its project; a pick opens the
  chat and the chats list comes back."
  [{:keys [shown open multi? ro q]}]
  (if (empty? shown)
    [:p.muted.small.cs-empty (pure/empty-note q true)]
    [sectioned shown :at row-key
     (fn [r]
       (let [k [(:server r) (:id r)]]
         [row-view r {:active? (= open k) :multi? multi? :read-only? ro :project? true
                      :on-click #(rf/dispatch [:nav/open k])}]))]))

(defn- chat-rows
  "The chats list: passive chats (ash, or open in another tab) last and dimmed, a dashed line over
  the first of them, as on the rail."
  [{:keys [shown open multi? ro new-pending? q archived?]}]
  (let [elsewhere @(rf/subscribe [:open-elsewhere])
        passive? #(pure/passive? % elsewhere open)
        rows (pure/passive-last shown elsewhere open)]
    [:<>
     (for [[i r] (map-indexed vector rows)
           :let [k [(:server r) (:id r)]]]
       ^{:key (row-key r)}
       [row-view r {:active? (= open k) :multi? multi? :read-only? ro
                    :passive? (passive? r)
                    :first-passive? (and (passive? r) (not (some-> (get rows (dec i)) passive?)))
                    :tag (when (:archived r) "archived")
                    :done? (and (not ro) (:unseen r) (not= open k))}])
     (when new-pending?
       [:div.chat-row.cs-row.active.cs-new
        [:span.rail-dot [:span.rail-blank {:aria-hidden true}]]
        [:span.cs-text [:span.cs-l1 [:span.t "New chat"]]]])
     (when (and (empty? shown) (not new-pending?))
       [:p.muted.small.cs-empty (pure/empty-note q archived?)])]))

(defn- use-search-focus! [search-ref rail-search?]
  (h/use-effect
   (fn []
     (when rail-search?
       (js/requestAnimationFrame #(some-> (.-current search-ref) .focus)))
     nil)
   [rail-search?]))

(defn- by-key [rows] (into {} (map (juxt (juxt :server :id) identity)) rows))

(defn- list-state
  "What the list shows: the rows the search text and the project chip keep, and what found-list and
  the rows need."
  [rows every-row {:keys [archived? text chip]}]
  (let [open @(rf/subscribe [:open])
        q (pure/search-key text)]
    {:shown (pure/in-project (pure/matching rows q) chip) :open open :q q :archived? archived? :chip chip
     :in-scope (if archived? rows every-row)
     :multi? @(rf/subscribe [:multi-server?]) :ro @(rf/subscribe [:read-only?])
     :new-pending? (and open (not (contains? (by-key rows) open))
                        (contains? @(rf/subscribe [:pending-new]) (second open)))}))

(defn- list-body [{:keys [archived?] :as m} found every-row text]
  (cond
    (nil? @(rf/subscribe [:me])) nil
    found [found-list found (by-key every-row) m text]
    archived? (archived-rows m)
    :else (chat-rows m)))

(defn chat-list [phone?]
  (let [rows @(rf/subscribe [:rows])
        every-row @(rf/subscribe [:all-rows])
        rail-search? @(rf/subscribe [:rail-search?])
        {:keys [archived? all-sessions?]} @(rf/subscribe [:list-mode])
        {:keys [text found]} @(rf/subscribe [:search])
        chip (when archived? @(rf/subscribe [:project-filter]))
        search-ref (h/use-ref nil)
        {:keys [open ro] :as m} (list-state rows every-row {:archived? archived? :text text :chip chip})]
    (scroll-open-into-view! [(str open) (count rows)])
    (use-search-focus! search-ref rail-search?)
    [:div.cs-pane
     (list-head ro phone? archived? (count rows))
     (search-box search-ref text rail-search? archived?)
     (when archived? [project-chips rows chip])
     [:nav.cs-list {:aria-label "Chats"}
      [off-notes]
      [list-body m found every-row text]
      (when-not found [sessions-list (when (and all-sessions? (not archived?)) @(rf/subscribe [:sessions]))])]
     (when (and (not ro) phone?)
       [:button.cs-fab {:aria-label "New chat" :on-click #(rf/dispatch [:nav/new])}
        [:svg {:viewBox "0 0 24 24" :aria-hidden true} [:path {:d "M12 5v14M5 12h14"}]]])]))

(defn- use-lingers
  "True while on, and for ms after it goes off (a screen that slides out stays mounted meanwhile)."
  [on ms]
  (let [[left set-left] (h/use-state on)]
    (h/use-effect
     (fn []
       (if on
         (do (set-left true) js/undefined)
         (let [t (js/setTimeout #(set-left false) ms)]
           #(js/clearTimeout t))))
     [on])
    (or on left)))

(defn sidebar
  "The list, and Settings sliding over it while shown?."
  [{:keys [shown? settings? phone?]}]
  (let [mounted? (use-lingers settings? 400)]
    [:aside.sidebar.cs-side {:aria-hidden (not shown?) :inert (when-not shown? true)}
     [:f> chat-list phone?]
     (when mounted? [settings/settings-pane settings?])]))
