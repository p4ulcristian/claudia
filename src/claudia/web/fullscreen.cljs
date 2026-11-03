(ns claudia.web.fullscreen
  "An element in the browser's full screen: whether it is, turning it on and off, and hearing when
  that changes (Esc leaves it too). The clojure map and the 3D scene use it.")

(defn full?
  "Whether el is the element in full screen."
  [el]
  (boolean (and el (identical? js/document.fullscreenElement el))))

(defn toggle!
  "Puts el in full screen, or leaves full screen when el is in it. A refusal (no user gesture,
  not allowed) is ignored."
  [el]
  (when el
    (-> (if (full? el) (js/document.exitFullscreen) (.requestFullscreen el))
        (.catch (fn [_])))))

(defn on-change!
  "Calls (f) whenever an element enters or leaves full screen. -> a function that stops it."
  [f]
  (js/document.addEventListener "fullscreenchange" f)
  #(js/document.removeEventListener "fullscreenchange" f))
