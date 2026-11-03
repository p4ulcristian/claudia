(ns claudia.web.hl
  "Syntax colours for code in the picture (the files form, flow cards, git diffs): highlight.js with
  the languages files use, coloured like VS Code's Dark+ (codeforms.css). Lines come back as HTML,
  each closing what it opened, so code can be shown line by line."
  (:require ["highlight.js/lib/core" :as hljs]
            ["highlight.js/lib/languages/javascript" :as l-js]
            ["highlight.js/lib/languages/typescript" :as l-ts]
            ["highlight.js/lib/languages/clojure" :as l-clj]
            ["highlight.js/lib/languages/python" :as l-py]
            ["highlight.js/lib/languages/sql" :as l-sql]
            ["highlight.js/lib/languages/bash" :as l-bash]
            ["highlight.js/lib/languages/json" :as l-json]
            ["highlight.js/lib/languages/css" :as l-css]
            ["highlight.js/lib/languages/xml" :as l-xml]
            ["highlight.js/lib/languages/markdown" :as l-md]
            ["highlight.js/lib/languages/yaml" :as l-yaml]
            ["highlight.js/lib/languages/rust" :as l-rust]
            ["highlight.js/lib/languages/go" :as l-go]
            ["highlight.js/lib/languages/java" :as l-java]
            ["highlight.js/lib/languages/cpp" :as l-cpp]
            ["highlight.js/lib/languages/ruby" :as l-ruby]
            ["highlight.js/lib/languages/swift" :as l-swift]
            ["highlight.js/lib/languages/lua" :as l-lua]
            ["highlight.js/lib/languages/ini" :as l-ini]
            ["highlight.js/lib/languages/diff" :as l-diff]
            ["highlight.js/lib/languages/dockerfile" :as l-docker]
            [clojure.string :as str]))

(defn- lang-fn [m] (or (.-default m) m))

(defonce ^:private registered
  (do (doseq [[n m] [["javascript" l-js] ["typescript" l-ts] ["clojure" l-clj] ["python" l-py] ["sql" l-sql]
                     ["bash" l-bash] ["json" l-json] ["css" l-css] ["xml" l-xml] ["markdown" l-md] ["yaml" l-yaml]
                     ["rust" l-rust] ["go" l-go] ["java" l-java] ["cpp" l-cpp] ["ruby" l-ruby] ["swift" l-swift]
                     ["lua" l-lua] ["ini" l-ini] ["diff" l-diff] ["dockerfile" l-docker]]]
        (.registerLanguage hljs n (lang-fn m)))
      true))

(def ^:private alias
  {"js" "javascript" "mjs" "javascript" "cjs" "javascript" "jsx" "javascript"
   "ts" "typescript" "tsx" "typescript" "mts" "typescript" "cts" "typescript"
   "clj" "clojure" "cljs" "clojure" "cljc" "clojure" "edn" "clojure" "bb" "clojure"
   "py" "python" "sh" "bash" "zsh" "bash" "shell" "bash" "html" "xml" "svg" "xml" "htm" "xml"
   "md" "markdown" "yml" "yaml" "rs" "rust" "c" "cpp" "h" "cpp" "cc" "cpp" "hpp" "cpp"
   "rb" "ruby" "toml" "ini" "service" "ini" "conf" "ini" "patch" "diff" "Dockerfile" "dockerfile"})

(defn lang-of
  "highlight.js's name for a language or file extension, or nil."
  [lang]
  (when (seq lang)
    (let [l (str/lower-case lang)
          n (get alias l l)]
      (when (and registered (.getLanguage hljs n)) n))))

(defn ext-of [file]
  (let [nm (last (str/split (str file) #"/"))
        dot (str/last-index-of nm ".")]
    (cond (= nm "Dockerfile") "dockerfile"
          (and dot (pos? dot)) (subs nm (inc dot)))))

(defn- split-lines-html
  "highlight.js HTML cut at newlines; each line reopens the spans open at its start and closes them."
  [html]
  (let [tokens (re-seq #"<span[^>]*>|</span>|\n|[^<\n]+|<" html)]
    (loop [ts tokens open [] line "" out []]
      (if-let [t (first ts)]
        (cond
          (= t "\n") (recur (rest ts) open (apply str (map identity open)) (conj out (str line (apply str (repeat (count open) "</span>")))))
          (str/starts-with? t "<span") (recur (rest ts) (conj open t) (str line t) out)
          (= t "</span>") (recur (rest ts) (pop open) (str line t) out)
          :else (recur (rest ts) open (str line t) out))
        (conj out (str line (apply str (repeat (count open) "</span>"))))))))

(defn lines
  "The code's lines as HTML strings, coloured when lang is known; nil when not."
  [code lang]
  (when-let [l (lang-of lang)]
    (try (split-lines-html (.-value (.highlight hljs (str code) #js {:language l :ignoreIllegals true})))
         (catch :default _ nil))))
