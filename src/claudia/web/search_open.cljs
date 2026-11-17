(ns claudia.web.search-open
  "Opening the search panel (claudia.web.views.search-panel) from a button, with the keyboard up on
  an iPhone or iPad. Apart from the panel, so the list, the rail and the edge can open it without
  depending on what the panel draws with."
  (:require [re-frame.core :as rf]))

(defonce ^:private stand-in (atom nil))

(defn open!
  "Opens the panel from a tap, keyboard and all: iOS raises the keyboard only for a field focused
  inside the tap itself, and the panel's field is drawn after it; so a stand-in field takes the
  focus now and hands it on when the real one is there (iOS keeps the keyboard up across)."
  []
  (let [^js el (js/document.createElement "input")]
    (set! (.-className el) "sp-stand-in")
    (.setAttribute el "aria-hidden" "true")
    (js/document.body.append el)
    (.focus el)
    (reset! stand-in el))
  (rf/dispatch [:search/open]))

(defn take-focus!
  "The panel's field takes the focus from the stand-in."
  [^js input]
  (some-> input .focus)
  (some-> ^js @stand-in .remove)
  (reset! stand-in nil))

