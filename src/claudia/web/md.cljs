(ns claudia.web.md
  "Markdown to safe HTML: marked renders, DOMPurify cleans."
  (:require ["marked" :refer [Marked]]
            ["dompurify" :as DOMPurify]
            ["highlight.js/lib/core" :as hljs]
            [claudia.web.hl :as hl]))

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
                     ;; Coloured as the TypeScript Claudia did (github-dark, claudia.css): by its
                     ;; language when known, else guessed.
                     (let [text (.-text tok)
                           lang (hl/lang-of (.-lang tok))
                           html (try (.-value (if lang
                                                (.highlight hljs text #js {:language lang :ignoreIllegals true})
                                                (.highlightAuto hljs text)))
                                     (catch :default _ (escape text)))]
                       (str "<div class=\"code\"><button class=\"copy\" type=\"button\" title=\"Copy\">Copy</button>"
                            "<pre><code class=\"hljs\">" html "</code></pre></div>")))
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
