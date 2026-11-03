(ns claudia.web.views.picture
  "The right half: the open chat's picture, a canvas of blocks top to bottom (two half blocks side by
  side), or the timeline; the bar over its bottom; the folded edge. No title on the board: the title
  names a picture only in the bar's scrubber. Desktop: the form row is the right rail beside it
  (right-rail); phone: a row at the top of the picture screen."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.db :as db]
            [claudia.web.md :as md]
            [claudia.web.views.codeforms :as codeforms]
            [claudia.web.views.forms :as forms]
            [claudia.web.views.frame :as frame]
            [claudia.web.views.graph :as graph]
            [claudia.web.views.rightbar :as rightbar]
            [claudia.web.views.snapshot :as snapshot]
            [claudia.web.views.timeline :as timeline]))

;; ---- what changed since the last picture shown (the glow, a node's ring, a hot arrow) ----

(defn- by-id [xs] (into {} (map (juxt :id identity)) xs))
(defn- eid [{:keys [from to]}] (str from "->" to))

(defn- changed-ids
  "Ids of the blocks, nodes and arrows that are new or different in scene against prev."
  [prev scene]
  (let [old (by-id (:blocks prev))]
    (into #{}
          (mapcat (fn [b]
                    (let [p (old (:id b))]
                      (concat
                       (when (not= p b) [(:id b)])
                       (when (and p (= "graph" (:kind b)) (not= p b))
                         (let [on (by-id (:nodes p))
                               oe (into {} (map (juxt eid identity)) (:edges p))]
                           (concat (for [n (:nodes b) :when (not= n (on (:id n)))] (:id n))
                                   (for [e (:edges b) :when (not= e (oe (eid e)))] (eid e)))))))))
          (:blocks scene))))

;; ---- one block ----

(defn- block-ctx
  "What a form needs to read and write through the open chat's server."
  [block live?]
  (let [{:keys [server chat]} @(rf/subscribe [:open-chat])]
    {:server server :chat chat :block (:id block) :live (boolean (and server chat live?))}))

(defn block-view
  "One block. hot: the last draw changed it (a new glow per draw, seq); kept: :here or :gone for a
  files block the user clicked in (it stays on the picture until closed)."
  ([block] (block-view block {}))
  ([block {:keys [changed seq-no live? kept on-touch on-unkeep] :or {changed #{} seq-no 0 live? true}}]
   (let [ctx (block-ctx block live?)
         hot (or (contains? changed (:id block))
                 (some #(str/starts-with? (str %) (str (:id block) ":")) changed))]
     [:section.block {:class [(str "w-" (if (= "half" (:width block)) "half" "full"))
                              (str "k-" (:kind block))
                              (when (= kept :gone) "kept-gone")]
                      :on-click-capture (when-not kept on-touch)}
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

(defn picture-view
  "A scene's blocks. k: the chat it belongs to; live?: it is the chat's live picture (forms that
  write, templates and kept blocks work only there); cols: 0 = Claude's layout, 1-3 = every block
  one cell in that many columns."
  [{:keys [scene k live? cols]}]
  (let [prev (h/use-ref nil)
        ;; A files block the user clicked in stays on the live picture when Claude's draw removes it,
        ;; until the user closes it. Kept per chat, for this page.
        [kept set-kept] (h/use-state {:k nil :ids []})
        copies (h/use-ref {})
        kept-ids (if (= (:k kept) k) (:ids kept) [])
        p (.-current prev)
        p (cond
            (or (nil? p) (not= (:k p) k)) {:k k :scene scene :changed #{} :seq 0}
            (identical? (:scene p) scene) p
            :else {:k k :scene scene :changed (changed-ids (:scene p) scene) :seq (inc (:seq p))})
        _ (set! (.-current prev) p)
        _ (when (not= (:k (.-current copies)) k) (set! (.-current copies) {:k k}))
        blocks (:blocks scene)
        _ (doseq [[i b] (map-indexed vector blocks) :when (some #{(:id b)} kept-ids)]
            (set! (.-current copies) (assoc (.-current copies) (:id b) {:block b :at i})))
        present (set (map :id blocks))
        gone (if live? (set (remove present (filter #(get (.-current copies) %) kept-ids))) #{})
        blocks (reduce (fn [bs id]
                         (let [{:keys [block at]} (get (.-current copies) id)
                               [a b] (split-at (min at (count bs)) bs)]
                           (vec (concat a [block] b))))
                       (vec blocks) (filter gone kept-ids))
        keep! (fn [id] (set-kept (fn [kp] (if (and (= (:k kp) k) (some #{id} (:ids kp)))
                                            kp
                                            {:k k :ids (conj (if (= (:k kp) k) (:ids kp) []) id)}))))
        unkeep! (fn [id]
                  (set! (.-current copies) (dissoc (.-current copies) id))
                  (set-kept (fn [kp] {:k k :ids (vec (remove #{id} (:ids kp)))})))]
    (cond
      (seq blocks)
      [:div.picture.blocks {:class (when (pos? cols) (str "cols-" cols))}
       (for [b blocks
             :let [kept? (and live? (some #{(:id b)} kept-ids))]]
         ^{:key (:id b)}
         [block-view b {:changed (:changed p) :seq-no (:seq p) :live? live?
                        :kept (when kept? (if (gone (:id b)) :gone :here))
                        :on-touch (when (and live? (= "form" (:kind b)) (= "files" (:form b))) #(keep! (:id b)))
                        :on-unkeep #(unkeep! (:id b))}])]

      (and (nil? (:blocks scene)) (or (seq (:nodes scene)) (seq (:cards scene))))
      [:div.picture
       (when (seq (:nodes scene)) [:f> graph/graph-view scene (:changed p) (:seq p)])
       (when (seq (:cards scene))
         [:div.cards (for [c (:cards scene)] ^{:key (:id c)} [card c])])]

      :else [empty-state])))

;; ---- the Forms gallery (opened from the rail and Settings) ----

(rf/reg-event-db :forms/gallery (fn [db [_ open?]] (assoc db :forms-gallery open?)))
(rf/reg-sub :forms/gallery (fn [db] (:forms-gallery db)))

(defn gallery-host
  "The Forms gallery over the app while it is open."
  []
  (let [open? @(rf/subscribe [:forms/gallery])
        oc @(rf/subscribe [:open-chat])
        server (or (:server oc) (first (keys @(rf/subscribe [:servers]))))]
    (when (and open? server)
      [:f> codeforms/gallery server #(rf/dispatch [:forms/gallery false])])))

;; ---- the right rail (desktop): this chat's forms ----

(defn right-rail
  "The rail beside the picture, mirroring the chats rail: the form row in a column. Not on phones
  (there the row is at the top of the picture screen)."
  []
  (let [oc @(rf/subscribe [:open-chat])
        ro @(rf/subscribe [:read-only?])
        server (or (:server oc) (first (keys @(rf/subscribe [:servers]))))]
    [:nav.rail.right-rail {:aria-label "Forms"}
     (when (and server (not ro))
       [:f> codeforms/picture-tools (assoc oc :server server) {:rail? true}])]))

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
        asks (get-in oc [:state :asks])
        cols (get-in oc [:state :pictureSettings :cols] 0)]
    (if (and (:folded? fold) (not phone?))
      [rightbar/fold-edge (:unseen? fold)]
      [:section.right rightbar/mouse-props
       (if (= view :timeline)
         [:div.right-tl [:f> timeline/timeline-view oc]]
         [:div.right-scroll
          (when (and phone? (:server oc) (not ro))
            [:f> codeforms/picture-tools oc {:rail? false}])
          [:div.stage {:class (when (:viewing? hist) "past")}
           [:f> picture-view {:scene scene :k (:ck oc) :cols cols
                              :live? (and (not (:viewing? hist)) (not ro))}]]])
       (when (:chat oc)
         [rightbar/zone (when (and (= view :picture) (seq (:blocks scene))) [snapshot/button oc hist])])
       (when (seq asks)
         [:button.ask-return.phone-only {:on-click #(rf/dispatch [:nav/screen :chat])}
          "Claude asks you something"])])))

