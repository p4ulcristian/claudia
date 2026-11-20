(ns claudia.slave.sdk-version
  "Which Agent SDK a chat's session runs on (README decision 86): the one a new session starts on,
  the one it falls back to, where its files are, and whether an idle chat moves to the newest
  (versions compare by claudia.shared.sdk-channel). Pure: data in, data out."
  (:require [claudia.shared.sdk-channel :refer [newest older?]]))

(defn fallback
  "The version a session falls back to when failed did not load or start: the one the host started
  with, else the newest other loaded one; nil when there is none."
  [failed start loaded]
  (if (and start (not= start failed) (contains? (set loaded) start))
    start
    (newest (remove #{failed} loaded))))

(defn start-version
  "The version a new session starts on: the release's (installed), unless it failed before (broken:
  version -> error), then its fallback."
  [installed broken start loaded]
  (if (and installed (not (contains? broken installed)))
    installed
    (fallback installed start (remove #(contains? broken %) loaded))))

(defn stored-package
  "Where version's SDK package is in a release folder's SDK store (<release>.sdk/<version>,
  deploy/sdk-store.sh): the copy a session loads when it is there, since its files stay while a
  session runs on them (the release's own node_modules go two updates later)."
  [release version]
  (str release ".sdk/" version "/node_modules/@anthropic-ai/claude-agent-sdk"))

(defn package-dir
  "The first of candidates ([[dir version-in-it] ...], in order of preference: the store copy, then
  the release's own) that holds version v, or nil."
  [v candidates]
  (when v (some (fn [[dir found]] (when (= v found) dir)) candidates)))

(defn move-to-newest?
  "Whether a chat's session closes now to move to the newest SDK: it is live, between turns, not
  settling (a background task just ended: Claude may start a turn of its own), with no background
  task running, and its SDK is older than the one new sessions start on (newest), or is one new
  sessions skip (broken: rolled back or failed, README decision 87) and not that one."
  [{:keys [live running settling background sdk broken] latest :newest}]
  (boolean (and live (not running) (not settling) (not background)
                (string? sdk) (string? latest)
                (or (older? sdk latest) (and (contains? (set broken) sdk) (not= sdk latest))))))
