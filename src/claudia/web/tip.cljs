(ns claudia.web.tip
  "A chalk speech bubble, shown at once on hover (the browser's own tooltip waits about a second):
  a chat row's full title when it is cut off, or any element's `data-tip` text (`data-tip-color` for
  its chalk). One bubble for the whole page, in its own root, so the sidebar's scrolling and
  clipping don't cut it. Mouse only."
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]))

(defonce ^:private tip (r/atom nil))

(defn- bubble []
  (when-let [{:keys [text color x y]} @tip]
    [:div.tip-bubble {:role "tooltip" :style {:left x :top y "--chat" color}}
     [:svg.tip-tail {:viewBox "0 0 12 16" :aria-hidden true} [:path {:d "M12 1 L1 8 L12 15"}]]
     text]))

(defn- target-of
  "What the pointer is over: {:el :text :color} or nil."
  [^js node]
  (when (instance? js/Element node)
    (if-let [^js el (.closest node "[data-tip]")]
      {:el el :text (.getAttribute el "data-tip") :color (.getAttribute el "data-tip-color")}
      (when-let [^js row (.closest node ".chat-row")]
        (let [^js t (.querySelector row ".t")]
          (when (and t (> (.-scrollWidth t) (.-clientWidth t)))
            {:el row :text (.-textContent t)
             :color (.trim (.getPropertyValue (.-style row) "--chat"))}))))))

(defonce ^:private installed (atom false))

(defn install! []
  (when-not @installed
    (reset! installed true)
    (let [host (js/document.createElement "div")
          hide #(reset! tip nil)]
      (set! (.-className host) "tip-root")
      (js/document.body.append host)
      (rdc/render (rdc/create-root host) [bubble])
      (js/document.addEventListener
       "pointerover"
       (fn [^js e]
         (when (= "mouse" (.-pointerType e))
           (if-let [{:keys [^js el text color]} (target-of (.-target e))]
             (when (and (seq text) (not= el (:el @tip)))
               (let [r (.getBoundingClientRect el)]
                 (reset! tip {:el el :text text :color (when (seq color) color)
                              :x (+ (.-right r) 12) :y (+ (.-top r) (/ (.-height r) 2))})))
             (hide)))))
      ;; A scrolled list or a click moves the row away from the bubble.
      (js/window.addEventListener "scroll" hide true)
      (js/window.addEventListener "mousedown" hide true)
      (js/window.addEventListener "blur" hide))))
