(ns claudia.web.hooks
  "React hooks the views share: an element's size, a media query, the time ticking, and a form's live
  poll of its server."
  (:require [reagent.hooks :as h]
            [claudia.web.api :as api]))

(defn use-key
  "Calls (on-key e) on every keydown while mounted (capture: before the page's own handlers)."
  [on-key capture deps]
  (h/use-effect
   (fn []
     (js/window.addEventListener "keydown" on-key capture)
     #(js/window.removeEventListener "keydown" on-key capture))
   deps))

(defn use-resize
  "Calls (f el) before the first paint and whenever the element in ref changes size. deps say when
  to look for the element again, e.g. after it first mounts."
  [ref f deps]
  (h/use-layout-effect
   (fn []
     (when-let [el (.-current ref)]
       (let [ro (js/ResizeObserver. #(f el))]
         (f el)
         (.observe ro el)
         #(.disconnect ro))))
   deps))

(defn use-media
  "Whether the media query matches, kept up to date."
  [q]
  (let [[on set-on] (h/use-state #(.-matches (js/matchMedia q)))]
    (h/use-effect
     (fn []
       (let [m (js/matchMedia q)
             f #(set-on (.-matches m))]
         (.addEventListener m "change" f)
         #(.removeEventListener m "change" f)))
     [q])
    on))

(defn use-width
  "The element's width in px (clientWidth), kept up to date; 0 until it is measured."
  [ref deps]
  (let [[width set-width] (h/use-state 0)]
    (use-resize ref #(set-width (.-clientWidth %)) deps)
    width))

(defn use-now
  "The time now (ms), new every second: for countdowns and ages."
  []
  (let [[now set-now] (h/use-state (js/Date.now))]
    (h/use-effect (fn [] (let [t (js/setInterval #(set-now (js/Date.now)) 1000)] #(js/clearInterval t))) [])
    now))

(defn- network-blip?
  "A fetch that failed before any answer (offline, the server restarting): not shown as an error."
  [e]
  (instance? js/TypeError e))

(defn use-poll
  "While on?, asks (url-of fp) every ms milliseconds the page is seen, fp being the fingerprint of
  the last answer (nil at first). An answer {:same true} means nothing changed; any other answer
  goes to on-answer. Returns the last error's message (nil after an answer). A deps change starts
  over with no fingerprint."
  [{:keys [on? url-of ms on-answer]} deps]
  (let [[error set-error] (h/use-state nil)]
    (h/use-effect
     (fn []
       (when on?
         (let [gone (atom false) timer (atom nil) fp (atom nil)
               answered (fn [j]
                          (when-not @gone
                            (set-error nil)
                            (when-not (:same j) (reset! fp (:fp j)) (on-answer j))))
               failed (fn [e]
                        (when-not (or @gone (network-blip? e)) (set-error (.-message e))))
               ask (fn ask []
                     (-> (if js/document.hidden
                           (js/Promise.resolve nil)
                           (-> (api/fetch-json (url-of @fp)) (.then answered) (.catch failed)))
                         (.then (fn [_] (when-not @gone (reset! timer (js/setTimeout ask ms)))))))]
           (ask)
           #(do (reset! gone true) (js/clearTimeout @timer)))))
     deps)
    error))
