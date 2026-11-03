(ns claudia.web.views.sidebar
  "The chat list (the phone's list screen and the desktop's drawer) and the Settings screen its gear
  opens over it (web/sidebar.tsx of the TypeScript Claudia). The list holds only chats: a header
  (whose chats as a title menu, the 5-hour and week rings, the gear, + on a desktop), the search, the
  rows; + floats bottom right on a phone. Every server's chats in one list, in the order they were
  started."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.views.chat :as chat]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.dot :as dot]
            [claudia.web.views.settings :as settings]
            [claudia.web.views.usage :as usage]))

(def ^:private chat-colors
  #{"burgundy" "red" "orange" "amber" "yellow" "lime" "green" "teal" "cyan" "sky" "blue" "indigo"
    "violet" "purple" "magenta" "pink" "brown" "ash"})

(defn chat-color-var [c] (when (chat-colors c) (str "var(--c-" c ")")))

(defn ago
  "How long ago: now, 5m, 3h, 2d, then the date (Oct 3)."
  [t]
  (when t
    (let [s (/ (- (js/Date.now) t) 1000)]
      (cond (< s 60) "now"
            (< s 3600) (str (js/Math.floor (/ s 60)) "m")
            (< s 86400) (str (js/Math.floor (/ s 3600)) "h")
            (< s (* 86400 7)) (str (js/Math.floor (/ s 86400)) "d")
            :else (.toLocaleDateString (js/Date. t) js/undefined #js {:month "short" :day "numeric"})))))

(defn- one-line [s] (str/trim (str/replace (str s) #"\s+" " ")))

(defn- second-line
  "What the chat does while it works or waits on you, else the start of Claude's last reply."
  [{:keys [waiting running agents asks preview]}]
  (let [n (or agents 0)
        ask (first asks)]
    (cond
      waiting (cond (= "question" (:kind ask)) ["waiting" (str "Asks you: " (one-line (:text ask)))]
                    (seq preview) ["waiting" (one-line preview)]
                    :else ["waiting" "Waiting for you"])
      running ["working" (if (pos? n) (str "Claude working · " n " agent" (when (not= n 1) "s")) "Claude working")]
      (pos? n) ["agents" (str "Agents working · " n)]
      :else ["reply" (one-line preview)])))

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

(defn- row-view
  [{:keys [id server title color off] :as row} {:keys [active? done? multi? read-only? title-view when-text line2? on-click]
                                                :or {line2? true}}]
  (let [k [server id]
        [kind text] (when line2? (second-line row))]
    [:button.chat-row.cs-row {:class [(when active? "active") (when off "off") (when done? "unseen")]
                              :style {"--chat" (chat-color-var color)}
                              :on-click (or on-click #(pick! k active? %))
                              :on-context-menu #(chatmenu/open-at! k %)}
     [dot/chat-dot row done?]
     [:span.cs-text
      [:span.cs-l1
       [:span.t (or title-view title)]
       (when read-only? [settings/lock-icon])
       (when multi? [chat/server-mark server])
       [:span.when (or when-text (ago (:at row)))]]
      (when line2? [:span.cs-l2 {:class kind} (if (str/blank? text) "\u00a0" text)])]]))

(defn- found-list [found rows-by multi? ro search-text]
  (let [hits (for [[server fs] found, f fs :let [r (rows-by [server (:id f)])] :when r] [r f])]
    (if (and (empty? hits) (seq found))
      [:p.muted.small.cs-empty "Nothing said matches."]
      [:<>
       (for [[r f] hits
             :let [k [(:server r) (:id r)]]]
         ^{:key (str (:server r) "/" (:id r))}
         [:div.found {:style {"--chat" (chat-color-var (:color r))}}
          [row-view r {:multi? multi? :read-only? ro :line2? false
                       :title-view (if (:title f) [marked-title (:title r) search-text] (:title r))
                       :when-text (if (pos? (:count f)) (str (:count f) " hit" (when (not= 1 (:count f)) "s")) (ago (:at r)))
                       :on-click #(rf/dispatch [:nav/open k])}]
          (for [h (:hits f)]
            ^{:key (:uuid h)}
            [:button.found-hit {:class (:role h) :on-click #(rf/dispatch [:nav/jump k (:uuid h)])}
             [:span.who (if (= "user" (:role h)) "you" "claude")]
             (into [:span.snip]
                   (map-indexed (fn [i part] (if (odd? i) ^{:key i} [:mark part] part))
                                (str/split (str (:snippet h)) #"[\u0001\u0002]")))])])])))

(defn- sessions-list [sessions]
  (when (seq (mapcat val sessions))
    [:<>
     [:div.cs-section "~/Work sessions"]
     (for [[server list] sessions, s list]
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
       (for [{:keys [name label]} servers
             :when (and (not (hidden name)) (= :closed (get-in all [name :status])))]
         ^{:key name} [:p.server-off-note (str (or label name) " is off · its chats as last seen")])])))

(defn- scroll-open-into-view!
  "The open chat stays in view in the list and the rail (only the list's own scroll moves)."
  [deps]
  (h/use-effect
   (fn []
     (doseq [^js el (array-seq (js/document.querySelectorAll ".cs-list .chat-row.active, .rail-dots .rail-chat.on"))]
       (let [^js list (.-parentElement el)
             r (.getBoundingClientRect el)
             l (.getBoundingClientRect list)]
         (cond (> (.-bottom r) (.-bottom l)) (set! (.-scrollTop list) (+ (.-scrollTop list) (- (.-bottom r) (.-bottom l)) 8))
               (< (.-top r) (.-top l)) (set! (.-scrollTop list) (- (.-scrollTop list) (- (.-top l) (.-top r)) 8)))))
     nil)
   deps))

(defn chat-list [phone?]
  (let [rows @(rf/subscribe [:rows])
        open @(rf/subscribe [:open])
        pending @(rf/subscribe [:pending-new])
        multi? @(rf/subscribe [:multi-server?])
        me @(rf/subscribe [:me])
        ro @(rf/subscribe [:read-only?])
        rail-search? @(rf/subscribe [:rail-search?])
        {:keys [archived? all-sessions?]} @(rf/subscribe [:list-mode])
        {:keys [text found]} @(rf/subscribe [:search])
        sessions (when (and all-sessions? (not archived?)) @(rf/subscribe [:sessions]))
        rows-by (into {} (map (juxt (juxt :server :id) identity)) rows)
        q (str/lower-case (str/trim (str text)))
        shown (if (seq q) (filter #(str/includes? (str/lower-case (str (:title %))) q) rows) rows)
        listed? (and open (contains? rows-by open))
        new-pending? (and open (not listed?) (contains? pending (second open)))
        search-ref (h/use-ref nil)]
    (scroll-open-into-view! [(str open) (count rows)])
    (h/use-effect
     (fn []
       (when rail-search?
         (js/requestAnimationFrame #(some-> (.-current search-ref) .focus)))
       nil)
     [rail-search?])
    [:div.cs-pane
     [:header.cs-head
      [settings/title-menu]
      [:f> usage/limit-rings]
      [settings/gear-button]
      (when (and (not ro) (not phone?))
        [:button.cs-icon.cs-add {:aria-label "New chat" :data-tip "New chat (Alt+N)" :on-click #(rf/dispatch [:nav/new])}
         [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M10 4.5v11M4.5 10h11"}]]])]
     (when archived?
       [:button.cs-archived {:on-click #(rf/dispatch [:ops/archived false])}
        [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M12.5 4L6.5 10l6 6"}]] "Back to chats"])
     [:label.search-box.cs-search
      [:input.search {:ref #(set! (.-current search-ref) %)
                      :placeholder "Search chats" :value (or text "")
                      :on-change #(rf/dispatch [:ops/search (.. % -target -value)])
                      :on-key-down #(when (and (= "Escape" (.-key %)) rail-search?)
                                      (.stopPropagation %)
                                      (rf/dispatch [:sidebar/toggle false]))}]]
     [:nav.cs-list {:aria-label "Chats"}
      [off-notes]
      (cond
        (nil? me) nil
        found [found-list found rows-by multi? ro text]
        :else
        [:<>
         (for [r shown
               :let [k [(:server r) (:id r)]]]
           ^{:key (str (:server r) "/" (:id r))}
           [row-view r {:active? (= open k) :multi? multi? :read-only? ro
                        :done? (and (not ro) (:unseen r) (not= open k))}])
         (when new-pending?
           [:div.chat-row.cs-row.active.cs-new
            [:span.rail-dot [:span.rail-blank {:aria-hidden true}]]
            [:span.cs-text [:span.cs-l1 [:span.t "New chat"]]]])
         (when (and (empty? shown) (not new-pending?))
           [:p.muted.small.cs-empty (cond (seq q) "No match." archived? "Nothing archived." :else "No chats yet.")])])
      (when-not found [sessions-list sessions])]
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
