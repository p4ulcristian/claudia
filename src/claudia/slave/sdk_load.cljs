(ns claudia.slave.sdk-load
  "Loading the Agent SDK a new session gets (README decision 80), for the host (each session loads
  it when it starts, so an SDK update needs no host restart) and for the runner's probe of models
  and commands (the same version as new sessions). Loaded modules stay in memory (a running session
  keeps the one it started with); a version that failed falls back."
  (:require ["node:fs" :as fs]
            ["node:module" :as module]
            ["node:path" :as path]
            ["node:url" :as url]
            [claudia.slave.sdk-version :as sdk-version]))

(def ^:private package "@anthropic-ai/claude-agent-sdk")

(def release
  "The release folder the program was started from (<release>/out/host.js or runner.js): the path,
  not where it pointed then, so after an update it names the new release."
  (path/resolve (path/dirname (aget js/process.argv 1)) ".."))

;; version -> {:version :sdk (the module) :z (zod beside it) :dir}
(defonce loaded (atom {}))
;; version -> why it failed to load or start (new sessions skip it until another is installed).
(defonce broken (atom {}))
;; The version loaded when the program started: the first fallback.
(defonce start (atom nil))

(defn- version-at
  "The SDK version in package folder dir, nil when it has none."
  [dir]
  (try (.-version (js/JSON.parse (fs/readFileSync (path/join dir "package.json") "utf8")))
       (catch :default _ nil)))

(defn- release-package
  "The release's own SDK package folder, as Node resolves it from out/host.js."
  []
  (path/dirname (.resolve (module/createRequire (path/join release "out" "host.js")) package)))

(defn- found
  "{:version v :dir} of version v's package: its store copy, else the release's own (own: that
  folder), nil when neither holds v."
  [v own]
  (let [stored (sdk-version/stored-package release v)
        dir (sdk-version/package-dir v [[stored (version-at stored)] [own (version-at own)]])]
    (when dir {:version v :dir dir})))

(defn installed
  "The newest installed SDK: {:version :dir} of the release's package (its store copy when the store
  has it), nil when none is found."
  []
  (try
    (let [own (release-package)]
      (found (version-at own) own))
    (catch :default e
      (js/console.error "Agent SDK: none in" release (.-message e))
      nil)))

(defn- import! [file v]
  ;; The version in the URL: a git checkout's one path holds another version after npm ci, and
  ;; Node keeps one module per URL.
  (js/claudia_import (str (.-href (url/pathToFileURL file)) "?v=" v)))

(defn- load-dir!
  "Promise of the SDK in dir (version v) with the zod beside it, kept in loaded."
  [{:keys [version dir]}]
  (if-let [m (get @loaded version)]
    (js/Promise.resolve m)
    (let [req (module/createRequire (path/join dir "package.json"))
          main (.resolve req package)]
      (-> (import! main version)
          (.then (fn [sdk]
                   (let [m {:version version :sdk sdk :z (req "zod") :dir dir}]
                     (swap! loaded assoc version m)
                     m)))))))

(defn pick
  "The version a new session starts on now (sdk-version/start-version), or nil."
  []
  (let [{:keys [version]} (installed)]
    (or (sdk-version/start-version version @broken @start (keys @loaded)) version)))

(defn failed!
  "Version v failed to load or to start a session (why): new sessions skip it."
  [v why]
  (swap! broken assoc v why))

(defn fallback
  "The loaded SDK a session falls back to after version failed, or nil."
  [failed]
  (get @loaded (sdk-version/fallback failed @start (keys @loaded))))

(defn load!
  "Promise of the SDK a new session starts on: {:sdk module :z :version :error?}. The installed one
  that fails to load is marked broken and the fallback comes instead, with :error saying why."
  []
  (let [inst (installed)
        v (pick)]
    (cond
      (nil? inst) (if-let [m (fallback nil)]
                    (js/Promise.resolve (assoc m :error "no Agent SDK is installed"))
                    (js/Promise.reject (js/Error. "no Agent SDK is installed")))
      (not= v (:version inst)) (js/Promise.resolve
                                (assoc (get @loaded v)
                                       :error (str "Agent SDK " (:version inst) " failed: " (get @broken (:version inst)))))
      :else (-> (load-dir! inst)
                (.catch (fn [e]
                          (let [why (str (or (.-message e) e))]
                            (failed! (:version inst) why)
                            (if-let [m (fallback (:version inst))]
                              (assoc m :error (str "Agent SDK " (:version inst) " failed to load: " why))
                              (throw e)))))))))

(defn load-version!
  "Promise of SDK version v (the one the host says new sessions start on: the runner's probe), else,
  when v is unknown, not installed here or fails to load, of load!."
  [v]
  (if-let [d (and v (not (contains? @broken v))
                  (found v (try (release-package) (catch :default _ nil))))]
    (.catch (load-dir! d) (fn [e] (failed! v (str (or (.-message e) e))) (load!)))
    (load!)))

(defn load-start!
  "Loads the installed SDK when the program starts: the first fallback of every later load."
  []
  (-> (load!)
      (.then (fn [m] (reset! start (:version m)) (println "Agent SDK" (:version m) "from" (:dir m))))
      (.catch (fn [e] (js/console.error "Agent SDK: none loaded at start:" (.-message e))))))
