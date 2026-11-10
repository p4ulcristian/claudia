(ns claudia.web.views.frame
  "A page Claude wrote, in a sandboxed iframe (scripts run; no access to Claudia, its cookies or its
  API). The frame is frame.html, a document of its own with its own policy (claudia.shared.csp):
  it asks for the page by postMessage and writes it in. Claudia's colours and fonts go in as CSS
  variables; the frame is as tall as its page (it says its height by postMessage); `parts` are
  swapped in by postMessage without a reload; a new page is a new frame."
  (:require [clojure.string :as str]
            [reagent.hooks :as h]
            [claudia.shared.csp :as csp]))

(def ^:private theme-vars
  ["--bg" "--bg2" "--panel" "--panel2" "--fg" "--muted" "--line" "--accent" "--board"
   "--green" "--red" "--amber" "--blue" "--grey" "--violet"
   "--green-bg" "--red-bg" "--amber-bg" "--blue-bg" "--grey-bg" "--violet-bg"
   "--sans" "--mono" "--hand"])

(def fonts-url
  "https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:ital,wght@0,400;0,700;1,400&family=Kalam:wght@400;700&family=JetBrains+Mono:wght@400;500&display=swap")

;; The page tells its height (and that it is ready for parts); Claudia sends parts. The right
;; button's press goes up to Claudia (circling on the picture, claudia.web.views.circle).
(def ^:private bridge
  "<script>(()=>{
const post=()=>parent.postMessage({claudiaPageHeight:Math.ceil(Math.max(document.body.scrollHeight,document.documentElement.scrollHeight))},'*');
const put=(parts)=>{for(const [k,v] of Object.entries(parts||{}))for(const el of document.querySelectorAll('[data-part=\"'+CSS.escape(k)+'\"]'))el.innerHTML=v;post();};
let first=null;
addEventListener('message',(e)=>{if(e.source!==parent||!e.data||!e.data.claudiaParts)return;if(document.readyState==='loading')first=e.data.claudiaParts;else put(e.data.claudiaParts);});
addEventListener('DOMContentLoaded',()=>{put(first);new ResizeObserver(post).observe(document.body);parent.postMessage({claudiaReady:true},'*');});
addEventListener('load',post);
addEventListener('pointerdown',(e)=>{if(e.button!==2||e.shiftKey||e.pointerType!=='mouse')return;e.preventDefault();e.stopPropagation();parent.postMessage({claudiaChalk:'down',x:e.clientX,y:e.clientY},'*');},true);
addEventListener('contextmenu',(e)=>{if(!e.shiftKey)e.preventDefault();},true);
})();</script>")

(defn- head []
  (let [cs (js/getComputedStyle js/document.documentElement)
        vars (str/join " " (for [v theme-vars] (str v ": " (str/trim (.getPropertyValue cs v)) ";")))]
    (str "<meta charset=\"utf-8\">"
         "<link rel=\"stylesheet\" href=\"" fonts-url "\">"
         "<style>:root{" vars " color-scheme: dark;} "
         "html,body{margin:0;background:transparent;color:var(--fg);font:14px/1.5 var(--sans);} html{overflow:hidden;} "
         "*,*::before,*::after{box-sizing:border-box;} button,input,select,textarea{font:inherit;color:inherit;}</style>"
         bridge)))

(defn page-doc
  "Claude's HTML (a whole page or just its body) with Claudia's head put in."
  [html]
  (let [html (or html "")
        hd (head)]
    (cond
      (re-find #"(?i)<head[^>]*>" html) (str/replace-first html #"(?i)<head[^>]*>" #(str % hd))
      (re-find #"(?i)<html[^>]*>" html) (str/replace-first html #"(?i)<html[^>]*>" #(str % "<head>" hd "</head>"))
      :else (str "<!doctype html><html><head>" hd "</head><body>" html "</body></html>"))))

(defn- post! [^js ref msg]
  (when-let [^js f (.-current ref)]
    (some-> (.-contentWindow f) (.postMessage (clj->js msg) "*"))))

(defn- on-message
  "The handler of the frame's messages: it asks for the page (sent), is ready for parts (sent),
  tells its height (set)."
  [^js ref doc-ref send-parts set-height]
  (fn [^js e]
    (when-let [^js f (.-current ref)]
      (when (identical? (.-source e) (.-contentWindow f))
        (let [d (.-data e)
              hgt (js/Number (some-> d .-claudiaPageHeight))]
          (when (some-> d .-claudiaFrame) (post! ref {:claudiaDoc (.-current doc-ref)}))
          (when (some-> d .-claudiaReady) (send-parts))
          (when (pos? hgt) (set-height (min 20000 (max 60 hgt)))))))))

(defn- frame* [{:keys [doc parts min-height title]}]
  (let [ref (h/use-ref nil)
        [height set-height] (h/use-state (or min-height 240))
        parts-ref (h/use-ref parts)
        doc-ref (h/use-ref doc)
        send-parts #(when-let [p (.-current parts-ref)] (post! ref {:claudiaParts p}))]
    (set! (.-current parts-ref) parts)
    (set! (.-current doc-ref) doc)
    (h/use-effect
     (fn []
       (let [on (on-message ref doc-ref send-parts set-height)]
         (js/window.addEventListener "message" on)
         #(js/window.removeEventListener "message" on)))
     [])
    (h/use-effect (fn [] (send-parts) nil) [parts])
    [:iframe.page-frame {:key (hash doc)
                         :ref #(set! (.-current ref) %)
                         :sandbox "allow-scripts"
                         :src (str "/" csp/frame-file)
                         :scrolling "no"
                         :title (or title "Page")
                         :style {:height height}}]))

(defn page-frame
  "An html block's page; parts update in place."
  [html parts]
  (let [doc (h/use-memo #(page-doc html) [html])]
    [:f> frame* {:doc doc :parts parts}]))
