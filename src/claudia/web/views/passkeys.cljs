(ns claudia.web.views.passkeys
  "Settings → Passkeys (README decision 66): the person's passkeys at the key broker, any provider
  (iCloud Keychain, 1Password, …), several at once. Adding the first needs a code made on the gate;
  every later one, and removing one, is approved with a passkey already kept. Shown only when the
  hub has a key broker.

  Each passkey call happens on its own tap, with the broker's options fetched before it
  (claudia.web.passkey): :step says which tap is next."
  (:require [clojure.string :as str]
            [claudia.web.format :as format]
            [claudia.web.passkey :as passkey]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(defn- load! [st]
  (-> (passkey/listing)
      (.then #(swap! st assoc :list % :error nil))
      (.catch #(swap! st assoc :list [] :error (passkey/problem %)))))

(defn- fail! [st e] (swap! st assoc :busy false :step nil :error (passkey/problem e)))

(defn- next-step!
  "Waits for p (options from the broker), then offers the tap step makes of them."
  [st p step]
  (swap! st assoc :busy true :error nil)
  (-> p
      (.then #(swap! st assoc :busy false :step (assoc step :prepared %)))
      (.catch #(fail! st %))))

(defn- finish!
  "The tap's passkey call p; the list reloads when it is done."
  [st p]
  (swap! st assoc :busy true :error nil)
  (-> p
      (.then #(do (swap! st assoc :busy false :step nil :name "" :code "") (load! st)))
      (.catch #(fail! st %))))

(defn- start-add! [st first? code]
  (if first?
    (next-step! st (passkey/prepare-add {:code code}) {:kind :create})
    (next-step! st (passkey/prepare-approval {:kind "add-passkey"}) {:kind :approve-add})))

(defn- tap!
  "The prepared step's passkey call, straight from the tap."
  [st {:keys [kind prepared]}]
  (case kind
    :create (finish! st (passkey/create! prepared (:name @st)))
    :remove (finish! st (passkey/remove! prepared))
    :approve-add (next-step! st (.then (passkey/sign! prepared) passkey/prepare-add) {:kind :create})))

(def ^:private tap-labels
  {:create "Create the passkey now"
   :remove "Confirm removal with a passkey"
   :approve-add "Approve with a passkey you have"})

(defn- passkey-row [st {:keys [id createdAt usedAt] passkey-name :name}]
  [:div.st-token
   [:span.st-token-name passkey-name]
   [:span.muted.small (str "added " (format/when-text createdAt)
                           (when usedAt (str ", used " (format/when-text usedAt))))]
   [:button.token-remove {:type "button" :disabled (or (:busy @st) (:step @st))
                          :on-click #(next-step! st (passkey/prepare-approval {:kind "remove-passkey" :id id})
                                                 {:kind :remove})}
    "Remove"]])

(defn- field [st k placeholder]
  [:input {:type "text" :auto-complete "off" :placeholder placeholder :value (get @st k)
           :disabled (or (:busy @st) (:step @st)) :on-change #(swap! st assoc k (.. % -target -value))}])

(defn- tap-row [st step]
  [:div.st-token-form
   [:button.primary {:type "button" :disabled (:busy @st) :on-click #(tap! st step)} (tap-labels (:kind step))]
   [:button {:type "button" :disabled (:busy @st) :on-click #(swap! st assoc :step nil)} "Cancel"]])

(defn- add-form [st first?]
  (let [code (str/trim (:code @st))]
    [:div.st-token-form
     [field st :name "Name it, e.g. iPad or 1Password"]
     (when first? [field st :code "Code from the gate"])
     [:button.primary {:type "button" :disabled (or (:busy @st) (and first? (str/blank? code)))
                       :on-click #(start-add! st first? code)}
      "Add passkey"]]))

(defn- intro [first?]
  [:p.small (if first?
              [:<> "None yet. On the gate, run " [:code "broker.js enroll <your email>"]
               " for a one-time code, then add your first passkey here."]
              "Any of these approves a secret. Add one per passkey app you use.")])

(defn- passkeys-body [st]
  (let [{:keys [error step] passkeys :list} @st
        first? (empty? passkeys)]
    [:<>
     (if (nil? passkeys) [:p.small "Checking…"] [intro first?])
     (when (seq passkeys)
       [:div.st-tokens (for [k passkeys] ^{:key (:id k)} [passkey-row st k])])
     (cond step [tap-row st step]
           passkeys [add-form st first?])
     (when error [:p.ask-error error])]))

(defn passkeys-section
  "The section, when the hub has a key broker."
  []
  (r/with-let [st (r/atom {:list nil :name "" :code "" :busy false :step nil})
               _ (load! st)]
    (when (:vault @(rf/subscribe [:me]))
      [:section.st-section.st-passkeys
       [:h3 "Passkeys " [:span.muted.small "approve secrets"]]
       [passkeys-body st]])))
