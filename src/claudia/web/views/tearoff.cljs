(ns claudia.web.views.tearoff
  "A chat torn out of the rail (README decision 81): dragged with a mouse and let go outside the rail
  (over the page or outside the window; claudia.web.views.chat-drag decides), the chat opens in a new
  window of its own (its address, /c/<server>/<chat>), as big as this one. When it was the chat open
  here, this window moves on to a chat no other window shows (or a new chat). The windows know each
  other (claudia.web.tabs)."
  (:require [re-frame.core :as rf]
            [claudia.web.fx :as fx]))

;; ---- pure ----

(defn chat-url
  "The chat k's address on this origin."
  [origin k]
  (str origin (fx/path-of k)))

(defn features
  "window.open features: a popup (an app window, no tabs) as big as w × h."
  [w h]
  (str "popup,width=" (js/Math.round w) ",height=" (js/Math.round h)))

(defn next-free
  "The first chat of rows (maps with :server :id) that is not torn (k) and no other window shows
  (elsewhere: [server chat] keys); nil when there is none."
  [rows elsewhere k]
  (some (fn [{:keys [server id]}]
          (let [r [server id]] (when-not (or (= r k) (contains? elsewhere r)) r)))
        rows))

;; ---- the page ----

(defn- move-on!
  "After chat k is torn out: when it was open here (open), open the first chat of rows no other
  window shows, or a new chat."
  [k open rows elsewhere]
  (when (= k open)
    (rf/dispatch (if-let [n (next-free rows elsewhere k)] [:nav/open n] [:nav/new]))))

(defn tear-fn
  "What tears chat k out: it opens in a window of its own and, when that window opened (a blocked
  popup leaves everything as it was), this window moves on (open, rows, elsewhere: as move-on!)."
  [k open rows elsewhere]
  (fn []
    (when (js/window.open (chat-url js/location.origin k) "_blank" (features js/window.innerWidth js/window.innerHeight))
      (move-on! k open rows elsewhere))))
