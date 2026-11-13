(ns claudia.web.views.systems
  "The Systems popup (README decision 12): Machines in the settings menu (claudia.web.views.menu)
  opens a card per server the person may use (and the gate, for admins), each asking its own numbers every
  2 s while open: every core's use split into user, system, steal and waiting on disk, memory,
  swap, disks, disk and network speed, temperatures, NVIDIA cards and Claudia's own processes. A
  card older than 8 s greys; a server that does not answer says so."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.pure.systems :as pure]
            [claudia.web.views.usage :as usage]))

(defn computer-icon []
  [:svg {:viewBox "0 0 20 20" :aria-hidden true}
   [:rect {:x 2.5 :y 3.5 :width 15 :height 10 :rx 1.5}]
   [:path {:d "M7 17h6M10 13.5V17"}]])

(defn- core-bar [i core]
  [:span.sys-core {:data-tip (pure/core-tip i core)}
   (for [[kind p] (pure/core-stack core)]
     ^{:key kind} [:i {:class kind :style {:height (str p "%")}}])])

(defn- cores [{:keys [cpu]}]
  (let [n (count (:cores cpu))]
    [:div.sys-cores {:style {:grid-template-columns (str "repeat(" (min n 32) ", 1fr)")}
                     :data-tip (str (:model cpu) " · all cores " (get-in cpu [:all :busy]) "% busy")}
     (for [[i c] (map-indexed vector (:cores cpu))]
       ^{:key i} [core-bar i c])]))

(defn- bar-row
  "A labelled bar: used of total, the numbers at its end."
  [label used total tip]
  (let [p (pure/percent used total)]
    [:div.sys-row {:class (pure/level p) :data-tip tip}
     [:span.sys-label label]
     [:span.m-bar [:span {:style {:width (str (min 100 p) "%")}}]]
     [:span.sys-num (str (pure/bytes-text used) " / " (pure/bytes-text total))]]))

(defn- memory-rows [{:keys [memory]}]
  [:<>
   [bar-row "RAM" (:used memory) (:total memory)
    (str "Used = total − available · " (pure/bytes-text (:cached memory)) " cache, free to take back")]
   (when (pos? (:swapTotal memory))
     [bar-row "Swap" (:swapUsed memory) (:swapTotal memory) "Swap in use"])])

(defn- disk-rows [{:keys [disks]}]
  [:<>
   (for [d disks]
     ^{:key (:mount d)}
     [bar-row (:mount d) (:used d) (+ (:used d) (:avail d))
      (str (:source d) " · " (:fs d) " · " (pure/bytes-text (:avail d)) " free")])])

(defn- rate-row [label down up tip]
  [:div.sys-row.sys-rate {:data-tip tip}
   [:span.sys-label label]
   [:span.sys-num "↓ " (pure/rate-text down)]
   [:span.sys-num "↑ " (pure/rate-text up)]])

(defn- rates [{:keys [io net window]}]
  [:<>
   [rate-row "Disk" (:read io) (:write io) (str "Read and written per second, over the last " (/ window 1000) " s, physical disks")]
   [rate-row "Net" (:rx net) (:tx net) (str "Received and sent per second, over the last " (/ window 1000) " s, network cards")]])

(defn- temps [{ts :temps}]
  (when (seq ts)
    [:div.sys-chips
     (for [t ts]
       ^{:key (str (:name t) (:label t))}
       [:span.sys-chip {:class (pure/temp-level (:c t)) :data-tip (:label t)} (str (:name t) " " (:c t) " °C")])]))

(defn- gpus [{gs :gpus}]
  [:<>
   (for [g gs]
     ^{:key (:index g)}
     [:div.sys-row.sys-line [:span.sys-label "GPU"] [:span.sys-text {:data-tip (str (:name g) " · " (pure/gpu-text g))} (pure/gpu-text g)]])])

(defn- claudia-rows [{own :claudia}]
  (when (seq own)
    [:div.sys-own
     (for [c own
           :let [[label mem] (pure/claudia-text c)]]
       ^{:key (:kind c)} [:span.sys-chip {:data-tip (get pure/kind-tips (:kind c) "Resident memory (RSS)")} label " " [:b mem]])]))

(defn- card-body [sample]
  [:<>
   [:div.sys-meta (pure/head-text sample)]
   [cores sample]
   [memory-rows sample]
   [disk-rows sample]
   [rates sample]
   [gpus sample]
   [temps sample]
   [claudia-rows sample]])

(defn- card-head [title sample error age]
  [:div.sys-head
   [:span.sys-name title]
   [:span.sys-sub {:data-tip error}
    (cond (and error (not sample)) (str "off · " error)
          (not sample) "asking…"
          :else (pure/age-text age))]])

(defn machine-card
  "One machine's card (in the Systems popup, and big over the world), asking url every 2 s while it is
  shown."
  [{:keys [title url]}]
  (let [[{:keys [sample got-at]} set-got] (h/use-state {})
        now (hooks/use-now)
        error (hooks/use-poll {:on? true :url-of (constantly url) :ms 2000
                               :on-answer #(set-got {:sample % :got-at (js/Date.now)})}
                              [url])
        age (pure/age-ms sample got-at now)]
    [:div.sys-card {:class [(when (or error (> age pure/stale-ms)) "stale") (when-not sample "off")]}
     [card-head title sample error age]
     (when (and error sample) [:div.sys-error error])
     (when sample [card-body sample])]))

(defn- place
  "Beside the computer, never taller than the window above its foot."
  [box]
  (let [p (usage/pop-place box true)]
    (assoc p :max-height (- js/innerHeight (:bottom p) 6))))

(defn popup
  "The Systems popup beside box (the settings gear); close closes it."
  [{:keys [box close]}]
  (let [menu (h/use-ref nil)
        me @(rf/subscribe [:me])]
    (chatmenu/use-dismiss! (fn [t] (or (some-> (.-current menu) (.contains t)) (some-> (.-current box) (.contains t))))
                           close)
    [:div.chat-menu.systems-pop {:ref #(set! (.-current menu) %) :role "dialog" :aria-label "Systems"
                                 :style (place (.-current box))}
     [:div.cm-title "Systems"]
     (when (:admin me) [:f> machine-card {:title "gate" :url "/hub/gate/system"}])
     (for [s (:servers me)]
       ^{:key (:name s)}
       [:f> machine-card {:title (or (:label s) (:name s)) :url (str "/hub/servers/" (js/encodeURIComponent (:name s)) "/system")}])]))
