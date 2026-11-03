(ns claudia.web.shiki
  "Code colours for the code forms (files, git diffs, clojure, calls, flow cards): Shiki, VS Code's
  own grammars and its Dark+ theme, built from npm into the page's own files. Shiki loads the first
  time code is shown (the :shiki module), each language's grammar when first needed (Shiki's grammar
  files in js/shiki-langs/, with index.json naming the file of every language name and alias).
  Lines come back as Shiki's tokens: #js [#js [#js {:content :color :fontStyle}]] (fontStyle bits:
  1 italic, 2 bold, 4 underline)."
  (:require [clojure.string :as str]
            [reagent.hooks :as h]
            [shadow.esm :as esm]
            [shadow.loader :as loader]
            [claudia.web.api :as api]))

(def ^:private theme "dark-plus")

(def ^:private langs-dir "/js/shiki-langs/")

;; Names Shiki doesn't know but files use.
(def ^:private alias {"cljs" "clojure" "cljc" "clojure" "edn" "clojure" "bb" "clojure" "mts" "ts" "cts" "ts" "h" "c"})

(defonce ^:private loaded (atom nil)) ; Promise of {:hl highlighter :index name -> grammar file}

;; The :shiki module hands its highlighter maker over as it loads (claudia.web.shiki.lib), rather
;; than shadow.lazy naming it here: the code views using this namespace are also in the test build,
;; which has no modules.
(defonce ^:private provided (atom nil))

(defn provide!
  "Called once by the :shiki module as it loads: its function making a highlighter."
  [make-highlighter]
  (reset! provided make-highlighter))

(defn- highlighter
  "Promise of a highlighter, the :shiki module loaded first."
  []
  (-> (js/Promise. (fn [ok fail] (.addCallbacks (loader/load "shiki") ok fail)))
      (.then (fn [_] (@provided)))))

(defn lang-file
  "The grammar file (without .mjs) for a language name, in any case, one of Shiki's aliases or one of
  `alias`; nil when Shiki has none. index: every name Shiki knows (keywords) -> its file's name."
  [index lang]
  (when-let [want (some-> lang str str/lower-case not-empty)]
    (get index (keyword (get alias want want)))))

(defn- load []
  (or @loaded
      (reset! loaded (-> (js/Promise.all #js [(highlighter) (api/fetch-json (str langs-dir "index.json"))])
                      (.then (fn [[hl index]] {:hl hl :index index}))
                      (.catch (fn [e] (reset! loaded nil) (throw e)))))))

(defn- load-grammar
  "Promise that the grammar file is in the highlighter."
  [^js hl file]
  (if (some #{file} (array-seq (.getLoadedLanguages hl)))
    (js/Promise.resolve nil)
    (.then (esm/dynamic-import (str langs-dir file ".mjs"))
           (fn [^js m] (.loadLanguage hl (.-default m))))))

(defn tokens
  "Promise of the code's lines of tokens; nil when the language is unknown (shown as plain text)."
  [code lang]
  (if-not (some-> lang str not-empty)
    (js/Promise.resolve nil)
    (.then (load)
           (fn [{:keys [^js hl index]}]
             (when-let [file (lang-file index lang)]
               (.then (load-grammar hl file)
                      (fn [] (.codeToTokensBase hl (str code) #js {:lang file :theme theme}))))))))

(defn use-tokens
  "The code's tokens once Shiki has them (nil before, and for an unknown language). fresh: start
  over from plain text whenever the code changes (else the old colours stay until the new come)."
  ([code lang] (use-tokens code lang true))
  ([code lang fresh]
   (let [[t set-t] (h/use-state nil)]
     (h/use-effect
      (fn []
        (let [gone (atom false)]
          (when fresh (set-t nil))
          (-> (tokens code lang)
              (.then #(when-not @gone (set-t %)))
              (.catch (fn [_])))
          #(reset! gone true)))
      [code lang])
     t)))

(defn line
  "Line i's tokens (a JS array), or nil."
  [^js ts i]
  (when (and ts (< i (.-length ts))) (aget ts i)))

(defn font-style
  "The style of a token's fontStyle bits (nil for none)."
  [f]
  (when (and f (not (zero? f)))
    (cond-> {}
      (pos? (bit-and f 1)) (assoc :font-style "italic")
      (pos? (bit-and f 2)) (assoc :font-weight 700)
      (pos? (bit-and f 4)) (assoc :text-decoration "underline"))))
