(ns claudia.slave.render
  "A screenshot of a picture, so Claude sees what it drew (every draw returns one) and the page's
  camera can save it. The page's own build (out/web of this checkout) is served on a loopback port
  nobody is told about, as a render-only page (claudia.web.render); one headless Chromium, started
  on the first shot and closed after a minute without one, opens it, is handed the scene and shoots
  it. No browser or no built page: no screenshot, and the reason says why.

  A 3D scene's video (claudia.slave.video) opens the same page in a browser of its own; while it
  is made the page's server stays up and the scene's folders may be read."
  (:require ["node:http" :as http]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.node.anatomy :as anatomy]
            [claudia.node.http :as io]
            [claudia.shared.csp :as csp]
            [claudia.shared.protocol :as p]
            [claudia.slave.http :as h]
            [claudia.slave.source :as source]))

(def ^:private width 1000)
(def ^:private max-height 3200)
;; After the picture is on the page its graph layout and freeform pages still settle.
(def ^:private settle-ms 1500)
(def ^:private idle-ms 60000)
(def ^:private timeout-ms 20000)
(def ^:private system-browsers "/opt/claudia-tools/ms-playwright")
(def ^:private chromes ["/opt/google/chrome/chrome" "/usr/bin/chromium" "/usr/bin/chromium-browser"
                        "/usr/bin/google-chrome-stable" "/usr/bin/google-chrome"
                        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
                        "/Applications/Chromium.app/Contents/MacOS/Chromium"])

(def web-dir (path/join h/checkout "out" "web"))

(def ^:private anatomy-dir
  "Where the anatomy models this server's renders use are kept once fetched."
  (path/join (or (.. js/process -env -XDG_CACHE_HOME) (path/join (.. js/process -env -HOME) ".cache")) "claudia" "anatomy"))

(defn available?
  "The page is built in this checkout (the render page is one of its modes)."
  []
  (fs/existsSync (path/join web-dir "js" "main.js")))

(def ^:private fonts
  "https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:ital,wght@0,400;0,700;1,400&family=Kalam:wght@400;700&family=JetBrains+Mono:wght@400;500&display=swap")

(def ^:private page
  (str "<!doctype html><html lang=\"en\" class=\"render\"><head><meta charset=\"utf-8\">"
       "<link rel=\"stylesheet\" href=\"" fonts "\">"
       "<link rel=\"stylesheet\" href=\"/css/claudia.css\"><link rel=\"stylesheet\" href=\"/css/right.css\"><link rel=\"stylesheet\" href=\"/css/codeforms.css\">"
       "<link rel=\"stylesheet\" href=\"/css/scene3d.css\">"
       "<script>window.claudiaRender = true;</script></head>"
       "<body><div id=\"root\"></div><script src=\"/js/main.js\"></script></body></html>"))

(def ^:private types {".js" "text/javascript" ".mjs" "text/javascript" ".css" "text/css" ".svg" "image/svg+xml"
                      ".map" "application/json" ".json" "application/json" ".glb" "model/gltf-binary"
                      ".html" "text/html; charset=utf-8"})

(defn asset-path
  "The file under folder that a request path (no query) asks for, when it is one of the page's
  assets (js, css, img, person, the freeform frame), else nil."
  [folder url]
  (let [file (path/join folder (path/normalize url))]
    (when (and (str/starts-with? file (str folder "/"))
               (or (re-find #"^/(js|css|img|person)/" url) (= url (str "/" csp/frame-file))))
      file)))

(defonce ^:private shooting (atom #{})) ; the roots of the scene3d blocks in the picture being shot
(defonce ^:private held (atom {}))       ; a video being made -> its scene's roots (the server stays up)

(defn scene-roots
  "The roots of the scene3d blocks in a picture (the only folders its screenshot may read)."
  [scene]
  (set (for [b (:blocks scene) :when (and (= "form" (:kind b)) (= "scene3d" (:form b)) (string? (get-in b [:data :root])))]
         (get-in b [:data :root]))))

(defn- source-reply
  "The render page's code reads (cities, code slabs), inside a root of the picture being shot."
  [^js req ^js res]
  (let [q (.-searchParams (js/URL. (.-url req) "http://x"))
        root (or (.get q "root") "")
        rel (or (.get q "path") "")
        ref (not-empty (.get q "ref"))]
    (if (or (not (or (@shooting root) (some #(% root) (vals @held))))
            (not (source/rel-ok? rel)) (and ref (not (source/ref-ok? ref))))
      (io/send-json res 403 {:error "not a folder of this picture"})
      (-> (if (= "1" (.get q "read")) (source/text root rel ref) (source/tree root rel ref))
          (.then #(io/send-json res 200 %))
          (.catch #(io/send-json res 500 {:error (str %)}))))))

(defn- serve [^js req ^js res]
  (let [url (first (str/split (.-url req) #"\?"))
        file (asset-path web-dir url)]
    (cond
      (= url "/") (do (.writeHead res 200 #js {"content-type" "text/html; charset=utf-8"}) (.end res page))
      (= url "/api/source") (source-reply req res)
      (str/starts-with? url "/anatomy/") (anatomy/serve! res (subs url 9) anatomy-dir)
      (and file (fs/existsSync file))
      ;; frame.html with its own policy (sandboxed, inline scripts and the CDN), as the hub sends it.
      (do (.writeHead res 200 (clj->js (merge {"content-type" (get types (path/extname file) "application/octet-stream")}
                                              (when (= url (str "/" csp/frame-file)) (csp/headers csp/frame-file)))))
          (.pipe (fs/createReadStream file) res))
      :else (do (.writeHead res 404) (.end res)))))

(defonce ^:private server (atom nil))   ; Promise of the base URL
(defonce ^:private browser (atom nil))  ; Promise of the Browser
(defonce ^:private idle (atom nil))
(defonce ^:private queue (atom (js/Promise.resolve nil)))

(defn base-url
  "Promise of {:url} of the render page's server (started on first use)."
  []
  (or @server
      (reset! server
              (js/Promise.
               (fn [done fail]
                 (let [s (http/createServer serve)]
                   (.on s "error" fail)
                   (.listen s 0 "127.0.0.1"
                            #(done {:server s :url (str "http://127.0.0.1:" (.-port (.address s)) "/")}))))))))

(def ^:private software-gl ["--use-gl=angle" "--use-angle=swiftshader" "--enable-unsafe-swiftshader"])

(def ^:private gpu-gls
  "How a video's browser tries to draw, best first: the GPU through Vulkan (Linux), the platform's
  own GPU path (a Mac), then software. About ten times faster on a GPU (40 against 400 ms a 1920 x
  1080 frame, measured)."
  [["--use-angle=vulkan" "--enable-features=Vulkan" "--ignore-gpu-blocklist"]
   ["--ignore-gpu-blocklist"]
   software-gl])

(defn- launch-with
  "Promise of a headless Chromium (Playwright's, else a system Chrome or Chromium) with args."
  [args]
  (let [env (.-env js/process)
        _ (when (and (not (.-PLAYWRIGHT_BROWSERS_PATH env)) (fs/existsSync system-browsers))
            (set! (.-PLAYWRIGHT_BROWSERS_PATH env) system-browsers))
        chromium (.-chromium (js/require "playwright-core"))
        own (some #(when (and (seq %) (fs/existsSync %)) %) (cons (.-CLAUDIA_CHROME env) chromes))]
    (-> (.launch chromium #js {:args (clj->js args) :timeout timeout-ms})
        ;; Playwright's own browser is not installed here: a system Chrome or Chromium.
        (.catch (fn [e]
                  (if own
                    (.launch chromium #js {:args (clj->js args) :executablePath own :timeout timeout-ms})
                    (throw e)))))))

(defn new-browser
  "Promise of a new headless Chromium drawing WebGL in software (the screenshots: a server may have
  no GPU, and a picture is one frame)."
  []
  (launch-with software-gl))

(defn- webgl
  "Promise of what browser b draws WebGL 2 with (its renderer's name), or nil when it cannot."
  [^js b]
  (.then (.newPage b)
         (fn [^js tab]
           (-> (.evaluate tab "(() => { const c = document.createElement('canvas').getContext('webgl2'); if (!c) return null; const e = c.getExtension('WEBGL_debug_renderer_info'); return e ? c.getParameter(e.UNMASKED_RENDERER_WEBGL) : 'WebGL 2'; })()")
               (.finally #(.close tab))))))

(defn drawing-browser
  "Promise of [browser renderer]: a new headless Chromium drawing WebGL on the GPU when this machine
  lets it, else in software (a video's many frames)."
  ([] (drawing-browser gpu-gls))
  ([[args & more]]
   (-> (launch-with args)
       (.then (fn [^js b]
                (.then (webgl b) (fn [r] (if (or r (empty? more)) [b r] (do (.close b) (drawing-browser more)))))))
       (.catch (fn [e] (if (seq more) (drawing-browser more) (throw e)))))))

(defn- launch []
  (or @browser
      (let [b (.then (new-browser)
                     (fn [^js b]
                       (.on b "disconnected" #(reset! browser nil))
                       b))]
        (.catch b (fn [_] (reset! browser nil)))
        (reset! browser b))))

(defn- later []
  (some-> @idle js/clearTimeout)
  (reset! idle
          (js/setTimeout
           (fn []
             (if (seq @held)
               (later)
               (let [b @browser s @server]
                 (reset! browser nil)
                 (reset! server nil)
                 (some-> s (.then (fn [{^js listener :server}] (.close listener))))
                 (some-> b (.then (fn [^js b] (.close b))) (.catch (fn [_]))))))
           idle-ms)))

(defn hold!
  "A video is being made (k) of a scene with these folders (roots): the page's server stays up and
  the folders may be read until release!."
  [k roots]
  (swap! held assoc k roots))

(defn release!
  "The video k is made: its folders are closed again, and the server goes after a minute idle."
  [k]
  (swap! held dissoc k)
  (later))

;; Sent to the page as source: a plain function, not compiled ClojureScript.
(def ^:private draw-fn (js/Function. "a" "return window.__draw(a[0], a[1]);"))

(defn- first-line [e] (first (str/split-lines (str (or (some-> e .-message) e)))))

(defn shot-height
  "The screenshot's height for a picture height tall: at least 120, at most max-height."
  [height]
  (max 120 (min height max-height)))

(defn- shoot [scene color]
  (some-> @idle js/clearTimeout)
  (reset! shooting (scene-roots scene))
  (let [pg (atom nil)]
    (-> (js/Promise.all #js [(launch) (base-url)])
        (.then (fn [[^js b {:keys [url]}]]
                 (-> (.newPage b #js {:viewport #js {:width width :height 700}})
                     (.then (fn [^js tab]
                              (reset! pg tab)
                              (.setDefaultTimeout tab timeout-ms)
                              (-> (.goto tab url #js {:waitUntil "load"})
                                  (.then #(.waitForFunction tab "typeof window.__draw === 'function'"))
                                  (.then #(.evaluate tab draw-fn #js [(p/write-json scene) color]))
                                  (.then #(.waitForFunction tab "window.__drawn === true"))
                                  ;; 3D scenes reading real code: until they are built (or 15 s).
                                  (.then #(-> (.waitForFunction tab "!(window.__scene3dLoading > 0)" nil #js {:timeout 15000})
                                              (.catch (fn [_] nil))))
                                  (.then #(.evaluate tab "document.fonts.ready.then(() => true)"))
                                  (.then #(.waitForTimeout tab settle-ms))
                                  ;; The board as tall as the picture (capped), shot whole.
                                  (.then #(.evaluate tab "Math.ceil(document.querySelector('.render-stage').getBoundingClientRect().height)"))
                                  (.then (fn [height]
                                           (.setViewportSize tab #js {:width width :height (shot-height height)})))
                                  (.then #(.screenshot tab #js {:type "png"}))))))))
        (.then (fn [png] {:png png}))
        (.catch (fn [e] {:error (first-line e)}))
        (.finally (fn []
                    (reset! shooting #{})
                    (some-> ^js @pg .close (.catch (fn [_])))
                    (later))))))

(defn screenshot
  "Promise of {:png Buffer} or {:error text}: the scene as the page draws it (color: the chat's
  colour). One at a time: renders share one browser, and a burst of draws should not pile up pages."
  [scene color]
  (if-not (available?)
    (js/Promise.resolve {:error "this server has no built page (out/web) to draw it with"})
    (let [run (.then @queue (fn [_] (shoot scene color)))]
      (reset! queue (.catch run (fn [_] nil)))
      run)))
