(ns claudia.web.views.picture
  "The right half: the open chat's picture, a canvas of blocks top to bottom (two half blocks side by
  side), or the timeline; the view switch over it and the stepper under the picture; the folded edge. No title on the board: the title
  names a picture only in the bar's scrubber."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.db :as db]
            [claudia.web.md :as md]
            [claudia.web.views.circle :as circle]
            [claudia.web.views.codeforms :as codeforms]
            [claudia.web.views.forms :as forms]
            [claudia.web.views.frame :as frame]
            [claudia.web.views.graph :as graph]
            [claudia.web.views.pure.picture :as pure]
            [claudia.web.views.rightbar :as rightbar]
            [claudia.web.views.snapshot :as snapshot]
            [claudia.web.views.timeline :as timeline]))

;; ---- one block ----

(defn- block-ctx
  "What a form needs to read and write through the open chat's server, and the block's id and name."
  [block live?]
  (let [{:keys [server chat]} @(rf/subscribe [:open-chat])]
    {:server server :chat chat :block (:id block) :title (:name block) :live (boolean (and server chat live?))}))

(defn- block-attrs
  "A block's section: its width and kind; its id, name and form as data (what a circle on the picture
  names, claudia.web.views.circle)."
  [block kept on-touch]
  {:data-block (:id block) :data-name (:name block) :data-kind (:kind block) :data-form (:form block)
   :class [(str "w-" (if (= "half" (:width block)) "half" "full"))
           (str "k-" (:kind block))
           (when (= kept :gone) "kept-gone")]
   :on-click-capture (when-not kept on-touch)})

(defn block-view
  "One block. hot: the last draw changed it (a new glow per draw, seq); kept: :here or :gone for a
  files block the user clicked in (it stays on the picture until closed)."
  ([block] (block-view block {}))
  ([block {:keys [changed seq-no live? kept on-touch on-unkeep] :or {changed #{} seq-no 0 live? true}}]
   (let [ctx (block-ctx block live?)
         hot (or (contains? changed (:id block))
                 (some #(str/starts-with? (str %) (str (:id block) ":")) changed))]
     [:section.block (block-attrs block kept on-touch)
      ;; A new glow per change; the block itself stays mounted (an html block keeps its page loaded).
      (when hot ^{:key seq-no} [:span.block-glow])
      [:div.block-head
       [forms/badge block]
       (when (and (= "html" (:kind block)) (:live ctx))
         [:f> codeforms/template-save (:server ctx) (:chat ctx) block])
       (when kept
         [:button.kept-off {:class (name kept) :on-click on-unkeep
                            :title (if (= kept :gone)
                                     "Claude took this off the picture; it stays until you close it"
                                     "Kept: stays even when Claude takes it off the picture. Click to stop keeping it")}
          (if (= kept :gone) "kept · close ✕" "kept ✕")])]
      (case (:kind block)
        "graph" [:f> graph/graph-view block changed seq-no]
        ;; Every block is as tall as it is: no inner scrolling, the picture scrolls.
        "form" [codeforms/form-view (:form block) (:data block) ctx]
        "html" [:f> frame/page-frame (:html block) (:parts block)]
        [:p.f-note (str "Unknown block kind \"" (:kind block) "\".")])])))

;; ---- a picture from before blocks: its graph and cards as they were drawn ----

(defn- card [{:keys [kind title text code items html columns rows color]}]
  (let [kind (or kind "text")]
    [:section.card {:class [(str "c-" (or color "grey")) (str "k-" kind)]}
     (when title [:h3 title])
     (case kind
       "text" [:div.md {:dangerouslySetInnerHTML {:__html (md/html text)}}]
       "code" [:div.code [:pre [:code.hljs (or code text)]]]
       "list" (into [:ul] (map-indexed (fn [i it] ^{:key i} [:li {:dangerouslySetInnerHTML {:__html (md/html it)}}]) items))
       "page" [:f> frame/page-frame html nil]
       "table" [:div.table-wrap
                [:table
                 (when (seq columns) [:thead (into [:tr] (map-indexed (fn [i c] ^{:key i} [:th c]) columns))])
                 (into [:tbody] (map-indexed (fn [i row] ^{:key i}
                                               (into [:tr] (map-indexed (fn [j c] ^{:key j} [:td {:dangerouslySetInnerHTML {:__html (md/html c)}}]) row)))
                                             rows))]]
       nil)]))

(defn- empty-state []
  [:div.picture-empty
   [:div.dots [:span] [:span] [:span]]
   [:p "Claude draws here as it works."]])

;; ---- the picture ----

(defn- blocks-view [{:keys [blocks p live? kept-ids gone keep! unkeep!]}]
  [:div.picture.blocks
   (for [b blocks
         :let [kept? (and live? (some #{(:id b)} kept-ids))]]
     ^{:key (:id b)}
     [block-view b {:changed (:changed p) :seq-no (:seq p) :live? live?
                    :kept (when kept? (if (gone (:id b)) :gone :here))
                    :on-touch (when (and live? (= "form" (:kind b)) (= "files" (:form b))) #(keep! (:id b)))
                    :on-unkeep #(unkeep! (:id b))}])])

(defn- old-picture [scene p]
  [:div.picture
   (when (seq (:nodes scene)) [:f> graph/graph-view scene (:changed p) (:seq p)])
   (when (seq (:cards scene))
     [:div.cards (for [c (:cards scene)] ^{:key (:id c)} [card c])])])

(defn picture-view
  "A scene's blocks. k: the chat it belongs to; live?: it is the chat's live picture (forms that
  write, templates and kept blocks work only there)."
  [{:keys [scene k live?]}]
  (let [prev (h/use-ref nil)
        ;; A files block the user clicked in stays on the live picture when Claude's draw removes it,
        ;; until the user closes it. Kept per chat, for this page.
        [kept set-kept] (h/use-state {:k nil :ids []})
        copies (h/use-ref {})
        kept-ids (if (= (:k kept) k) (:ids kept) [])
        p (pure/next-shown (.-current prev) k scene)
        _ (set! (.-current prev) p)
        _ (set! (.-current copies) (pure/copies-of (.-current copies) k (:blocks scene) kept-ids))
        gone (pure/gone-ids (:blocks scene) kept-ids (.-current copies) live?)
        blocks (pure/with-gone (:blocks scene) kept-ids gone (.-current copies))]
    (cond
      (seq blocks)
      (blocks-view {:blocks blocks :p p :live? live? :kept-ids kept-ids :gone gone
                    :keep! (fn [id] (set-kept #(pure/kept-with % k id)))
                    :unkeep! (fn [id]
                               (set! (.-current copies) (dissoc (.-current copies) id))
                               (set-kept (fn [kp] {:k k :ids (vec (remove #{id} (:ids kp)))})))})

      (and (nil? (:blocks scene)) (or (seq (:nodes scene)) (seq (:cards scene))))
      (old-picture scene p)

      :else [empty-state])))

;; ---- the pane ----

(defn picture-pane
  "The right half of the open chat."
  []
  (let [oc @(rf/subscribe [:open-chat])
        hist @(rf/subscribe [:picture-history])
        view @(rf/subscribe [:right-view])
        fold @(rf/subscribe [:fold-state])
        ro @(rf/subscribe [:read-only?])
        phone? (db/phone?)
        ;; The picture shown: the live one, or an earlier one from the history (read only).
        scene (:scene hist)
        asks (get-in oc [:state :asks])]
    (if (and (:folded? fold) (not phone?))
      [rightbar/fold-edge (:unseen? fold)]
      [:section.right
       (when (:chat oc) [rightbar/top])
       (if (= view :timeline)
         [:div.right-tl [:f> timeline/timeline-view oc]]
         [:div.right-scroll
          [:div.stage {:class (when (:viewing? hist) "past")}
           [:f> picture-view {:scene scene :k (:ck oc)
                              :live? (and (not (:viewing? hist)) (not ro))}]
           ;; Circling with the right button: on the live picture of an open chat.
           (when (and (:ck oc) (not (:viewing? hist)) (not ro) (not phone?))
             ^{:key (str (:ck oc))} [circle/layer (:ck oc)])]])
       (when (and (:chat oc) (= view :picture))
         [rightbar/bar (when (seq (:blocks scene)) [snapshot/button oc hist])])
       (when (seq asks)
         [:button.ask-return.phone-only {:on-click #(rf/dispatch [:nav/screen :chat])}
          "Claude asks you something"])])))

