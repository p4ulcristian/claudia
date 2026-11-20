(ns claudia.shared.sdk-channel
  "The Agent SDK channel (README decision 87): the release builder publishes each newer
  `@anthropic-ai/claude-agent-sdk` from npm, signed, through the hub; every server puts it into its
  SDK store and new sessions take it, with no merge, release or restart. The decisions: versions
  compared, whether the builder publishes one, which file a server fetches, whether it stores it,
  which version a new session starts on given the versions marked broken, and the hub's newest.
  Pure: data in, data out."
  (:require [clojure.string :as str]))

(def package "The npm package the channel follows." "@anthropic-ai/claude-agent-sdk")

(def ^:private plain #"^\d{1,6}\.\d{1,6}\.\d{1,6}$")

(defn version?
  "Whether v is a plain release version (\"0.3.329\"): the only kind the channel carries (npm's
  latest dist-tag), and safe in a file name and a shell word."
  [v]
  (boolean (and (string? v) (re-matches plain v))))

(defn- parts
  "A version (\"0.3.326\", \"1.0.0-beta.2\") as [numbers pre-release]: the numbers of its core, and
  its pre-release text or nil."
  [v]
  (let [[core pre] (str/split (str v) #"-" 2)]
    [(mapv #(or (parse-long %) 0) (str/split core #"\.")) pre]))

(defn- pad [nums n] (into nums (repeat (- n (count nums)) 0)))

(defn compare-versions
  "Negative when a is older than b, 0 when the same, positive when newer. Numbers compare as numbers
  (0.3.99 is older than 0.3.326); a pre-release is older than its release."
  [a b]
  (let [[na pa] (parts a)
        [nb pb] (parts b)
        n (max (count na) (count nb))
        c (compare (pad na n) (pad nb n))]
    (cond (not (zero? c)) c
          (= pa pb) 0
          (nil? pa) 1
          (nil? pb) -1
          :else (compare pa pb))))

(defn older? [a b] (neg? (compare-versions a b)))

(defn newest
  "The newest of versions (nil when there are none)."
  [versions]
  (reduce (fn [best v] (if (or (nil? best) (older? best v)) v best)) nil versions))

(defn publish?
  "Whether the release builder publishes latest (npm's latest dist-tag): a plain version, newer than
  the newest the hub has from it (published; anything but a version: none yet), and never below what
  main's package-lock pins (pinned)."
  [latest published pinned]
  (boolean (and (version? latest) (version? pinned) (not (older? latest pinned))
                (or (not (version? published)) (older? published latest)))))

(def builds
  "Every platform the channel builds, the one place that says it: its name, how npm ci installs its
  node_modules (npm-flags), and the packages taken out after verification (left-out): the musl
  Claude Code binary, which npm puts in on any Linux (the lockfile names no libc) though every
  Claudia server is glibc Linux or a Mac."
  [{:platform "linux-x64" :uname ["Linux" "x86_64"] :npm-flags []
    :left-out ["@anthropic-ai/claude-agent-sdk-linux-x64-musl"]}
   {:platform "darwin-arm64" :uname ["Darwin" "arm64"] :npm-flags ["--os=darwin" "--cpu=arm64"]
    :left-out []}])

(def platforms "The platforms' names, in build order." (mapv :platform builds))

(defn build-of "The build of platform p, nil when none." [p] (some #(when (= p (:platform %)) %) builds))

(defn platform
  "The channel's platform for a machine (uname -s, uname -m), nil when none is built for it."
  [os arch]
  (some #(when (= [os arch] (:uname %)) (:platform %)) builds))

(defn tarball
  "The file of version v for platform p."
  [v p]
  (str "claudia-sdk-" v "-" p ".tar.gz"))

(defn file-name?
  "Whether file is one of the channel's files on the hub (a tarball or its signature)."
  [file]
  (boolean (re-matches #"^claudia-sdk-\d{1,6}\.\d{1,6}\.\d{1,6}-(linux-x64|darwin-arm64)\.tar\.gz(\.sig)?$"
                       (str file))))

(defn file-version
  "The version a channel file is of, or nil."
  [file]
  (second (re-matches #"^claudia-sdk-(\d{1,6}\.\d{1,6}\.\d{1,6})-.*" (str file))))

(defn parse-marks
  "A store's `broken` file (one version per line: rolled back, README decision 87) as a set; lines
  that are not versions are ignored."
  [text]
  (into #{} (comp (map str/trim) (filter version?)) (str/split-lines (str text))))

(defn usable-newest
  "The version a new session starts on among the installed ones: the newest of the release's own
  (own, nil when it has none) and the store's (stored) that is not marked broken (marks); the
  release's own when every one is marked."
  [own stored marks]
  (or (newest (remove #(or (nil? %) (contains? marks %)) (cons own stored))) own))

(defn install?
  "Whether a server stores channel version v: a plain version, not marked broken, not stored yet,
  and not older than its release's own (own: new sessions would never pick it)."
  [v own stored marks]
  (boolean (and (version? v) (not (contains? marks v)) (not (contains? (set stored) v))
                (or (not (version? own)) (not (older? v own))))))

(defn current
  "The hub's newest channel version: the newest published one not rolled back, nil when none."
  [published broken]
  (newest (remove (set broken) published)))

(def kept "How many published versions the hub keeps (their files too)." 5)

(defn with-published
  "The hub's published list ([{:version :at}], newest first) with v published at t: once each, at
  most kept."
  [published v t]
  (vec (take kept (sort-by :version #(compare-versions %2 %1)
                           (cons {:version v :at t} (remove #(= v (:version %)) published))))))

(defn roll-back
  "The hub's channel state ({:published :broken}) with v rolled back, or nil when v is not a
  published version or is rolled back already."
  [{:keys [published broken] :as state} v]
  (when (and (some #(= v (:version %)) published) (not (some #{v} broken)))
    (assoc state :broken (vec (take 20 (cons v broken))))))

(defn stale-files
  "Of the channel files on the hub, those of versions no longer published (kept)."
  [files published]
  (let [keep-set (set (map :version published))]
    (vec (remove #(contains? keep-set (file-version %)) files))))
