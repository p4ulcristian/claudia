(ns claudia.slave.sdk-store
  "Reading a release's Agent SDK store (<release>.sdk, deploy/sdk-store.sh; README decisions 80 and
  81): the versions it holds, the ones marked broken (<release>.sdk/broken, a rolled-back channel
  version), and the version a new session starts on: the newest installed one not marked. Read
  each time it is asked, so a version the update puts in (or marks) counts at once, with no restart."
  (:require ["node:fs" :as fs]
            ["node:module" :as module]
            ["node:path" :as path]
            [claudia.shared.sdk-channel :as channel]
            [claudia.slave.sdk-version :as sdk-version]))

(defn program-release
  "The release folder the program was started from (<release>/out/<program>.js): the path, not
  where it pointed then, so after an update it names the new release."
  []
  (path/resolve (path/dirname (aget js/process.argv 1)) ".."))

(defn version-at
  "The SDK version in package folder dir, nil when it has none."
  [dir]
  (try (.-version (js/JSON.parse (fs/readFileSync (path/join dir "package.json") "utf8")))
       (catch :default _ nil)))

(defn own-package
  "The release's own SDK package folder, as Node resolves it from out/host.js; nil when it has none."
  [release]
  (try (path/dirname (.resolve (module/createRequire (path/join release "out" "host.js")) channel/package))
       (catch :default _ nil)))

(defn stored
  "The versions the store holds complete: folders named as a version whose package says it."
  [release]
  (try (vec (filter #(and (channel/version? %) (= % (version-at (sdk-version/stored-package release %))))
                    (fs/readdirSync (str release ".sdk"))))
       (catch :default _ [])))

(defn marks
  "The versions marked broken in the store (rolled back): new sessions skip them."
  [release]
  (try (channel/parse-marks (fs/readFileSync (str release ".sdk/broken") "utf8"))
       (catch :default _ #{})))

(defn found
  "{:version v :dir} of version v's package: its store copy, else the release's own (own: that
  folder), nil when neither holds v."
  [release v own]
  (let [copy (sdk-version/stored-package release v)
        dir (sdk-version/package-dir v [[copy (version-at copy)] [own (version-at own)]])]
    (when dir {:version v :dir dir})))

(defn installed
  "{:version :dir} of the SDK a new session starts on: the newest of the release's own and the
  store's not marked broken (channel/usable-newest); nil when none is found."
  [release]
  (let [own (own-package release)
        v (channel/usable-newest (some-> own version-at) (stored release) (marks release))]
    (when v (found release v own))))
