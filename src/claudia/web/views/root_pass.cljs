(ns claudia.web.views.root-pass
  "The passkey before root (README decision 79), for the confirm card of the root switch and the
  Allow of a \"Run as root\" card. The challenge is fetched when the card appears and again every
  4 minutes (the front's nonce and the broker's challenge hold 5), so the tap opens the passkey at
  once: WebKit refuses a passkey that a fetch stands between it and the tap."
  (:require [reagent.core :as r]
            [claudia.web.passkey :as passkey]))

(def refresh-ms "How often a waiting card fetches a fresh challenge." (* 4 60 1000))

(defn prepare!
  "Fetches the challenge for root on chat k (action \"on\" or \"allow\", ask the card's nonce) into
  st: {:prepared …} or {:error …}."
  [st k action ask]
  (swap! st assoc :prepared nil)
  (-> (passkey/prepare-root k action ask)
      (.then #(swap! st assoc :prepared % :error nil))
      (.catch #(swap! st assoc :error (passkey/problem %)))))

(defn tap!
  "The tap: the passkey signs the prepared challenge, the broker's grant goes to on-grant. After a
  failure the error shows and a fresh challenge is fetched."
  [st k action ask on-grant]
  (swap! st assoc :busy true :error nil)
  (-> (passkey/root-grant! (:prepared @st))
      (.then #(do (swap! st assoc :busy false) (on-grant %)))
      (.catch #(do (swap! st assoc :busy false :error (passkey/problem %))
                   (prepare! st k action ask)))))

(defn ready?
  "Whether the tap may go: a challenge in hand, nothing running."
  [{:keys [prepared busy]}]
  (boolean (and prepared (not busy))))

(defn holder
  "A component that keeps its own state prepared for root on k while it is shown, rendering
  (render st) with that state (an r/atom: {:prepared :busy :error})."
  [k action ask _render]
  (let [st (r/atom {})
        timer (atom nil)]
    (r/create-class
     {:display-name "root-pass"
      :component-did-mount
      (fn []
        (prepare! st k action ask)
        (reset! timer (js/setInterval #(prepare! st k action ask) refresh-ms)))
      :component-will-unmount #(some-> @timer js/clearInterval)
      :reagent-render (fn [_k _action _ask render-fn] (render-fn st))})))
