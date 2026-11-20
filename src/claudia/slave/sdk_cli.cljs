(ns claudia.slave.sdk-cli
  "out/sdk.js: the Agent SDK channel's decisions (claudia.shared.sdk-channel, README decision 87) for
  the shell scripts, so deploy/update.sh and the release builder decide with the same tested code:

    node out/sdk.js version <v>                       exit 0 when v is a plain version
    node out/sdk.js publish <latest> <published> <pinned>   exit 0 when the builder publishes latest
    node out/sdk.js platforms                         the platforms built, one per line
    node out/sdk.js platform <uname -s> <uname -m>    this machine's platform (exit 1: none built)
    node out/sdk.js npm-flags <platform>              npm ci's flags for it, one per line
    node out/sdk.js left-out <platform>               the packages taken out of it, one per line
    node out/sdk.js tarball <version> <platform>      the file's name
    node out/sdk.js install <version> <release>       exit 0 when the server stores it
    node out/sdk.js installed <release>               the version a new session starts on

  An answer that is no exits 1 and prints nothing."
  (:require [clojure.string :as str]
            [claudia.shared.sdk-channel :as channel]
            [claudia.slave.sdk-store :as sdk-store]))

(defn- own-version [release] (some-> (sdk-store/own-package release) sdk-store/version-at))

(defn- install? [v release]
  (channel/install? v (own-version release) (sdk-store/stored release) (sdk-store/marks release)))

(defn answer
  "The command's answer: a string to print, true (yes, nothing to print) or nil (no)."
  [[cmd a b c]]
  (case cmd
    "version" (channel/version? a)
    "publish" (channel/publish? a b c)
    "platforms" (str/join "\n" channel/platforms)
    "platform" (channel/platform a b)
    "npm-flags" (some->> (channel/build-of a) :npm-flags (str/join "\n"))
    "left-out" (some->> (channel/build-of a) :left-out (str/join "\n"))
    "tarball" (when (and (channel/version? a) (some #{b} channel/platforms)) (channel/tarball a b))
    "install" (install? a b)
    "installed" (:version (sdk-store/installed a))
    nil))

(defn main [& args]
  (let [a (answer args)]
    (when (string? a) (println a))
    (when-not a (set! (.-exitCode js/process) 1))))
