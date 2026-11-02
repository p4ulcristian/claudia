(ns claudia.web.md
  "Markdown to safe HTML: marked renders, DOMPurify cleans."
  (:require ["marked" :refer [Marked]]
            ["dompurify" :as DOMPurify]))

(defn- escape [s]
  (-> (str s)
      (.replaceAll "&" "&amp;")
      (.replaceAll "<" "&lt;")
      (.replaceAll ">" "&gt;")
      (.replaceAll "\"" "&quot;")))

(def ^:private marked
  (Marked.
   #js {:gfm true
        :breaks false
        :renderer
        #js {:code (fn [^js tok]
                     (str "<div class=\"code\"><button class=\"copy\" type=\"button\">Copy</button>"
                          "<pre><code>" (escape (.-text tok)) "</code></pre></div>"))
             :link (fn [^js tok]
                     (this-as ^js this
                              (str "<a href=\"" (escape (.-href tok)) "\" target=\"_blank\" rel=\"noreferrer\">"
                                   (.parseInline (.-parser this) (.-tokens tok)) "</a>")))}}))

(defn html
  "Markdown text -> sanitized HTML string."
  [text]
  (.sanitize DOMPurify (.parse marked (or text "") #js {:async false})
             #js {:ADD_ATTR #js ["target"]}))

(defn install-copy!
  "One listener for every code block's copy button."
  []
  (js/document.addEventListener
   "click"
   (fn [^js e]
     (when-let [btn (some-> (.-target e) (.closest ".code .copy"))]
       (let [code (or (some-> (.-parentElement btn) (.querySelector "code") .-textContent) "")]
         (-> (js/navigator.clipboard.writeText code)
             (.then (fn []
                      (set! (.-textContent btn) "Copied")
                      (js/setTimeout #(set! (.-textContent btn) "Copy") 1200)))))))))
