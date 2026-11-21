(ns claudia.web.views.system-machine
  "A machine's card (README decision 12): every core's use split into user, system, steal and
  waiting on disk, memory, swap, disks, disk and network speed, temperatures, NVIDIA cards and
  Claudia's own processes. System's Machine tab shows it from the sheet's own Sample
  (claudia.web.views.systems, decision 96); the world's stats ask for theirs every 2 s. A card older
  than 8 s greys; a server that does not answer says so."
  (:require [reagent.hooks :as h]
            [claudia.web.format :as format]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.pure.systems :as pure]))

(defn core-bar
  "One core's bar: user, system, steal and waiting on disk stacked from the bottom."
  [i core]
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
     [:span.sys-num (str (format/bytes-text used) " / " (format/bytes-text total))]]))

(defn- memory-rows [{:keys [memory]}]
  [:<>
   [bar-row "RAM" (:used memory) (:total memory)
    (str "Used = total − available · " (format/bytes-text (:cached memory)) " cache, free to take back")]
   (when (pos? (:swapTotal memory))
     [bar-row "Swap" (:swapUsed memory) (:swapTotal memory) "Swap in use"])])

(defn- disk-rows [{:keys [disks]}]
  [:<>
   (for [d disks]
     ^{:key (:mount d)}
     [bar-row (:mount d) (:used d) (+ (:used d) (:avail d))
      (str (:source d) " · " (:fs d) " · " (format/bytes-text (:avail d)) " free")])])

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

(defn machine-view
  "One machine's card from its sample, how old that is (ms) and the last ask's error."
  [{:keys [title sample error age]}]
  [:div.sys-card {:class [(when (or error (> age pure/stale-ms)) "stale") (when-not sample "off")]}
   [card-head title sample error age]
   (when (and error sample) [:div.sys-error error])
   (when sample [card-body sample])])

(defn machine-card
  "One machine's card (big over the world), asking url every 2 s while it is shown."
  [{:keys [title url]}]
  (let [[{:keys [sample got-at]} set-got] (h/use-state {})
        now (hooks/use-now)
        error (hooks/use-poll {:on? true :url-of (constantly url) :ms 2000
                               :on-answer #(set-got {:sample % :got-at (js/Date.now)})}
                              [url])]
    [machine-view {:title title :sample sample :error error :age (pure/age-ms sample got-at now)}]))
