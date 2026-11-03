(ns claudia.hub.web
  "The built page, served as plain files from HUB_WEB."
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

(def ^:private types
  {".html" "text/html; charset=utf-8"
   ".js" "text/javascript; charset=utf-8"
   ".mjs" "text/javascript; charset=utf-8"
   ".css" "text/css; charset=utf-8"
   ".json" "application/json; charset=utf-8"
   ".map" "application/json; charset=utf-8"
   ".webmanifest" "application/manifest+json"
   ".txt" "text/plain; charset=utf-8"
   ".svg" "image/svg+xml"
   ".png" "image/png"
   ".jpg" "image/jpeg"
   ".jpeg" "image/jpeg"
   ".gif" "image/gif"
   ".webp" "image/webp"
   ".avif" "image/avif"
   ".ico" "image/x-icon"
   ".woff" "font/woff"
   ".woff2" "font/woff2"
   ".ttf" "font/ttf"
   ".otf" "font/otf"
   ".wasm" "application/wasm"
   ".glb" "model/gltf-binary"})

(def ^:private kept
  "Fonts and images: no build step renames them (no hashed names in v1), but they change rarely,
  so a week in the browser's cache is fine. Everything else is checked again on every load
  (cheap: the ETag answers 304), so a page update is seen at once."
  #{".woff" ".woff2" ".ttf" ".otf" ".svg" ".png" ".jpg" ".jpeg" ".gif" ".webp" ".avif" ".ico" ".glb"})

(defn- plain [^js res status text]
  (.writeHead res status #js {"content-type" "text/plain; charset=utf-8"
                              "cache-control" "no-store"})
  (.end res text))

(defn- relative
  "The URL path as a path inside the web folder, or nil for anything that could leave it or name a
  hidden file (`..`, `.git`, a NUL)."
  [pathname]
  (let [decoded (try (js/decodeURIComponent pathname) (catch :default _ nil))
        segments (some->> decoded (#(str/split % #"/")) (remove str/blank?))]
    (when (and decoded
               (seq segments)
               (not (str/includes? decoded "\u0000"))
               (not (str/includes? decoded "\\"))
               (not-any? #(str/starts-with? % ".") segments))
      (str/join "/" segments))))

(def ^:private stale-js
  "What a page from before an update gets when it asks for a script this build does not have (the
  iPhone app's web view may keep an old index.html): a reload past any cache, once (the fresh page has
  the new scripts)."
  "if (!/[?&]fresh=/.test(location.search)) location.replace(location.pathname + (location.search ? location.search + \"&\" : \"?\") + \"fresh=\" + Date.now());")

(defn- missing [^js res rel]
  (if (and rel (re-matches #"^(?:js/)?[\w.-]+\.js$" rel))
    (do (.writeHead res 200 #js {"content-type" "text/javascript; charset=utf-8" "cache-control" "no-store"})
        (.end res stale-js))
    (plain res 404 "Not found")))

(defn- send-file [^js req ^js res file cache-control & [rel]]
  (fs/stat file
           (fn [err ^js st]
             (if (or err (not (.isFile st)))
               (missing res rel)
               (let [ext (str/lower-case (path/extname file))
                     etag (str "W/\"" (.toString (.-size st) 16) "-" (.toString (js/Math.floor (.-mtimeMs st)) 16) "\"")
                     headers #js {"content-type" (get types ext "application/octet-stream")
                                  "cache-control" cache-control
                                  "etag" etag
                                  "last-modified" (.toUTCString (.-mtime st))
                                  "x-content-type-options" "nosniff"}]
                 (if (= etag (aget (.-headers req) "if-none-match"))
                   (do (.writeHead res 304 headers) (.end res))
                   (do (aset headers "content-length" (.-size st))
                       (.writeHead res 200 headers)
                       (if (= "HEAD" (.-method req))
                         (.end res)
                         (let [^js stream (fs/createReadStream file)]
                           (.on stream "error" #(.destroy res))
                           (.pipe stream res))))))))))

(defn serve
  "GET/HEAD of the page: `/`, `/new` and `/c/…` are index.html (the page routes chats itself), anything else
  a file in the web folder."
  [root ^js req ^js res pathname]
  (cond
    (not (#{"GET" "HEAD"} (.-method req)))
    (do (.setHeader res "allow" "GET, HEAD")
        (plain res 405 "Method not allowed"))

    (or (= pathname "/") (= pathname "/new") (= pathname "/c") (str/starts-with? pathname "/c/"))
    (send-file req res (path/join root "index.html") "no-cache")

    :else
    (if-let [rel (relative pathname)]
      (let [ext (str/lower-case (path/extname rel))]
        (send-file req res (path/join root rel)
                   (if (kept ext) "public, max-age=604800" "no-cache") rel))
      (plain res 404 "Not found"))))
