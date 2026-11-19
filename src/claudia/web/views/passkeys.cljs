(ns claudia.web.views.passkeys
  "Settings → Passkeys (README decision 66): the person's passkeys at the key broker, any provider
  (iCloud Keychain, 1Password, …), several at once. Adding the first needs a code made on the gate;
  every later one, and removing one, is approved with a passkey already kept. Shown only when the
  hub has a key broker."
  (:require [clojure.string :as str]
            [claudia.web.format :as format]
            [claudia.web.passkey :as passkey]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(defn- load! [st]
  (-> (passkey/listing)
      (.then #(swap! st assoc :list % :error nil))
      (.catch #(swap! st assoc :list [] :error (passkey/problem %)))))

(defn- act! [st p]
  (swap! st assoc :busy true :error nil)
  (-> p
      (.then #(do (swap! st assoc :busy false :name "" :code "") (load! st)))
      (.catch #(swap! st assoc :busy false :error (passkey/problem %)))))

(defn- passkey-row [st {:keys [id createdAt usedAt] passkey-name :name}]
  [:div.st-token
   [:span.st-token-name passkey-name]
   [:span.muted.small (str "added " (format/when-text createdAt)
                           (when usedAt (str ", used " (format/when-text usedAt))))]
   [:button.token-remove {:type "button" :disabled (:busy @st) :title "Approve with a passkey to remove it"
                          :on-click #(act! st (passkey/remove! id))}
    "Remove"]])

(defn- field [st k placeholder]
  [:input {:type "text" :auto-complete "off" :placeholder placeholder :value (get @st k)
           :disabled (:busy @st) :on-change #(swap! st assoc k (.. % -target -value))}])

(defn- add-form [st first?]
  (let [code (str/trim (:code @st))]
    [:div.st-token-form
     [field st :name "Name it, e.g. iPhone or 1Password"]
     (when first? [field st :code "Code from the gate"])
     [:button.primary {:type "button" :disabled (or (:busy @st) (and first? (str/blank? code)))
                       :on-click #(act! st (passkey/add! (:name @st) (when first? code)))}
      "Add passkey"]]))

(defn- intro [first?]
  [:p.small (if first?
              [:<> "None yet. On the gate, run " [:code "broker.js enroll <your email>"]
               " for a one-time code, then add your first passkey here."]
              "Any of these approves a secret. Add one per passkey app you use.")])

(defn- passkeys-body [st]
  (let [{:keys [error] passkeys :list} @st
        first? (empty? passkeys)]
    [:<>
     (if (nil? passkeys) [:p.small "Checking…"] [intro first?])
     (when (seq passkeys)
       [:div.st-tokens (for [k passkeys] ^{:key (:id k)} [passkey-row st k])])
     (when passkeys [add-form st first?])
     (when error [:p.ask-error error])]))

(defn passkeys-section
  "The section, when the hub has a key broker."
  []
  (r/with-let [st (r/atom {:list nil :name "" :code "" :busy false})
               _ (load! st)]
    (when (:vault @(rf/subscribe [:me]))
      [:section.st-section.st-passkeys
       [:h3 "Passkeys " [:span.muted.small "approve secrets"]]
       [passkeys-body st]])))
