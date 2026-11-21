(ns claudia.web.views.chat-context
  "What the chat's header and the strip over its message box need (README decisions 90, 117), the
  same for a new chat (oc nil or not started) and a started one: where it runs
  (folders/chat-place), its settings and how a pick is put, its plans and the plan it runs on."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.folders :as folders]
            [claudia.web.views.pure.chat :as pure]))

(defn context
  "The open chat oc's context (nil: the new chat); ro: a read-only view."
  [{:keys [ck state row pending?] :as oc} ro]
  (let [place (folders/chat-place oc @(rf/subscribe [:new-server]) @(rf/subscribe [:new-settings]))
        {:keys [new? server]} place
        settings (if new? @(rf/subscribe [:new-settings]) (:settings state))
        {:keys [plans planKey]} @(rf/subscribe [:meta server])]
    (assoc (if new? {} oc)
           :new? new? :oc oc :server server :settings settings :plans plans
           :on (pure/chat-plan plans (:plan settings) planKey)
           :folder (:folder place) :view-ro ro
           :color (if new? (:color settings) (:color row))
           :ro (or ro (and (not new?) (or pending? (not row))))
           :put! (if new? #(rf/dispatch [:new/settings %]) #(rf/dispatch [:chat/settings ck %])))))

(defn use-tokens!
  "The hub's saved tokens with their limits, loaded now and every minute."
  []
  (h/use-effect (fn [] (rf/dispatch [:ops/load-tokens])
                  (let [t (js/setInterval #(rf/dispatch [:ops/load-tokens]) 60000)] #(js/clearInterval t)))
                []))
