(ns claudia.slave.host-sdk
  "The host's Agent SDKs (README decision 80): each session loads the newest installed release's SDK
  when it starts, from a path of its own, so an SDK update needs no host restart. Loaded modules stay
  in memory (a running session keeps the one it started with); a version that failed falls back."
  (:require ["node:fs" :as fs]
            ["node:module" :as module]
            ["node:path" :as path]
            ["node:url" :as url]
            [claudia.slave.sdk-version :as sdk-version]))

(def ^:private package "@anthropic-ai/claude-agent-sdk")

(def release
  "The release folder the host was started from (<release>/out/host.js): the path, not where it
  pointed then, so after an update it names the new release."
  (path/resolve (path/dirname (aget js/process.argv 1)) ".."))

;; version -> {:version :sdk (the module) :z (zod beside it) :dir}
(defonce loaded (atom {}))
;; version -> why it failed to load or start (new sessions skip it until another is installed).
(defonce broken (atom {}))
;; The version loaded when the host started: the first fallback.
(defonce start (atom nil))

(defn- version-at [dir]
  (.-version (js/JSON.parse (fs/readFileSync (path/join dir "package.json") "utf8"))))

(defn- release-package
  "The release's own SDK package folder, as Node resolves it from out/host.js."
  []
  (path/dirname (.resolve (module/createRequire (path/join release "out" "host.js")) package)))

(defn installed
  "The newest installed SDK: {:version :dir} of the release's package (its store copy when the store
  has it), nil when none is found."
  []
  (try
    (let [own (release-package)
          v (version-at own)
          stored (sdk-version/stored-package release v)]
      {:version v
       :dir (if (try (= v (version-at stored)) (catch :default _ false)) stored own)})
    (catch :default e
      (js/console.error "host: no Agent SDK in" release (.-message e))
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

(defn load-start!
  "Loads the installed SDK when the host starts: the first fallback of every later session."
  []
  (-> (load!)
      (.then (fn [m] (reset! start (:version m)) (println "host: Agent SDK" (:version m) "from" (:dir m))))
      (.catch (fn [e] (js/console.error "host: no Agent SDK loaded at start:" (.-message e))))))
