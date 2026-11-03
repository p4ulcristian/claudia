(ns claudia.web.views.snapshot
  "The camera in the bar: the picture shown, as a PNG, copied to the clipboard or saved as a file.
  The runner takes it with the same headless browser that shows Claude its drawings
  (GET /api/chats/:id/picture.png?turn=), so it is the whole picture at its full height."
  (:require [reagent.core :as r]))

(defn- file-name [title]
  (let [t (-> (str title)
              (.replace (js/RegExp. "[^\\p{L}\\p{N}]+" "gu") "-")
              (.replace (js/RegExp. "^-+|-+$" "g") ""))]
    (str (if (seq t) t "picture") ".png")))

(defn- png-url [{:keys [server chat]} {:keys [pictures index viewing?]}]
  (let [turn (when viewing? (:turn (get pictures index)))]
    (str "/s/" (js/encodeURIComponent server) "/api/chats/" (js/encodeURIComponent chat) "/picture.png"
         (when turn (str "?turn=" (js/encodeURIComponent turn))))))

(defn- fetch-png
  "Promise of the PNG blob; an error carries the runner's reason."
  [url]
  (-> (js/fetch url #js {:credentials "same-origin"})
      (.then (fn [^js res]
               (if (.-ok res)
                 (.blob res)
                 (-> (.json res) (.catch (fn [_] #js {}))
                     (.then (fn [^js j] (throw (js/Error. (or (.-error j) (str "HTTP " (.-status res)))))))))))))

(defn- flash! [state v]
  (reset! state v)
  (js/setTimeout #(when (= @state v) (reset! state nil)) (if (string? v) 6000 1500)))

(defn- save! [state oc hist]
  (reset! state :busy)
  (-> (fetch-png (png-url oc hist))
      (.then (fn [blob]
               (let [href (js/URL.createObjectURL blob)
                     a (js/document.createElement "a")]
                 (set! (.-href a) href)
                 (set! (.-download a) (file-name (:title hist)))
                 (.click a)
                 (js/setTimeout #(js/URL.revokeObjectURL href) 10000)
                 (flash! state :done))))
      (.catch (fn [e] (flash! state (str "No snapshot: " (.-message e)))))))

(defn- copy! [state oc hist]
  (reset! state :busy)
  ;; Written in the click itself, the item holding the promise of the image (Safari wants it so).
  (-> (if (and js/navigator.clipboard (exists? js/ClipboardItem))
        (try (.write js/navigator.clipboard
                     #js [(js/ClipboardItem. #js {"image/png" (fetch-png (png-url oc hist))})])
             (catch :default e (js/Promise.reject e)))
        (js/Promise.reject (js/Error. "this browser can't copy images")))
      (.then #(flash! state :done))
      (.catch (fn [e] (flash! state (str "Not copied: " (.-message e)))))))

(defn button [_oc _hist]
  (let [state (r/atom nil) ; nil | :busy | :done | error text
        menu? (r/atom false)]
    (fn [oc hist]
      (let [s @state]
        [:span.snap-wrap
         [:button.rbar-snap {:title (if (string? s) s "Snapshot of the picture (PNG)")
                             :aria-label "Snapshot of the picture"
                             :class (cond (= :busy s) "busy" (= :done s) "done" (string? s) "failed")
                             :disabled (= :busy s)
                             :on-click #(swap! menu? not)}
          [:svg {:viewBox "0 0 24 24"}
           [:path {:d "M4 8h3l2-3h6l2 3h3v11H4z"}]
           [:circle {:cx 12 :cy 13 :r 3.5}]]]
         (when @menu?
           [:div.snap-menu {:on-mouse-leave #(reset! menu? false)}
            [:button {:on-click #(do (reset! menu? false) (copy! state oc hist))} "Copy image"]
            [:button {:on-click #(do (reset! menu? false) (save! state oc hist))} "Save as PNG"]])]))))
