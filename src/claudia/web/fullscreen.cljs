(ns claudia.web.fullscreen
  "An element in full screen: whether it is, turning it on and off, and hearing when that changes
  (Esc leaves it too). The clojure map and the 3D scene use it.

  Where the browser has no full screen for elements (Safari on the iPhone), the element fills the
  window instead: the class `claudia-full` fixes it over the page, and the same change event is sent.")

(def ^:private fill-class "claudia-full")

(defn- filled? [^js el] (.contains (.-classList el) fill-class))

(defn full?
  "Whether el is the element in full screen (the browser's, or filling the window)."
  [el]
  (boolean (and el (or (identical? js/document.fullscreenElement el) (filled? el)))))

(declare fill!)

(defn- on-escape [^js e]
  (when (= "Escape" (.-key e))
    (some-> (js/document.querySelector (str "." fill-class)) (fill! false))))

(defn- fill!
  "el fills the window (on) or goes back into the page; the page under it stops scrolling meanwhile."
  [^js el on]
  (.toggle (.-classList el) fill-class on)
  (.toggle (.. js/document -documentElement -classList) "claudia-full-open" on)
  (if on
    (js/document.addEventListener "keydown" on-escape)
    (js/document.removeEventListener "keydown" on-escape))
  (js/document.dispatchEvent (js/Event. "fullscreenchange")))

(defn toggle!
  "Puts el in full screen, or leaves full screen when el is in it. A refusal (no user gesture,
  not allowed) is ignored."
  [^js el]
  (when el
    (cond (filled? el) (fill! el false)
          (not js/document.fullscreenEnabled) (fill! el true)
          :else (-> (if (full? el) (js/document.exitFullscreen) (.requestFullscreen el))
                    (.catch (fn [_]))))))

(defn on-change!
  "Calls (f) whenever an element enters or leaves full screen. -> a function that stops it."
  [f]
  (js/document.addEventListener "fullscreenchange" f)
  #(js/document.removeEventListener "fullscreenchange" f))
