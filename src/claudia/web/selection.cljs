(ns claudia.web.selection
  "A text selection held in the chat column (README decision 43). While the person holds one there,
  the column does not scroll itself to its end (streamed text kept moving the text under a finger
  that was dragging a handle on an iPad); once the selection is let go, the column catches up. Each
  held selection also tells the hub's log how long it was held, how many self-scrolls it held back
  and how often the column's text changed under it, so a selection that still stalls can be told
  apart from one the page disturbed."
  (:require [claudia.web.mic :as mic]))

(defn- column-of [^js node]
  (some-> (if (instance? js/Element node) node (some-> node .-parentElement)) (.closest ".messages")))

(defn held-in
  "The chat column a non-empty selection is held in now, or nil."
  []
  (let [^js s (js/window.getSelection)]
    (when (and s (pos? (.-rangeCount s)) (not (.-isCollapsed s)))
      (column-of (.-anchorNode s)))))

(defn holds?
  "Whether a selection is held inside el."
  [^js el]
  (some-> (held-in) (identical? el)))

;; nil, or {:column :at ms :chars :held n :changes n :observer}
(defonce ^:private now-held (atom nil))
(defonce ^:private on-release (atom []))

(defn held-back!
  "The column skipped a self-scroll for a held selection; f (the latest one only) runs once it is
  let go."
  [f]
  (swap! now-held #(when % (update % :held inc)))
  (reset! on-release [f]))

(defn- begin! [^js column]
  (let [obs (js/MutationObserver. #(swap! now-held (fn [h] (when h (update h :changes + (.-length %))))))]
    (.observe obs column #js {:childList true :subtree true :characterData true})
    (reset! now-held {:column column :at (js/Date.now) :chars 0 :held 0 :changes 0 :observer obs})))

(defn report-of
  "What a let-go selection tells the hub's log."
  [{:keys [at held changes] :as h} now]
  {:event "selection" :ms (- now at) :chars (:chars h)
   :error (str "self-scrolls held back " held ", column changes " changes)})

(defn- end! []
  (when-let [h @now-held]
    (.disconnect ^js (:observer h))
    (reset! now-held nil)
    (mic/report! (report-of h (js/Date.now)))
    (let [fs @on-release]
      (reset! on-release [])
      (doseq [f fs] (f)))))

(defn- changed! []
  (let [column (held-in) h @now-held]
    (cond
      (and column (not h)) (begin! column)
      (and column h) (swap! now-held update :chars max (count (str (js/window.getSelection))))
      (and h (not column)) (end!))))

(defonce ^:private installed (atom false))

(defn install! []
  (when-not @installed
    (reset! installed true)
    (js/document.addEventListener "selectionchange" changed!)))
