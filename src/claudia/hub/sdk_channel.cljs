(ns claudia.hub.sdk-channel
  "The hub's side of the Agent SDK channel (README decision 87). The release builder publishes each
  newer Agent SDK from npm as one signed tarball per platform (claudia.shared.sdk-channel/tarball),
  brought like its release files, then says so (POST /hub/releases/sdk {version}): the hub keeps the
  version in <releases>/sdk.json and pokes every server (`sdk <version>`). Anyone may ask the newest
  and the rolled-back ones (GET /hub/releases/sdk: they name versions only, every file is signed);
  an admin rolls the newest back (POST /hub/admin/sdk-rollback {version}: every server is poked
  with `sdk-rollback <version>` and its new sessions skip it). The hub signs and checks nothing."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [claudia.hub.announce :as announce]
            [claudia.shared.protocol :as protocol]
            [claudia.shared.sdk-channel :as channel]))

(def ^:private empty-state {:published [] :broken []})

(defn- state-file [dir] (path/join dir "sdk.json"))

(defn- read-state [dir]
  (try (merge empty-state (protocol/read-json (fs/readFileSync (state-file dir) "utf8")))
       (catch :default _ empty-state)))

(defn- save! [dir state]
  (fs/writeFileSync (str (state-file dir) ".new") (protocol/write-json state))
  (fs/renameSync (str (state-file dir) ".new") (state-file dir)))

(defn files-of
  "Every file a published version needs on the hub: each platform's tarball and its signature."
  [v]
  (for [p channel/platforms s ["" ".sig"]] (str (channel/tarball v p) s)))

(defn answer
  "What GET /hub/releases/sdk and Settings say: the newest version not rolled back (nil: none), the
  rolled-back ones, and when each was published."
  [{:keys [published broken]}]
  {:newest (channel/current (map :version published) broken)
   :broken broken
   :published published})

(defn now
  "The channel as the hub has it (answer)."
  [dir]
  (answer (read-state dir)))

(defn- tidy!
  "The files of versions no longer kept go."
  [dir published]
  (doseq [f (channel/stale-files (filter channel/file-name? (fs/readdirSync dir)) published)]
    (fs/rmSync (path/join dir f) #js {:force true})))

(defn published!
  "The builder published version v: [status body]. Every server is poked once its files are here."
  [dir v]
  (let [missing (remove #(fs/existsSync (path/join dir %)) (files-of v))]
    (cond
      (not (channel/version? v)) [400 {:error "version: a plain version, e.g. 0.3.329"}]
      (seq missing) [409 {:error (str "missing: " (apply str (interpose ", " missing)))}]
      :else (let [s (update (read-state dir) :published channel/with-published v (js/Date.now))]
              (save! dir s)
              (tidy! dir (:published s))
              (js/console.log (str "sdk-channel: Agent SDK " v " published"))
              (announce/poke-all! {:sdk v})
              [200 (answer s)]))))

(defn roll-back!
  "An admin rolls version v back: [status body]. Every server's new sessions skip it."
  [dir v]
  (if-let [s (channel/roll-back (read-state dir) v)]
    (do (save! dir s)
        (js/console.log (str "sdk-channel: Agent SDK " v " rolled back"))
        (announce/poke-all! {:sdkRollback v})
        [200 (answer s)])
    [400 {:error (str v " is not a published Agent SDK, or it was rolled back already")}]))
