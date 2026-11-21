(ns claudia.web.views.system-tiles
  "System's four tiles (README decision 95): CPU as busy cores of all with every core as a bar and
  the busiest process and its owner; Waiting (the kernel's pressure; macOS has none, so no tile and
  CPU takes the row); memory with the
  90% cap; the fullest disk; the cards' memory. Each over its line of the last 24 hours."
  (:require [claudia.web.format :as format]
            [claudia.web.views.pure.system-lists :as lists]
            [claudia.web.views.pure.system-tiles :as pure]
            [claudia.web.views.system-bits :as bits]
            [claudia.web.views.system-machine :as machine]))

(defn- spark
  "The 24-hour line under a tile (d: its path in a 100 × 26 box, nil: no history yet); cap-y: a
  dashed red line there."
  [d tone cap-y]
  [:svg.sy-spark {:viewBox "0 0 100 26" :preserveAspectRatio "none" :aria-hidden true}
   (when cap-y [:line.sy-cap {:x1 0 :x2 100 :y1 cap-y :y2 cap-y}])
   (when d [:path {:class tone :d d}])])

(defn- tile
  "A tile: its label and a muted note on top, then the body, the 24-hour line at its foot."
  [{:keys [class label note]} body foot]
  [:section.sy-tile {:class class}
   [:header.sy-tile-head [:span label] (when note [:span.sy-note note])]
   body
   [:div.sy-tile-foot foot]])

(defn- value
  "A tile's number in mono and its unit, muted, with a muted line under it."
  [n unit sub]
  [:<>
   [:div.sy-value [:b n] (when unit [:span.sy-unit unit])]
   (when sub [:div.sy-sub sub])])

;; ---- CPU ----

(defn- core-bars [cores]
  [:div.sy-cores {:class (when (> (count cores) 48) "many")}
   (for [[i c] (map-indexed vector cores)] ^{:key i} [machine/core-bar i c])])

(defn- core-key [cores]
  [:div.sy-key
   (for [[kind word] (pure/core-key cores)] ^{:key kind} [:span [:i {:class kind}] word])])

(defn- busiest-line
  "\"Busiest: ■ shadow-cljs 1.0 core (chat …) · core 5 pinned at 100%\"."
  [top cores]
  (let [p (pure/busiest top)
        pins (pure/pinned-text (pure/pinned cores))]
    (when (or p pins)
      [:div.sy-busiest
       (when p
         [:<> [:span.sy-muted "Busiest "] [:b.sy-mono (:name p)] " " [:span.sy-mono (lists/cores-text (:cores p))]
          (when (lists/owner-text (:owner p)) [:<> " " [bits/owner-tag (:owner p)]])])
       (when pins [:span.sy-pins (when p " · ") pins])])))

(defn- cpu-tile [{:keys [cpu top]} rows now]
  (let [b (pure/cpu-busy cpu)
        [n unit pct] (pure/cpu-parts b)]
    [tile {:class "cpu" :label "CPU" :note (:model cpu)}
     [:<>
      [:div.sy-value [:b n] [:span.sy-unit unit] [:span.sy-pct pct]]
      [core-bars (:cores cpu)]
      [core-key (:cores cpu)]
      [busiest-line top (:cores cpu)]]
     [spark (pure/spark rows :cores (max 1 (:total b)) now) "green"]]))

;; ---- Waiting ----

(defn- wait-row [{:keys [label tone] share :value}]
  [:div.sy-wait-row
   [:span label]
   [:span.sy-meter [:i {:class tone :style {:width (str (min 100 share) "%")}}]]
   [:span.sy-mono (str (js/Math.round share) "%")]])

(defn- waiting-tile [{:keys [pressure]} rows now]
  [tile {:class "wait" :label "Waiting" :note "last 10 s"}
   [:<>
    (for [r (pure/waiting-rows pressure)] ^{:key (:label r)} [wait-row r])
    [:p.sy-hint "Share of time programs sat waiting for that resource. Above ~10% you feel it."]]
   [spark (pure/spark rows pure/row-waiting (pure/waiting-top rows) now) "amber"]])

;; ---- memory, disk, cards ----

(defn- memory-tile [{:keys [memory]} rows now]
  (let [{:keys [percent used total]} (pure/memory-tile memory)]
    [tile {:class "small" :label "Memory" :note (str "cap " pure/memory-cap "%")}
     [value percent "%" (str (format/bytes-text used) " of " (format/bytes-text total))]
     [spark (pure/spark rows pure/row-memory 100 now) "blue" (pure/level-y pure/memory-cap 100 26)]]))

(defn- disk-tile [{:keys [disks]} rows now]
  (when-let [d (pure/fullest disks)]
    [tile {:class "small" :label "Disk" :note (format/bytes-text (+ (:used d) (:avail d)))}
     [value (:percent d) "%" (str (:mount d) " · " (format/bytes-text (:avail d)) " free")]
     [spark (pure/spark rows :disk 100 now) "blue"]]))

(defn- gpu-tile [{:keys [gpus]} rows now]
  (when-let [{:keys [used total percent] card :name} (pure/gpu-tile gpus)]
    [tile {:class "small" :label "GPU memory" :note card}
     [value percent "%" (str (format/bytes-text used) " of " (format/bytes-text total))]
     [spark (pure/spark rows :gpuMem (max 1 total) now) "violet"]]))

(defn tiles
  "The tiles of a server's Sample, over the History rows of the last 24 hours."
  [{:keys [sample day now]}]
  (let [rows (:rows day)
        smalls (remove nil? [[memory-tile sample rows now] (disk-tile sample rows now) (gpu-tile sample rows now)])]
    [:div.sy-tiles {:class (str "n" (count smalls))}
     [cpu-tile sample rows now]
     (when (:pressure sample) [waiting-tile sample rows now])
     (into [:<>] smalls)]))
