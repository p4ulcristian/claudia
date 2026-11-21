(ns claudia.slave.system-front
  "The front's System calls (README decision 96), the hub's only: GET /api/system/details,
  /api/system/history and /api/system/unit-log. The chats' owners come from each profile's runner
  (POST /api/system/chats with the key hashes the root helper read); the history is kept in the
  front's own SQLite (FRONT_DATA/system.db), one row a minute, whether or not anyone asks."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:sqlite" :refer [DatabaseSync]]
            [claudia.node.details :as details]
            [claudia.node.env :as env]
            [claudia.node.events :as events]
            [claudia.node.history :as history]
            [claudia.node.http :as io]
            [claudia.node.reads :as reads]
            [claudia.node.root-read :as root-read]))

(defonce ^:private db (atom nil))

(defn data-dir
  "Where the front keeps its store: FRONT_DATA, else the user's data folder's claudia-front."
  []
  (env/env "FRONT_DATA" (path/join (env/env "XDG_DATA_HOME" (path/join (os/homedir) ".local" "share")) "claudia-front")))

(defn chats-answer
  "A runner's /api/system/chats body as {hash chat}, each chat with the runner's profile."
  [body profile]
  (into {} (for [[k c] (:chats body) :when (map? c)] [(name k) (assoc c :profile profile)])))

(defn- runner-chats!
  "Promise of {hash chat} from one runner (nil when it does not answer in 5 s)."
  [{:keys [socket profile]} hashes]
  (-> (io/socket-json socket "/api/system/chats" {:keys hashes} 5000)
      (.then (fn [{:keys [status body]}] (when (= 200 status) (chats-answer body profile))))
      (.catch (fn [_] nil))))

(defn- chats-fn
  "The chats lookup for claudia.node.details: every profile's runner asked once (one per socket);
  nil when one did not answer (its chats' processes are then not called leftovers)."
  [profiles]
  (fn [hashes]
    (.then (js/Promise.all (into-array (map #(runner-chats! % hashes) (map first (vals (group-by :socket @profiles))))))
           #(when (every? some? %) (apply merge {} %)))))

(defn- opts [profiles] {:chats-fn (chats-fn profiles)})

(defn- query [^js req]
  (.-searchParams (js/URL. (.-url req) "http://front")))

(defn- details-call [profiles ^js res]
  (-> (details/latest! (opts profiles))
      (.then #(io/send-json res 200 %))
      (.catch #(io/send-json res 500 {:error (.-message %)}))))

(defn- history-call [profiles ^js req ^js res]
  (let [since (- (js/Date.now) (* 3600000 (history/hours-of (.get (query req) "hours"))))]
    (if-let [d @db]
      (-> (details/log! (opts profiles))
          (.catch (fn [_] []))
          (.then #(io/send-json res 200 (history/answer (history/rows-since d since) % since))))
      (io/send-json res 503 {:error "The front keeps no history (its store did not open)."}))))

(defn unit-log-ask
  "A unit-log query's params ({unit scope user n}, texts) as the read frame."
  [{:keys [unit scope user n]}]
  (cond-> {:read "unit-log" :unit unit :scope (or (not-empty scope) "system")
           :n (if (seq n) (js/Number n) 50)}
    (seq user) (assoc :user user)))

(defn- unit-log-call [^js req ^js res]
  (let [q (query req)
        ask (unit-log-ask {:unit (.get q "unit") :scope (.get q "scope") :user (.get q "user") :n (.get q "n")})]
    (if-let [problem (reads/unit-log-problem ask)]
      (io/send-json res 400 {:error problem})
      (.then (root-read/ask! ask)
             (fn [{:keys [out error missing]}]
               (cond missing (io/send-json res 503 {:error missing})
                     error (io/send-json res 502 {:error error})
                     :else (io/send-json res 200 {:lines (events/log-lines out)})))))))

(defn handle
  "Answers url when it is one of System's calls (true), else nil. profiles: the front's atom."
  [profiles url ^js req ^js res]
  (when (#{"/api/system/details" "/api/system/history" "/api/system/unit-log"} url)
    (if (not= "GET" (.-method req))
      (io/send-json res 405 {:error "GET only"})
      (case url
        "/api/system/details" (details-call profiles res)
        "/api/system/history" (history-call profiles req res)
        "/api/system/unit-log" (unit-log-call req res)))
    true))

(defn start!
  "Opens FRONT_DATA/system.db and keeps a history row a minute; a store that does not open is
  logged and System has no history here."
  [profiles]
  (try (let [dir (data-dir)]
         (fs/mkdirSync dir #js {:recursive true})
         (let [d (DatabaseSync. (path/join dir "system.db"))]
           (.exec d "PRAGMA journal_mode = WAL;")
           (reset! db d)
           (history/start! d #(details/context-now! (opts profiles)))))
       (catch :default e (js/console.error (str "front: no System history: " (.-message e))))))
