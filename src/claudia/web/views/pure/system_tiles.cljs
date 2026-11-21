(ns claudia.web.views.pure.system-tiles
  "The System sheet's four tiles (README decision 96), pure: CPU as busy cores of all, the cores
  pinned, the waiting rows, memory, the fullest disk and the cards' memory, and the 24-hour lines
  under them (time slots, an SVG path)."
  (:require [clojure.string :as str]
            [claudia.web.views.pure.systems :as systems]))

;; ---- CPU ----

(defn cpu-busy
  "Busy cores now, the cores' busy shares summed (100% busy = one core), of how many:
  {:busy 3.7 :total 16 :percent 23}."
  [cpu]
  (let [cores (:cores cpu)
        total (count cores)
        busy (/ (reduce + 0 (map #(or (:busy %) 0) cores)) 100)]
    {:busy busy :total total :percent (systems/percent busy total)}))

(defn cpu-parts
  "The CPU value in its three looks: the number, \" of 16 cores busy\", \" (23%)\"."
  [{:keys [busy total percent]}]
  [(.toFixed busy 1) (str " of " total (if (= 1 total) " core" " cores") " busy") (str " (" percent "%)")])

(def pinned-share "A core this busy (percent) counts as pinned." 95)

(defn pinned
  "The cores at least pinned-share busy: [[index busy] …]."
  [cores]
  (vec (keep-indexed (fn [i c] (when (>= (or (:busy c) 0) pinned-share) [i (:busy c)])) cores)))

(defn pinned-text
  "\"core 5 pinned at 100%\", \"cores 2 and 5 pinned\", \"6 cores pinned\"; nil when none."
  [pins]
  (case (count pins)
    0 nil
    1 (let [[[i b]] pins] (str "core " i " pinned at " (js/Math.round b) "%"))
    (2 3) (str "cores " (str/join ", " (map first (butlast pins))) " and " (first (last pins)) " pinned")
    (str (count pins) " cores pinned")))

(defn busiest
  "The busiest process now (top is busiest first), nil when nothing uses a twentieth of a core."
  [top]
  (let [p (first top)] (when (and p (>= (or (:cores p) 0) 0.05)) p)))

(defn core-key
  "The key under the core bars: what each colour means, steal only when a core has some."
  [cores]
  (cond-> [["user" "programs"] ["sys" "kernel"] ["iowait" "waiting on disk"]]
    (some #(pos? (or (:steal %) 0)) cores) (conj ["steal" "taken by the host"])))

;; ---- waiting ----

(defn tone
  "A share's colour: red over hi, amber over lo, else green."
  [v lo hi]
  (cond (> v hi) "red" (> v lo) "amber" :else "green"))

(defn waiting-rows
  "The kernel's pressure as the Waiting tile's rows: the share of the last 10 s some programs waited
  for CPU, memory and disk ({:label :value :tone}); nil when the kernel has none (macOS)."
  [pressure]
  (when pressure
    (vec (for [[k label] [[:cpu "for CPU"] [:memory "for memory"] [:io "for disk"]]
               :let [v (or (get-in pressure [k :some]) 0)]]
           {:label label :value v :tone (tone v 10 40)}))))

(defn waiting-most "The highest waiting share, nil without pressure." [pressure]
  (when pressure (apply max (map #(or (get-in pressure [% :some]) 0) [:cpu :memory :io]))))

;; ---- memory, disk, cards ----

(def memory-cap "The cap systemd keeps memory under (percent): the line on the memory tile." 90)

(defn memory-tile "{:percent :used :total}" [{:keys [used total]}]
  {:percent (systems/percent used total) :used used :total total})

(defn disk-share "A disk's use, as df counts it: used of used + available." [{:keys [used avail]}]
  (systems/percent used (+ (or used 0) (or avail 0))))

(defn fullest "The fullest real filesystem, with :percent; nil without disks." [disks]
  (when (seq disks)
    (let [d (apply max-key disk-share (reverse disks))] (assoc d :percent (disk-share d)))))

(defn gpu-tile
  "The cards' memory summed: {:used :total :percent :name} (a single card's name, else \"2 cards\");
  nil without cards."
  [gpus]
  (when (seq gpus)
    (let [used (reduce + 0 (keep :memUsed gpus)) total (reduce + 0 (keep :memTotal gpus))]
      {:used used :total total :percent (systems/percent used total)
       :name (if (= 1 (count gpus)) (:name (first gpus)) (str (count gpus) " cards"))})))

;; ---- the lines of the last hours ----

(defn slots
  "The rows' values (f row; nil left out) in n equal time slots from..to, each slot's highest:
  [[slot value] …] in slot order, empty slots left out (the highest keeps a short spike in sight)."
  [rows f from to n]
  (let [span (max 1 (- to from))
        slot (fn [at] (min (dec n) (js/Math.floor (* n (/ (- at from) span)))))]
    (vec (reduce (fn [m r]
                   (let [v (f r) at (:at r)]
                     (if (and (some? v) at (<= from at to)) (update m (slot at) #(if % (max % v) v)) m)))
                 (sorted-map) rows))))

(defn- round1 [x] (/ (js/Math.round (* 10 x)) 10))

(defn line-path
  "An SVG path through slot values ([[slot value] …] of n slots, 0..top) in a w × h box (y down); a
  run of empty slots (the machine was off) breaks the line."
  [pts n top w h]
  (let [x (fn [i] (round1 (* w (/ (+ i 0.5) n))))
        y (fn [v] (round1 (- h (* h (/ (min top (max 0 v)) (max top 1e-9))))))]
    (str/join " " (map (fn [[i v] prev] (str (if (= prev (dec i)) "L" "M") (x i) " " (y v)))
                       pts (cons nil (map first pts))))))

(defn level-y "Where a value (0..top) sits in a box h tall (y down)." [v top h]
  (round1 (- h (* h (/ (min top (max 0 v)) (max top 1e-9))))))

(def spark-slots "The 24-hour lines: a slot per 15 minutes." 96)

(defn spark
  "The 24-hour line of one value under a tile, drawn in a 100 × 26 box: f of a History row, top its
  highest; nil with fewer than two slots."
  [rows f top now]
  (let [pts (slots rows f (- now 86400000) now spark-slots)]
    (when (> (count pts) 1) (line-path pts spark-slots top 100 26))))

(defn row-memory "A History row's memory use in percent." [{:keys [mem memTotal]}]
  (when (and mem memTotal (pos? memTotal)) (* 100 (/ mem memTotal))))

(defn row-waiting "A History row's highest waiting share." [{:keys [pressure]}]
  (when pressure (apply max (map #(or (get pressure %) 0) [:cpu :memory :io]))))

(defn waiting-top
  "The Waiting line's top: 20% at least, else its own highest (small shares stay visible)."
  [rows]
  (apply max 20 (keep row-waiting rows)))
