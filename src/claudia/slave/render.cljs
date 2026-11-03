(ns claudia.slave.render
  "A screenshot of a picture, so Claude sees what it drew (every draw returns one) and the page's
  camera can save it. The page's own build (out/web of this checkout) is served on a loopback port
  nobody is told about, as a render-only page (claudia.web.render); one headless Chromium, started
  on the first shot and closed after a minute without one, opens it, is handed the scene and shoots
  it. No browser or no built page: no screenshot, and the reason says why."
  (:require ["node:http" :as http]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.shared.csp :as csp]
            [claudia.shared.protocol :as p]
            [claudia.slave.http :as h]))

(def ^:private width 1000)
(def ^:private max-height 3200)
;; After the picture is on the page its graph layout and freeform pages still settle.
(def ^:private settle-ms 1500)
(def ^:private idle-ms 60000)
(def ^:private timeout-ms 20000)
(def ^:private system-browsers "/opt/claudia-tools/ms-playwright")
(def ^:private chromes ["/opt/google/chrome/chrome" "/usr/bin/chromium" "/usr/bin/chromium-browser"
                        "/usr/bin/google-chrome-stable" "/usr/bin/google-chrome"])

(def web-dir (path/join h/checkout "out" "web"))

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

(defn- serve [^js req ^js res]
  (let [url (first (str/split (.-url req) #"\?"))
        file (asset-path web-dir url)]
    (cond
      (= url "/") (do (.writeHead res 200 #js {"content-type" "text/html; charset=utf-8"}) (.end res page))
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

(defn- base-url []
  (or @server
      (reset! server
              (js/Promise.
               (fn [done fail]
                 (let [s (http/createServer serve)]
                   (.on s "error" fail)
                   (.listen s 0 "127.0.0.1"
                            #(done {:server s :url (str "http://127.0.0.1:" (.-port (.address s)) "/")}))))))))

(defn- launch []
  (or @browser
      (let [env (.-env js/process)
            _ (when (and (not (.-PLAYWRIGHT_BROWSERS_PATH env)) (fs/existsSync system-browsers))
                (set! (.-PLAYWRIGHT_BROWSERS_PATH env) system-browsers))
            chromium (.-chromium (js/require "playwright-core"))
            ;; Software GL: a server has no GPU for a headless browser.
            args #js ["--use-gl=angle" "--use-angle=swiftshader" "--enable-unsafe-swiftshader"]
            own (some #(when (and (seq %) (fs/existsSync %)) %) (cons (.-CLAUDIA_CHROME env) chromes))
            b (-> (.launch chromium #js {:args args})
                  ;; Playwright's own browser is not installed here: a system Chrome or Chromium.
                  (.catch (fn [e]
                            (if own
                              (.launch chromium #js {:args args :executablePath own})
                              (throw e))))
                  (.then (fn [^js b]
                           (.on b "disconnected" #(reset! browser nil))
                           b)))]
        (.catch b (fn [_] (reset! browser nil)))
        (reset! browser b))))

(defn- later []
  (some-> @idle js/clearTimeout)
  (reset! idle
          (js/setTimeout
           (fn []
             (let [b @browser s @server]
               (reset! browser nil)
               (reset! server nil)
               (some-> s (.then (fn [{^js listener :server}] (.close listener))))
               (some-> b (.then (fn [^js b] (.close b))) (.catch (fn [_])))))
           idle-ms)))

;; Sent to the page as source: a plain function, not compiled ClojureScript.
(def ^:private draw-fn (js/Function. "a" "return window.__draw(a[0], a[1]);"))

(defn- first-line [e] (first (str/split-lines (str (or (some-> e .-message) e)))))

(defn shot-height
  "The screenshot's height for a picture height tall: at least 120, at most max-height."
  [height]
  (max 120 (min height max-height)))

(defn- shoot [scene color]
  (some-> @idle js/clearTimeout)
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
