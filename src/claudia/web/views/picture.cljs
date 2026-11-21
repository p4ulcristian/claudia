(ns claudia.web.views.picture
  "The right half: the Drawing board (the open chat's picture, a canvas of blocks top to bottom, two
  half blocks side by side, the stepper under it) with the sheets of the other views laid on it
  (README decision 90), and the folded edge. No title and no frame on the board: the title names a
  picture only in the bar's scrubber."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.shared.views :as views]
            [claudia.web.db :as db]
            [claudia.web.views.codeforms :as codeforms]
            [claudia.web.views.forms :as forms]
            [claudia.web.views.pure.picture :as pure]
            [claudia.web.views.rightbar :as rightbar]
            [claudia.web.views.sheets :as sheets]
            [claudia.web.views.snapshot :as snapshot]))

;; ---- one block ----

(defn- block-ctx
  "What a form needs to read and write through the open chat's server, and the block's id and name."
  [block live?]
  (let [{:keys [server chat]} @(rf/subscribe [:open-chat])]
    {:server server :chat chat :block (:id block) :title (:name block) :live (boolean (and server chat live?))}))

(defn block-view
  "One block. hot: the last draw changed it (a new glow per draw, seq); kept: :here or :gone for a
  files block the user clicked in (it stays on the picture until closed)."
  ([block] (block-view block {}))
  ([block {:keys [changed seq-no live? kept on-touch on-unkeep] :or {changed #{} seq-no 0 live? true}}]
   (let [ctx (block-ctx block live?)
         hot (or (contains? changed (:id block))
                 (some #(str/starts-with? (str %) (str (:id block) ":")) changed))]
     [:section.block {:class [(str "w-" (if (= "half" (:width block)) "half" "full"))
                              (when (= kept :gone) "kept-gone")]
                      :on-click-capture (when-not kept on-touch)}
      ;; A new glow per change; the block itself stays mounted (an html block keeps its page loaded).
      (when hot ^{:key seq-no} [:span.block-glow])
      [:div.block-head
       [forms/badge block]
       (when (and (= "html" (:form block)) (:live ctx))
         [:f> codeforms/template-save (:server ctx) (:chat ctx) block])
       (when kept
         [:button.kept-off {:class (name kept) :on-click on-unkeep
                            :title (if (= kept :gone)
                                     "Claude took this off the picture; it stays until you close it"
                                     "Kept: stays even when Claude takes it off the picture. Click to stop keeping it")}
          (if (= kept :gone) "kept · close ✕" "kept ✕")])]
      ;; Every block is as tall as it is: no inner scrolling, the picture scrolls.
      [codeforms/form-view (:form block) (:data block) (assoc ctx :changed changed :seq-no seq-no)]])))

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
                    :on-touch (when (and live? (= "files" (:form b))) #(keep! (:id b)))
                    :on-unkeep #(unkeep! (:id b))}])])

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

      :else [empty-state])))

;; ---- the pane ----

(defn- stage
  "The picture: the live one, or an earlier one from the history (read only)."
  [oc hist ro]
  [:div.right-scroll
   [:div.stage {:class (when (:viewing? hist) "past")}
    [:f> picture-view {:scene (:scene hist) :k (:ck oc)
                       :live? (and (not (:viewing? hist)) (not ro))}]]])

(defn- board
  "The Drawing board: the stage, and the bar under it for a chat. covered?: sheets lie on it, so it
  takes no input (README decision 90)."
  [oc hist ro covered?]
  [:div.board {:inert (when covered? true)}
   [stage oc hist ro]
   (when (:chat oc)
     [rightbar/bar (when (seq (:blocks (:scene hist))) [snapshot/button oc hist])])])

(defn picture-pane
  "The right half of the open chat: the Drawing board, with the person's pile of sheets laid on it
  (claudia.web.views.sheets)."
  []
  (let [oc @(rf/subscribe [:open-chat])
        hist @(rf/subscribe [:picture-history])
        fold @(rf/subscribe [:fold-state])
        ro @(rf/subscribe [:read-only?])
        phone? (db/phone?)
        asks (get-in oc [:state :asks])]
    (if (and (:folded? fold) (not phone?))
      [rightbar/fold-edge (:unseen? fold)]
      (let [shown (views/shown @(rf/subscribe [:right-sheets]) ro)]
        [:section.right
         [board oc hist ro (seq shown)]
         [sheets/pile shown oc]
         (when (seq asks)
           [:button.ask-return.phone-only {:on-click #(rf/dispatch [:nav/screen :chat])}
            "Claude asks you something"])]))))
