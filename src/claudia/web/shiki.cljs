(ns claudia.web.shiki
  "Code colours for the code forms (files, git diffs, clojure, calls, flow cards): Shiki, VS Code's
  own grammars and its Dark+ theme. Loaded from the CDN the first time code is shown (like the
  charts' Vega), each language when first needed. Lines come back as Shiki's tokens:
  #js [#js [#js {:content :color :fontStyle}]] (fontStyle bits: 1 italic, 2 bold, 4 underline)."
  (:require [clojure.string :as str]
            [reagent.hooks :as h]))

(def ^:private theme "dark-plus")

;; Names Shiki doesn't know but files use.
(def ^:private alias {"cljs" "clojure" "cljc" "clojure" "edn" "clojure" "bb" "clojure" "mts" "ts" "cts" "ts" "h" "c"})

;; A dynamic import the compiler leaves alone.
(def ^:private import-url (js/Function. "u" "return import(u)"))

(defonce ^:private lib (atom nil))

(defn- load []
  (or @lib
      (reset! lib (-> (import-url "https://cdn.jsdelivr.net/npm/shiki@3/+esm")
                      (.then (fn [^js m]
                               (.then (.createHighlighter m #js {:themes #js [theme] :langs #js []})
                                      (fn [hl] {:hl hl :known (.-bundledLanguages m)}))))
                      (.catch (fn [e] (reset! lib nil) (throw e)))))))

(defn tokens
  "Promise of the code's lines of tokens; nil when the language is unknown (shown as plain text)."
  [code lang]
  (let [want (some-> lang str str/lower-case not-empty)]
    (if-not want
      (js/Promise.resolve nil)
      (.then (load)
             (fn [{:keys [^js hl ^js known]}]
               (let [nm (get alias want want)]
                 (when (js-in nm known)
                   (.then (if (some #{nm} (array-seq (.getLoadedLanguages hl)))
                            (js/Promise.resolve nil)
                            (.loadLanguage hl nm))
                          (fn [] (.codeToTokensBase hl (str code) #js {:lang nm :theme theme}))))))))))

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
