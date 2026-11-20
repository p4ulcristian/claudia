(ns claudia.web.editor
  "The editor form in the picture: CodeMirror, its language grammars and the view load on first use
  (the :editor module, claudia.web.editor.view), so pictures without an editor cost nothing."
  (:require [reagent.core :as r]
            [shadow.lazy :as lazy]
            [claudia.web.shot-wait :as shot-wait]))

(def ^:private lazy-view (lazy/loadable claudia.web.editor.view/editor-form))

(defonce ^:private loaded (r/atom nil)) ; nil, :loading, :ready or the error's text

(defn- load!
  "The :editor module loaded once; a screenshot waits for it."
  []
  (reset! loaded :loading)
  (shot-wait/hold!)
  (-> (js/Promise.resolve (lazy/load lazy-view)) ; a native promise (shadow.lazy gives a goog Deferred)
      (.then #(reset! loaded :ready))
      (.catch #(reset! loaded (str (or (.-message %) %))))
      (.finally shot-wait/release!)))

(defn editor-form
  "data: the form's data; ctx: {:server :chat :block :live} (claudia.web.editor.view/editor-form)."
  [data ctx]
  (let [ok @loaded]
    (cond
      (= ok :ready) [:f> @lazy-view data ctx]
      (string? ok) [:p.f-note (str "The editor did not load: " ok)]
      :else (do (when-not ok (load!))
                [:p.f-note "Loading the editor…"]))))
