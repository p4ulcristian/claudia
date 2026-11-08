(ns claudia.node.anatomy
  "Serving the anatomy models (claudia.shared.anatomy) from a folder on disk: a file not there yet
  is fetched once from the models' repository (requests for the same file share one fetch), kept,
  and from then on sent from disk. The hub and the runner's render page use it."
  (:require ["node:fs" :as fs]
            ["node:fs/promises" :as fsp]
            ["node:path" :as path]
            [claudia.node.http :as io]
            [claudia.shared.anatomy :as anatomy]))

(defonce ^:private fetching (atom {}))

(defn- fetch-to!
  "Promise of file, fetched from url when it is not on disk yet (written whole, then renamed)."
  [url file]
  (or (get @fetching file)
      (let [tmp (str file ".part")
            p (-> (fsp/mkdir (path/dirname file) #js {:recursive true})
                  (.then #(js/fetch url #js {:signal (js/AbortSignal.timeout 30000)}))
                  (.then (fn [^js r]
                           (when-not (.-ok r) (throw (js/Error. (str "the models answered " (.-status r)))))
                           (.arrayBuffer r)))
                  (.then #(fsp/writeFile tmp (js/Buffer.from %)))
                  (.then #(fsp/rename tmp file))
                  (.then (fn [_] file))
                  (.finally #(swap! fetching dissoc file)))]
        (swap! fetching assoc file p)
        p)))

(defn serve!
  "Answers GET /anatomy/<rel> from the folder dir (fetching the file first when needed)."
  [^js res rel dir]
  (if-not (anatomy/path-ok? rel)
    (io/send-json res 404 {:error "No such anatomy file."})
    (let [file (path/join dir rel)]
      (-> (if (fs/existsSync file) (js/Promise.resolve file) (fetch-to! (anatomy/source-url rel) file))
          (.then (fn [f]
                   (.writeHead res 200 #js {"content-type" (if (.endsWith f ".json") "application/json" "model/gltf-binary")
                                            "content-length" (.-size (fs/statSync f))
                                            "cache-control" "public, max-age=31536000, immutable"})
                   (.pipe (fs/createReadStream f) res)))
          (.catch (fn [e] (when-not (.-headersSent res) (io/send-json res 502 {:error (.-message e)}))))))))
