(ns claudia.web.views.picture
  "The right side: the open chat's Scene, its blocks top to bottom (two half blocks side by side)."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.views.codeforms :as codeforms]
            [claudia.web.views.frame :as frame]
            [claudia.web.views.graph :as graph]))

(defn- kind-label [{:keys [kind form]}]
  (case kind
    "graph" "graph"
    "form" (or form "form")
    "html" "freeform"
    (or kind "block")))

(defn- badge [block]
  (let [k (kind-label block)]
    [:span.block-badge {:class (str "b-" (if (= "form" (:kind block)) "form" k))}
     [:b k]
     (when (:name block) [:span.block-name (:name block)])]))

(defn- block-ctx
  "What a form needs to read and write through the open chat's server."
  [block]
  (let [{:keys [server chat]} @(rf/subscribe [:open-chat])]
    {:server server :chat chat :block (:id block) :live (boolean (and server chat))}))

(defn- block-view
  "One block; a short glow when its content changes (not on first sight)."
  [block]
  (let [ctx (block-ctx block)
        prev (h/use-ref block)
        [glow set-glow] (h/use-state 0)]
    (h/use-effect
     (fn []
       (when (not= (.-current prev) block)
         (set! (.-current prev) block)
         (set-glow inc))
       nil)
     [block])
    [:section.block {:class [(str "w-" (if (= "half" (:width block)) "half" "full"))
                             (str "k-" (:kind block))]}
     ;; A new key per change restarts the glow's animation.
     (when (pos? glow) (list ^{:key glow} [:span.block-glow]))
     [:div.block-head
      (when (and (= "html" (:kind block)) (:live ctx)) [:f> codeforms/template-save (:server ctx) (:chat ctx) (:id block)])
      [badge block]]
     (case (:kind block)
       "graph" [:f> graph/graph-view block]
       "form" [codeforms/form-view (:form block) (:data block) ctx]
       "html" [:f> frame/page-frame (:html block) (:parts block)]
       [:p.f-note (str "Unknown block kind \"" (:kind block) "\".")])]))

(defn- empty-state []
  [:div.picture-empty
   [:div.dots [:span] [:span] [:span]]
   [:p "Claude draws here as it works."]])

(defn picture-pane
  "The picture of the open chat. on-back: the phone's way back to the chat."
  []
  (let [oc @(rf/subscribe [:open-chat])
        scene (get-in oc [:state :scene])
        blocks (:blocks scene)
        asks (get-in oc [:state :asks])
        cols (get-in oc [:state :settings :cols] 0)]
    [:section.right
     [:header.picture-head
      [:button.back-btn.phone-only {:on-click #(rf/dispatch [:nav/screen :chat])}
       [:svg {:viewBox "0 0 24 24"} [:path {:d "M15 5l-7 7 7 7"}]] "Chat"]
      [:h2.picture-title (or (:title scene) "")]
      [:f> codeforms/picture-tools oc]]
     [:div.picture-scroll
      (if (seq blocks)
        [:div.picture.blocks {:class (when (pos? cols) (str "cols-" cols))}
         (for [b blocks]
           ^{:key (:id b)} [:f> block-view b])]
        [empty-state])]
     (when (seq asks)
       [:button.ask-return.phone-only {:on-click #(rf/dispatch [:nav/screen :chat])}
        "Claude asks you something"])]))
