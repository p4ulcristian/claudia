(ns claudia.web.copy-pill
  "The Copy pill (README decision 42): in the iPhone/iPad app the iOS edit menu is off outside text
  fields (iOS 27 opens it right under the selected text, over the lower handle), so a selection
  gets one Copy pill above it instead. It shows once the selection rests (settle-ms after its last
  change: a handle being dragged changes it all the time; iOS keeps the drag's touchend from the
  page, so touches can't tell) and copies through the app
  (`claudiaText` message handler, UIPasteboard). In a browser the iOS menu stays and so nothing is
  shown here."
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [claudia.web.views.pure.copy-pill :as pure]))

(def ^:private settle-ms "How long a selection rests before the pill shows." 350)

(defonce ^:private pill (r/atom nil)) ; nil or {:text :x :y :copied?}

(defn- handler []
  (some-> js/window (unchecked-get "webkit") (unchecked-get "messageHandlers") (unchecked-get "claudiaText")))

(defn- tell! [m] (some-> ^js (handler) (.postMessage (clj->js m)) (.catch (fn [_] nil))))

(defn- copy! [e]
  (.preventDefault e)
  (when-let [{:keys [text]} @pill]
    (-> (tell! {:op "copy" :text text})
        (.then #(swap! pill (fn [p] (when p (assoc p :copied? true)))))
        (.then #(js/setTimeout (fn [] (reset! pill nil)) 700)))))

(defn- view []
  (when-let [{:keys [x y copied?]} @pill]
    ;; pointerdown, not click: a tap must not first clear the selection it copies.
    [:button.copy-pill {:style {:left x :top y} :on-pointer-down copy!}
     (if copied? "Copied" "Copy")]))

(defn- editable? [^js node]
  (some-> (if (instance? js/Element node) node (.-parentElement node))
          (.closest "input, textarea, [contenteditable]")
          some?))

(defn- selection-now
  "The page's selection as {:text :rect}, or nil (none, empty, or in a text field)."
  []
  (let [^js s (js/window.getSelection)]
    (when (and s (pos? (.-rangeCount s)) (not (.-isCollapsed s)) (not (editable? (.-anchorNode s))))
      (let [text (str s) ^js rr (.getBoundingClientRect (.getRangeAt s 0))]
        (when (seq (.trim text))
          {:text text :rect {:left (.-left rr) :top (.-top rr) :right (.-right rr) :bottom (.-bottom rr)}})))))

(defonce ^:private timer (atom nil))

(defn- place! []
  (reset! pill (when-let [{:keys [text rect]} (selection-now)]
                 (merge {:text text} (pure/position rect js/innerWidth)))))

(defn- soon! []
  (js/clearTimeout @timer)
  (reset! pill nil)
  (reset! timer (js/setTimeout place! settle-ms)))

(defn- on-focus
  "A text field took or lost the focus: the app turns its iOS menu on there (Paste) and off again."
  [on?]
  (fn [^js e] (when (editable? (.-target e)) (tell! {:op "editing" :on on?}))))

(defonce ^:private installed (atom false))

(defn install!
  "Turns the pill on, in the app only."
  []
  (when (and (handler) (not @installed))
    (reset! installed true)
    (let [host (js/document.createElement "div")]
      (set! (.-className host) "copy-pill-root")
      (js/document.body.append host)
      (rdc/render (rdc/create-root host) [view])
      (js/document.addEventListener "selectionchange" soon!)
      (js/window.addEventListener "scroll" soon! true)
      (js/document.addEventListener "focusin" (on-focus true))
      (js/document.addEventListener "focusout" (on-focus false)))))
