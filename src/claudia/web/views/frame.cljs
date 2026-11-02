(ns claudia.web.views.frame
  "A page Claude wrote, in a sandboxed iframe (scripts run; no access to Claudia, its cookies or its
  API). Claudia's colours and fonts go in as CSS variables; the frame is as tall as its page (it
  says its height by postMessage); `parts` are swapped in by postMessage without a reload."
  (:require [clojure.string :as str]
            [reagent.hooks :as h]))

(def ^:private theme-vars
  ["--bg" "--bg2" "--panel" "--panel2" "--fg" "--muted" "--line" "--accent" "--board"
   "--green" "--red" "--amber" "--blue" "--grey" "--violet"
   "--green-bg" "--red-bg" "--amber-bg" "--blue-bg" "--grey-bg" "--violet-bg"
   "--sans" "--mono" "--hand"])

(def fonts-url
  "https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:ital,wght@0,400;0,700;1,400&family=Kalam:wght@400;700&family=JetBrains+Mono:wght@400;500&display=swap")

(def ^:private csp
  (str "default-src 'none'; "
       "script-src 'unsafe-inline' https://cdn.jsdelivr.net; "
       "style-src 'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com; "
       "font-src https://fonts.gstatic.com https://cdn.jsdelivr.net data:; "
       "img-src data: blob: https:; "
       "connect-src https://cdn.jsdelivr.net"))

;; The page tells its height (and that it is ready for parts); Claudia sends parts.
(def ^:private bridge
  "<script>(()=>{
const post=()=>parent.postMessage({claudiaHeight:Math.ceil(Math.max(document.body.scrollHeight,document.documentElement.scrollHeight))},'*');
const put=(parts)=>{for(const [k,v] of Object.entries(parts||{}))for(const el of document.querySelectorAll('[data-part=\"'+CSS.escape(k)+'\"]'))el.innerHTML=v;post();};
let first=null;
addEventListener('message',(e)=>{if(e.source!==parent||!e.data||!e.data.claudiaParts)return;if(document.readyState==='loading')first=e.data.claudiaParts;else put(e.data.claudiaParts);});
addEventListener('DOMContentLoaded',()=>{put(first);new ResizeObserver(post).observe(document.body);parent.postMessage({claudiaReady:true},'*');});
addEventListener('load',post);
})();</script>")

(defn- head []
  (let [cs (js/getComputedStyle js/document.documentElement)
        vars (str/join " " (for [v theme-vars] (str v ": " (str/trim (.getPropertyValue cs v)) ";")))]
    (str "<meta charset=\"utf-8\">"
         "<meta http-equiv=\"Content-Security-Policy\" content=\"" csp "\">"
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

(defn- frame* [{:keys [doc parts min-height title]}]
  (let [ref (h/use-ref nil)
        [height set-height] (h/use-state (or min-height 120))
        parts-ref (h/use-ref parts)
        send-parts (fn []
                     (when-let [^js f (.-current ref)]
                       (when-let [p (.-current parts-ref)]
                         (some-> (.-contentWindow f) (.postMessage #js {:claudiaParts (clj->js p)} "*")))))]
    (set! (.-current parts-ref) parts)
    (h/use-effect
     (fn []
       (let [on (fn [^js e]
                  (when-let [^js f (.-current ref)]
                    (when (identical? (.-source e) (.-contentWindow f))
                      (let [d (.-data e)]
                        (when (some-> d .-claudiaReady) (send-parts))
                        (let [hgt (js/Number (some-> d .-claudiaHeight))]
                          (when (pos? hgt) (set-height (min 20000 (max 40 hgt)))))))))]
         (js/window.addEventListener "message" on)
         #(js/window.removeEventListener "message" on)))
     [])
    (h/use-effect (fn [] (send-parts) nil) [parts])
    [:iframe.page-frame {:ref #(set! (.-current ref) %)
                         :sandbox "allow-scripts"
                         :srcDoc doc
                         :scrolling "no"
                         :title (or title "Page")
                         :style {:height height}}]))

(defn page-frame
  "An html block's page; parts update in place."
  [html parts]
  (let [doc (h/use-memo #(page-doc html) [html])]
    [:f> frame* {:doc doc :parts parts}]))

;; ---- chart: Vega-Lite inside its own sandboxed page, loaded from jsdelivr ----

(defn- chart-page [data]
  (let [spec-data (js/JSON.stringify (clj->js data))
        ;; Never let the data close the script element.
        spec-data (str/replace spec-data "</" "<\\/")]
    (str "<div id=\"c\" style=\"width:100%\"></div>"
         "<script src=\"https://cdn.jsdelivr.net/npm/vega@5\"></script>"
         "<script src=\"https://cdn.jsdelivr.net/npm/vega-lite@5\"></script>"
         "<script src=\"https://cdn.jsdelivr.net/npm/vega-embed@6\"></script>"
         ;; The interpreter runs Vega's expressions without eval, so the page's CSP stays strict.
         "<script src=\"https://cdn.jsdelivr.net/npm/vega-interpreter@1\"></script>"
         "<script>(()=>{const data=" spec-data ";
const cs=getComputedStyle(document.documentElement);const v=(n)=>cs.getPropertyValue(n).trim();
const fg=v('--fg'),muted=v('--muted'),line=v('--line');
const palette=['--blue','--amber','--green','--violet','--red','--grey'].map(v);
const x=data.x||'x',y=data.y||'y',mark=data.mark||'bar';
const values=(Array.isArray(data.rows)?data.rows:[]).map(({id,...r})=>r);
const numericX=values.length>0&&values.every((r)=>typeof r[x]==='number');
const spec={$schema:'https://vega.github.io/schema/vega-lite/v5.json',width:'container',height:220,background:'transparent',
...(data.title?{title:data.title}:{}),data:{values},
mark:{type:mark,tooltip:true,...(mark==='line'||mark==='area'?{point:true,strokeWidth:3}:mark==='bar'?{cornerRadiusEnd:3}:{})},
encoding:{x:{field:x,type:numericX?'quantitative':'ordinal',sort:null,axis:{labelAngle:0}},y:{field:y,type:'quantitative'},
...(data.color?{color:{field:data.color,type:'nominal'}}:{color:{value:palette[0]}})},
config:{font:'Atkinson Hyperlegible',view:{stroke:null},range:{category:palette},
title:{font:'Kalam',fontSize:18,fontWeight:700,color:fg,anchor:'start'},
axis:{labelColor:muted,titleColor:muted,domainColor:line,tickColor:line,gridColor:line,gridOpacity:0.5,labelFontSize:12,titleFont:'Kalam',titleFontSize:14,titleFontWeight:700},
legend:{labelColor:muted,titleColor:muted}}};
const show=(t)=>{document.getElementById('c').textContent=t;};
if(!window.vegaEmbed){show('Chart failed: Vega did not load.');return;}
document.fonts.ready.then(()=>vegaEmbed('#c',spec,{actions:false,renderer:'svg',ast:true,expr:vega.expressionInterpreter})).catch((e)=>show('Chart failed: '+(e.message||e)));
})();</script>")))

(defn chart-frame [data]
  (let [doc (h/use-memo #(page-doc (chart-page data)) [data])]
    [:f> frame* {:doc doc :min-height 250 :title "Chart"}]))
