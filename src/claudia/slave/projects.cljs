(ns claudia.slave.projects
  "A chat's project, from its working folder: the name the folder chip shows, and the project's own
  logo when it has one (a favicon, app icon or logo file in the repo)."
  (:require ["node:fs" :as fs]
            [claudia.node.fs :refer [dir?]]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:private rescan-ms (* 10 60 1000))
(defonce ^:private by-folder (atom {}))

(defn- there? [p] (fs/existsSync p))

(defn- root-of
  "The project folder: the git repo the folder is in. A worktree (.git is a file) belongs to the repo
  around it, or else to the folder holding the worktrees. Without git, inside the working folder: the
  project folder under its group (Work/apps/<project>)."
  [folder work]
  (or (loop [d folder]
        (when (not= d (path/dirname d))
          (let [git (path/join d ".git")]
            (cond
              (not (there? git)) (recur (path/dirname d))
              (dir? git) {:root d :name (path/basename d)}
              :else (or (loop [up (path/dirname d)]
                          (when (not= up (path/dirname up))
                            (if (dir? (path/join up ".git"))
                              {:root d :name (path/basename up)}
                              (recur (path/dirname up)))))
                        {:root d :name (path/basename (path/dirname d))})))))
      (let [rel (path/relative work folder)
            [group project] (str/split rel #"/")]
        (when (and (seq rel) (not (str/starts-with? rel "..")) project)
          {:root (path/join work group project) :name project}))
      {:root folder :name (path/basename folder)}))

(def ^:private names
  [[#"(?i)^favicon\.svg$" 100] [#"(?i)^icon\.svg$" 95] [#"(?i)^logo-mark\.svg$" 92] [#"(?i)^logo\.svg$" 85]
   [#"(?i)^apple-touch-icon\.png$" 80] [#"(?i)^apple-icon\.png$" 78] [#"(?i)^icon\.png$" 75]
   [#"(?i)^favicon(-\d+)?\.png$" 70] [#"(?i)^logo\.png$" 60] [#"(?i)^logo\.webp$" 55] [#"(?i)^favicon\.ico$" 50]])

(def ^:private skip
  #{"node_modules" ".git" "target" ".shadow-cljs" "dist" "build" "out" ".next" "vendor" "data" "reference"
    "test" "tests" "fixtures" "logos" ".cache" "tmp"})

(defn- find-logo [root]
  (let [best (atom nil)]
    (letfn [(walk [dir depth]
              (doseq [^js e (try (fs/readdirSync dir #js {:withFileTypes true}) (catch :default _ #js []))
                      :let [n (.-name e)]]
                (if (.isDirectory e)
                  (when (and (< depth 6) (not (skip n)) (not (str/starts-with? n ".")))
                    (walk (path/join dir n) (inc depth)))
                  (when-let [[_ score] (some #(when (re-find (first %) n) %) names)]
                    (let [p (path/join dir n)
                          score (- score depth)]
                      (when (and (or (nil? @best) (> score (:score @best)))
                                 (try (<= (.-size (fs/statSync p)) 1000000) (catch :default _ false)))
                        (reset! best {:path p :score score})))))))]
      (walk root 0))
    (:path @best)))

(defn project-of
  "{:name :root :logo (path or nil)} for a working folder, or nil."
  [folder work]
  (when (and (string? folder) (path/isAbsolute folder) (dir? folder))
    (let [hit (get @by-folder folder)]
      (if (and hit (< (- (js/Date.now) (:at hit)) rescan-ms))
        (:project hit)
        (let [{:keys [root name]} (root-of folder work)
              project {:name name :root root :logo (find-logo root)}]
          (swap! by-folder assoc folder {:at (js/Date.now) :project project})
          project)))))

(def ^:private types
  {".svg" "image/svg+xml" ".png" "image/png" ".webp" "image/webp" ".ico" "image/x-icon"})

(defn logo-type [p] (get types (str/lower-case (path/extname p)) "application/octet-stream"))
