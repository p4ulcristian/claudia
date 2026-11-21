(ns claudia.web.views.head
  "The chat's header (README decisions 22, 90, 117): one panel lightly tinted with the chat's colour,
  two rows, the same for a new chat and a started one. Row 1: the title (a click renames it), the
  server's state when it is off or unreached, and on the right Sudo (its owner, a started chat).
  Row 2: where the chat works (one pill: the project's logo, server › project,
  claudia.web.views.head-place), the voice that reads its replies (decision 21), the notify sound
  (claudia.web.views.notify), and on the right the chat's colour, a filled circle. Model · effort,
  Compact and the subscription sit in the strip over the message box (claudia.web.views.chat-strip);
  Files, System, Fork and Archive on the right rail (claudia.web.views.thumb-edge), the prompt
  profile in the rail's Profiles. A new chat picks all of them before its first message (they go
  with `new`); once it has started a server pick moves it (decision 82) and every other pick
  changes the chat."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.shared.colours :as colours-pure]
            [claudia.shared.sounds :as sounds]
            [claudia.web.views.chat-context :as chat-context]
            [claudia.web.views.head-pill :as hp]
            [claudia.web.views.head-place :as place]
            [claudia.web.views.notify :as notify]
            [claudia.web.views.talk :as talk]
            [claudia.web.views.pure.chat :as pure]
            [claudia.web.views.pure.words :as words]))

(def ^:private colours words/colours-with-ash)

;; ---- small parts ----

(defn archive-icon
  "A box with a lid; the arrow points out of it for Unarchive."
  [out?]
  [:svg {:width 16 :height 16 :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width 1.5
         :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden true}
   [:rect {:x 1.5 :y 2.5 :width 13 :height 3.5 :rx 0.8}]
   [:path {:d "M2.8 6v6.5a1 1 0 0 0 1 1h8.4a1 1 0 0 0 1-1V6"}]
   (if out? [:path {:d "M8 12V8.2M6.3 9.8 8 8.1l1.7 1.7"}] [:path {:d "M6.3 9h3.4"}])])

;; ---- row 1: the title ----

(defn- title-edit [title rename! cancel!]
  [:input.title-edit {:default-value title :auto-focus true
                      :on-blur #(rename! (.. % -target -value))
                      :on-key-down #(case (.-key %)
                                      "Enter" (rename! (.. % -currentTarget -value))
                                      "Escape" (cancel!)
                                      nil)}])

(defn- title-line
  "The title; with edit! (a renamable chat) a click on it renames."
  [title edit!]
  [:h1 {:class (when edit! "renamable") :title (when edit! "Click to rename") :on-click edit!}
   [:span.t title]])

(def ^:private state-paths
  {:off ["M12 3v8" "M6.5 6.5a8 8 0 1 0 11 0"]
   :reconnecting ["M20 12a8 8 0 0 0-14-5.3L4 9" "M4 4v5h5" "M4 12a8 8 0 0 0 14 5.3l2-2.3" "M20 20v-5h-5"]
   :offline ["M3 3l18 18" "M8.5 16.4a5 5 0 0 1 7 0" "M4.9 12.8a10 10 0 0 1 4.6-2.6" "M19.1 12.8a10 10 0 0 0-2.7-1.9" "M12 20h.01"]})

(def ^:private state-tips {:offline "no network" :reconnecting "reconnecting…"})

(defn- state-icon
  "Whether the server is there: a power sign when it is off, a struck-through wave when the device
  has no network, turning arrows while the page reconnects, nothing when it is connected."
  [status label]
  (when-let [s (pure/server-state status)]
    (let [tip (if (= :off s) (str label " is off") (state-tips s))]
      [:span.head-state {:class (name s) :role "img" :aria-label tip :data-tip tip}
       [:svg {:viewBox "0 0 24 24" :aria-hidden true}
        (for [d (state-paths s)] ^{:key d} [:path {:d d}])]])))

(defn- head-names
  "The title (a click renames a started chat) and the server's state."
  [{:keys [ck server row state status ro]}]
  (let [[editing set-editing] (h/use-state false)
        title (or (:title row) (:title state) "New chat")
        rename! (fn [typed]
                  (set-editing false)
                  (when-let [t (and row (pure/new-title typed title))] (rf/dispatch [:chat/rename ck t])))]
    [:div.head-title
     (if editing
       (title-edit title rename! #(set-editing false))
       (title-line title (when (and row (not ro)) #(set-editing true))))
     (state-icon (or status @(rf/subscribe [:server-status server])) @(rf/subscribe [:server-label server]))]))

;; ---- row 2: where, voice, notify; the colour on the right ----

(defn- swatch [c color pick taken]
  (let [off? (and (taken c) (not= c color))
        n (colours-pure/name-of c)]
    [:button.swatch {:class [(when (= c color) "on") (when (= c "ash") "passive") (when off? "taken")]
                     :style {:background (words/colour-fill c)} :disabled off? :aria-label n
                     :data-tip (cond (= c "ash") "Grey: passive, sinks to the bottom of the rail"
                                     off? (str n ": another chat wears it")
                                     :else n)
                     :on-click #(pick c)}]))

(defn- palette
  "The solid colours and ash, then the gradients on rows of their own (README decision 80); the ones
  another open chat wears struck through (decision 26). k: the chat ([server id]; nil: the new one)."
  [k color pick]
  (let [taken @(rf/subscribe [:taken-colours k])]
    [:div.palette {:role "menu"}
     (for [c colours] ^{:key c} [swatch c color pick taken])
     [:span.swatch-break]
     (for [c words/gradient-colours] ^{:key c} [swatch c color pick taken])]))

(defn- voice-pick
  "The voice that reads the chat's replies aloud, Off by default (README decision 21); only when
  the hub has a voice."
  [{:keys [new? ck ro]}]
  (let [k (when-not new? ck)
        picked @(rf/subscribe [:voice k])]
    (when (talk/use-voice?)
      [:f> hp/popped {:k :voice :label "Voice" :value (talk/voice-name picked) :ro ro
                   :class (when (seq picked) "on")}
       (fn [close!] [talk/voice-pop k picked close!])])))

(defn- notify-pick
  "When the chat finishes or waits on you: its sound (README decision 90), or Off (its bell off: no
  chime, popup or phone push, decision 21)."
  [{:keys [new? ck ro color]}]
  (let [k (when-not new? ck)
        picked @(rf/subscribe [:notify k])]
    [:f> hp/popped {:k (if (seq picked) :bell :bell-off) :label "Notify" :value (sounds/notify-name picked) :ro ro}
     (fn [close!] [notify/notify-pop k picked color close!])]))

(defn- colour-pick
  "The chat's colour: a filled circle alone, on the right of row 2 (README decisions 89, 117); the
  popup is the palette (a new chat's pick goes with `new`)."
  [{:keys [new? ck ro color put!]}]
  (let [c (or color "ash")]
    [:f> hp/popped {:dot c :label (str "Colour: " (colours-pure/name-of c)) :ro ro :class "colour-pill" :caret? false}
     (fn [close!]
       [:div.head-pop.colour-pop
        [palette (when-not new? ck) c (fn [p] (if new? (put! {:color p}) (rf/dispatch [:ops/color ck p])) (close!))]])]))

(defn- row-2 [hc]
  [:div.head-row.r2
   [place/where-pick hc]
   [:f> voice-pick hc]
   [notify-pick hc]
   [:span.head-end [colour-pick hc]]])

;; ---- row 1's right: Sudo ----

(defn- root-button
  "The chat's root switch (README decision 52), only for its owner: red while on; turning it on asks
  first."
  [ck]
  (let [on? @(rf/subscribe [:root? ck])]
    [:button.head-btn.root-btn {:type "button" :class (when on? "on") :aria-pressed (boolean on?)
                                :data-tip (if on? "Root on: sudo runs without asking. Click to turn it off"
                                              "Root off: each sudo command asks you first")
                                :on-click #(rf/dispatch (if on? [:chat/root ck false] [:root/ask ck]))}
     [hp/icon :root] [:span (if on? "Sudo on" "Sudo off")]]))

(defn- row-1
  "The title and the server's state; Sudo on the right (its owner, a started chat)."
  [{:keys [ck row ro] :as hc}]
  [:div.head-row.r1
   [:f> head-names hc]
   (when (and row (not ro) @(rf/subscribe [:owner?]))
     [:span.head-end [root-button ck]])])

;; ---- the header ----

(defn- head-view [oc ro]
  (let [hc (chat-context/context oc ro)]
    (chat-context/use-tokens!)
    [:header.chat-head
     [:div.head-panel {:style (words/colour-style (:color hc))}
      [row-1 hc]
      (row-2 hc)]]))

(defn header
  "The header of the open chat oc (nil: a new chat)."
  [oc]
  [:f> head-view oc @(rf/subscribe [:read-only?])])
