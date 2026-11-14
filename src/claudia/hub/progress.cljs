(ns claudia.hub.progress
  "Where an update stands (README decision 32), for the pill on the rail: the release builder's
  steps for each commit it checks and builds (it tells the hub with each commit status it sets),
  then, from a push to main on, every server (its front's GET /api/version, asked every 2 s until it
  runs the commit or 3 minutes pass) and the hub itself (a hub that starts on the commit it was
  waiting for marks itself done). Kept in <data>/progress.json, so the hub's own restart in the
  middle of an update loses nothing. The page asks GET /hub/progress."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [claudia.node.env :refer [env]]
            [claudia.hub.config :as config]
            [claudia.hub.front :as front]
            [claudia.shared.protocol :as protocol]))

(def ^:private hex40 #"^[0-9a-f]{40}$")
(def ^:private poll-ms 2000)
(def ^:private give-up-ms (* 3 60 1000))
(def ^:private builds-kept 6)

;; Pure ------------------------------------------------------------------------------------------

(defn with-build
  "The builds (newest first, builds-kept at most) with the builder's word on commit: its state
  (pending, success, failure), what it said, when."
  [builds {:keys [commit tree state description]} t]
  (let [old (some #(when (= commit (:commit %)) %) builds)
        b (merge old {:commit commit :state state :note description :at t}
                 (when tree {:tree tree})
                 (when-not old {:started t}))]
    (vec (take builds-kept (cons b (remove #(= commit (:commit %)) builds))))))

(defn new-release
  "A push to main: commit (title: the first line of its message) on its way to every server and the hub."
  [commit tree title servers t]
  {:commit commit :tree tree :title title :merged t
   :machines (into {} (for [s servers] [s {:state "waiting"}]))
   :hub {:state "waiting"}})

(defn machine-done
  "The release with server name on commit (its front said so) at t."
  [release server-name t]
  (cond-> release
    (= "waiting" (get-in release [:machines server-name :state]))
    (assoc-in [:machines server-name] {:state "done" :secs (/ (- t (:merged release)) 1000)})))

(defn machine-asleep
  "The release with server name marked asleep: it did not answer when the update began (a laptop
  shut, a machine off), so nobody waits for it; it updates itself when it wakes."
  [release server-name]
  (cond-> release
    (= "waiting" (get-in release [:machines server-name :state]))
    (assoc-in [:machines server-name] {:state "asleep"})))

(defn hub-done
  "The release with the hub on its commit at t."
  [release t]
  (cond-> release
    (= "waiting" (get-in release [:hub :state]))
    (assoc :hub {:state "done" :secs (/ (- t (:merged release)) 1000)})))

(defn given-up
  "The release with whatever still waits marked not reached."
  [release]
  (-> release
      (update :machines #(into {} (for [[k v] %] [k (if (= "waiting" (:state v)) {:state "not reached"} v)])))
      (update :hub #(if (= "waiting" (:state %)) {:state "not reached"} %))))

(defn settled?
  "Whether nothing in the release waits any more."
  [release]
  (not-any? #(= "waiting" (:state %)) (cons (:hub release) (vals (:machines release)))))

(defn short=
  "Whether a full commit begins with an abbreviated one (machines name 7 characters)."
  [full abbrev]
  (boolean (and (string? full) (string? abbrev) (seq abbrev) (.startsWith full abbrev))))

;; State -----------------------------------------------------------------------------------------

(defonce ^:private state (atom {:builds [] :release nil}))
(defonce ^:private file (atom nil))
(defonce ^:private poller (atom nil))

(defn- save! []
  (when-let [f @file]
    (try (fs/writeFileSync (str f ".new") (protocol/write-json @state))
         (fs/renameSync (str f ".new") f)
         (catch :default e (js/console.error (str "progress: " (.-message e)))))))

(defn- change! [f & args]
  (apply swap! state f args)
  (save!))

(defn- stop-polling! []
  (some-> @poller js/clearInterval)
  (reset! poller nil))

(defn- ask-servers!
  "Each server still waiting is asked which commit it runs; past give-up-ms, what waits is not reached."
  []
  (when-let [r (:release @state)]
    (if (> (- (js/Date.now) (:merged r)) give-up-ms)
      (do (change! update :release given-up) (stop-polling!))
      (doseq [s (config/all-servers)
              :when (= "waiting" (get-in r [:machines (:name s) :state]))]
        (.then (front/call s "GET" "/api/version" nil 1500)
               (fn [[status body]]
                 (when (and (= 200 status) (short= (:commit r) (:commit body)))
                   (change! update :release machine-done (:name s) (js/Date.now)))))))
    (when (some-> (:release @state) settled?) (stop-polling!))))

(defn- start-polling! []
  (stop-polling!)
  (reset! poller (js/setInterval ask-servers! poll-ms)))

(defn start!
  "Loads <data>/progress.json; a hub that starts on the commit its release waited for is done."
  [data-dir]
  (reset! file (path/join data-dir "progress.json"))
  (try (reset! state (merge {:builds [] :release nil} (protocol/read-json (fs/readFileSync @file "utf8"))))
       (catch :default _ nil))
  (when-let [r (:release @state)]
    (when (short= (:commit r) (env "HUB_COMMIT" nil))
      (change! update :release hub-done (js/Date.now)))
    (when-not (settled? (:release @state)) (start-polling!))))

(defn builder-said!
  "The builder's word on a commit ({:commit :tree? :state :description})."
  [{:keys [commit] st :state :as said}]
  (when (and (re-matches hex40 (str commit)) (#{"pending" "success" "failure"} st))
    (change! update :builds with-build said (js/Date.now))))

(defn- mark-asleep!
  "Each server is asked once right away (before it starts installing): one that does not answer is asleep."
  []
  (doseq [s (config/all-servers)]
    (.then (front/call s "GET" "/api/version" nil 1500)
           (fn [[status]] (when (zero? status) (change! update :release machine-asleep (:name s)))))))

(defn main-pushed!
  "A push to main: every server that is awake and the hub are followed until they run it."
  [commit tree title]
  (when (re-matches hex40 (str commit))
    (change! assoc :release (new-release commit tree title (mapv :name (config/all-servers)) (js/Date.now)))
    (mark-asleep!)
    (start-polling!)))

(defn now
  "What GET /hub/progress answers: the builds and the latest release."
  []
  @state)

(defonce ^:private started-at (js/Date.now))

(defn updated-at
  "When this hub's version went live: the merge of the release it runs, else when it started."
  []
  (let [r (:release @state)]
    (if (and r (short= (:commit r) (env "HUB_COMMIT" nil))) (:merged r) started-at)))
