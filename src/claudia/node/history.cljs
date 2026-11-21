(ns claudia.node.history
  "System's history (README decision 96, PROTOCOL.md `History`): one row a minute in SQLite
  (`system_samples`), kept 7 days, sampled whether or not anyone asks, so a crash at night still
  shows in the morning. The front keeps its machine's in its own store, the hub the gate's in hub.db.
  Each row covers the whole minute: CPU and each process's CPU are the difference of two reads 60 s
  apart, the pressure the kernel's 60 s average."
  (:require [claudia.node.proc :as proc]
            [claudia.node.system :as system]
            [claudia.shared.protocol :as p]))

(def step-ms 60000)
(def keep-ms (* 7 24 60 60 1000))

(def schema
  "CREATE TABLE IF NOT EXISTS system_samples (at INTEGER PRIMARY KEY, row TEXT NOT NULL);")

;; ---- pure ----

(defn- round2 [x] (/ (js/Math.round (* 100 x)) 100))

(defn fullest-disk
  "The fullest real filesystem's use in percent (used of used + available, as df), nil with none."
  [disks]
  (let [pcts (for [{:keys [used avail]} disks :when (pos? (+ used avail))] (* 100 (/ used (+ used avail))))]
    (when (seq pcts) (/ (js/Math.round (* 10 (apply max pcts))) 10))))

(defn row-top
  "The 3 busiest processes by CPU, then the 2 biggest by memory not among them, as {:name :owner
  :cores :rss}."
  [top]
  (let [busy (take 3 (sort-by (comp - :cores) (filter #(pos? (:cores %)) top)))
        seen (set (map :pid busy))
        big (take 2 (sort-by (comp - :rss) (remove #(seen (:pid %)) top)))]
    (mapv #(select-keys % [:name :owner :cores :rss]) (concat busy big))))

(defn row-of
  "A History row from a sample s (claudia.node.system/sample-of over the minute) and the pressure
  over its last 60 s (nil without PSI)."
  [s pressure]
  (let [cores (count (get-in s [:cpu :cores]))
        gpu-mem (keep :memUsed (:gpus s))]
    (cond-> {:at (:at s)
             :cores (round2 (* cores (/ (or (get-in s [:cpu :all :busy]) 0) 100)))
             :coresTotal cores
             :mem (get-in s [:memory :used]) :memTotal (get-in s [:memory :total])
             :swap (get-in s [:memory :swapUsed])
             :disk (fullest-disk (:disks s))
             :top (row-top (:top s))}
      (seq gpu-mem) (assoc :gpuMem (reduce + gpu-mem))
      pressure (assoc :pressure (update-vals pressure :some)))))

(defn hours-of
  "The hours a History ask covers: 1 to 168, 24 when not given or not a number."
  [text]
  (let [n (js/parseInt (str text) 10)]
    (if (js/isNaN n) 24 (max 1 (min 168 n)))))

(defn answer
  "The History answer of rows and events since `since` (ms)."
  [rows evs since]
  {:step step-ms :rows rows :events (filterv #(>= (:at %) since) evs)})

;; ---- the store (a node:sqlite database: the front's own, the hub's hub.db) ----

(defn open-table! [^js db] (.exec db schema))

(defn save! [^js db row]
  (.run (.prepare db "INSERT OR REPLACE INTO system_samples (at, row) VALUES (?, ?)") (:at row) (p/write-json row)))

(defn prune! [^js db before]
  (.run (.prepare db "DELETE FROM system_samples WHERE at < ?") before))

(defn rows-since
  "The rows since `since` (ms), oldest first."
  [^js db since]
  (mapv #(p/read-json (.-row ^js %)) (.all (.prepare db "SELECT row FROM system_samples WHERE at >= ? ORDER BY at") since)))

;; ---- the minute ----

(defn- minute!
  "Reads this machine, writes the row of the minute since the read before (prev), prunes, and
  reads again in a minute. context!: promise of the owner context refreshed first (or nil)."
  [^js db prev context!]
  (-> (js/Promise.resolve (when context! (context!)))
      (.catch (fn [_] nil))
      (.then #(system/read!))
      (.then (fn [now]
               (-> (system/with-owners! (system/sample-of prev now nil))
                   (.then (fn [s]
                            (save! db (row-of s (proc/pressure (:psi now) :avg60)))
                            (prune! db (- (:t now) keep-ms))
                            now)))))
      (.catch (fn [e] (js/console.error (str "system history: " (.-message e))) prev))
      (.then (fn [r] (js/setTimeout #(minute! db r context!) step-ms)))))

(defn start!
  "Keeps one row a minute in db (its table made when missing); context!: as minute!."
  [^js db context!]
  (open-table! db)
  (.then (system/read!) (fn [first-read] (js/setTimeout #(minute! db first-read context!) step-ms))))
