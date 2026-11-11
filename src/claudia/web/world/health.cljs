(ns claudia.web.world.health
  "How a server is doing, from its Systems sample (GET /hub/servers/<name>/system, every 2 s while
  the world is open): its load as percents and one word for it, which the world shows as its
  island's look (lush, dry, scorched and smoking, grey) and on its board.")

(defn- percent [used total]
  (when (and used total (pos? total)) (js/Math.round (* 100 (/ used total)))))

(defn load-of
  "{:cpu :mem :gpu} in percent (nil when the sample has none: no GPU, no sample)."
  [sample]
  (when sample
    {:cpu (some-> (get-in sample [:cpu :all :busy]) js/Math.round)
     :mem (percent (get-in sample [:memory :used]) (get-in sample [:memory :total]))
     :gpu (some->> (seq (keep :util (:gpus sample))) (apply max) js/Math.round)}))

(defn- over? [{:keys [cpu mem gpu]} [c m g]]
  (or (and cpu (>= cpu c)) (and mem (>= mem m)) (and gpu (>= gpu g))))

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
