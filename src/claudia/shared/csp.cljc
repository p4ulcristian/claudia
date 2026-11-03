(ns claudia.shared.csp
  "The Content-Security-Policy of the page and of the frame Claude's freeform pages run in, and the
  headers a file of the page is sent with (the hub serves the page, the runner's render server the
  same files for screenshots).

  The page runs only its own scripts: everything it loads (Shiki, Vega, three.js, QuickJS) is built
  from npm into the page's own files. WebAssembly is allowed (QuickJS and Shiki's regex engine).
  Styles may be inline (CodeMirror, Vega and the markdown add them). A freeform page runs in
  frame.html, its own document from the same server: a srcdoc or blob frame would take on the
  page's policy and could not run Claude's inline scripts. frame.html's policy sandboxes it
  (an opaque origin, also when opened on its own: no cookies, no Claudia API) and allows inline
  scripts and the CDN (Chart.js, D3), as the freeform block promises Claude."
  (:require [clojure.string :as str]))

(def cdn "The one place Claude's freeform pages may load scripts and styles from." "https://cdn.jsdelivr.net")

(def ^:private fonts-css "https://fonts.googleapis.com")
(def ^:private fonts-files "https://fonts.gstatic.com")

(def frame-file "The page that holds a freeform block (in the web folder)." "frame.html")

(def page-directives
  [["default-src" "'none'"]
   ["script-src" "'self'" "'wasm-unsafe-eval'"]
   ["style-src" "'self'" "'unsafe-inline'" fonts-css]
   ["font-src" "'self'" fonts-files]
   ["img-src" "'self'" "data:" "blob:" "https:"]
   ["media-src" "'self'" "blob:"]
   ["connect-src" "'self'"]
   ["frame-src" "'self'"]
   ["manifest-src" "'self'"]
   ["base-uri" "'none'"]
   ["form-action" "'none'"]
   ["frame-ancestors" "'none'"]])

(def frame-directives
  [["sandbox" "allow-scripts"]
   ["default-src" "'none'"]
   ["script-src" "'unsafe-inline'" cdn]
   ["style-src" "'unsafe-inline'" cdn fonts-css]
   ["font-src" fonts-files cdn "data:"]
   ["img-src" "data:" "blob:" "https:"]
   ["connect-src" cdn]
   ["frame-ancestors" "'self'"]])

(defn policy
  "Directives (pairs of a name and its sources, in order) as one header value."
  [directives]
  (str/join "; " (map #(str/join " " %) directives)))

(defn headers
  "The security headers of a file of the page, by its path in the web folder (\"index.html\",
  \"frame.html\", \"js/main.js\"): frame.html may be framed by the page and is sandboxed; every
  other file has the page's policy and may not be framed."
  [rel]
  (if (= rel frame-file)
    {"content-security-policy" (policy frame-directives)
     "x-frame-options" "SAMEORIGIN"
     "x-content-type-options" "nosniff"}
    {"content-security-policy" (policy page-directives)
     "x-frame-options" "DENY"
     "x-content-type-options" "nosniff"}))
