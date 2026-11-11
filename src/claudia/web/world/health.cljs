(ns claudia.web.world.health
  "How a server is doing, from its Systems sample (GET /hub/servers/<name>/system, every 2 s while
  the world is open): its load as percents and one word for it, which the world shows as its
  island's look (lush, dry, scorched and smoking, grey) and on its board.")

(defn- percent [used total]
  (when (and used total (pos? total)) (js/Math.round (* 100 (/ used total)))))

(defn- sum-of [k gpus]
  (some->> (seq (keep k gpus)) (reduce +)))

(defn load-of
  "{:cpu :mem :gpu :vram} in percent, :cores, and the sizes behind them in bytes (:mem-used
  :mem-total :vram-used :vram-total; every card's memory summed); nil where the sample has none
  (no GPU, no sample)."
  [sample]
  (when sample
    (let [{:keys [used total]} (:memory sample)
          gpus (:gpus sample)
          vram-used (sum-of :memUsed gpus)
          vram-total (sum-of :memTotal gpus)]
      {:cpu (some-> (get-in sample [:cpu :all :busy]) js/Math.round)
       :cores (some-> (get-in sample [:cpu :cores]) seq count)
       :mem (percent used total) :mem-used used :mem-total total
       :gpu (some->> (seq (keep :util gpus)) (apply max) js/Math.round)
       :vram (percent vram-used vram-total) :vram-used vram-used :vram-total vram-total})))

(defn gb-text
  "used / total in GB, short: \"24 / 62 GB\", one decimal under 10 GB (\"5.9 / 16 GB\")."
  [used total]
  (when (and used total)
    (let [gb (fn [b] (let [g (/ b 1e9)] (if (< g 10) (.toFixed g 1) (str (js/Math.round g)))))]
      (str (gb used) " / " (gb total) " GB"))))

(defn- over? [{:keys [cpu mem gpu vram]} [c m g]]
  (or (and cpu (>= cpu c)) (and mem (>= mem m)) (and gpu (>= gpu g)) (and vram (>= vram m))))

(def stale-ms
  "A sample this old with the server failing since counts as no answer."
  8000)

(defn status
  "One word for a server: :dead (it does not answer, or failed for longer than stale-ms), :asking
  (no answer yet), :overworked, :busy or :free (by its CPU, memory and GPU). age: ms since the
  sample came."
  [{:keys [sample error age]}]
  (let [l (load-of sample)]
    (cond (and error (or (not sample) (> (or age 0) stale-ms))) :dead
          (not sample) :asking
          (over? l [85 90 90]) :overworked
          (over? l [50 75 50]) :busy
          :else :free)))

(def words
  {:dead "offline" :asking "asking…" :overworked "overworked" :busy "busy" :free "free"})

(def colours
  "The status word's colour on the board."
  {:dead "#9aa0a8" :asking "#c9ced8" :overworked "#ff7a59" :busy "#ffd36b" :free "#8dffb0"})

(def grass
  "The island's grass by status (hex): lush, warming, dried and scorched, grey."
  {:free 0x5cbf48 :asking 0x5cbf48 :busy 0x9dbb3f :overworked 0xb7793a :dead 0x7b7d76})

(defn dead
  "How grey the whole island turns (0-1) for a status word."
  [word]
  (if (= word :dead) 0.85 0))
