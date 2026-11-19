(ns claudia.web.views.folders
  "The Folders modal (README decisions 61 to 64), over the page, opened from a chat's header on that
  chat's server: on top the server's bookmarks as cards (the project's logo as the chats show it,
  else a folder; name, path, ★ to unpin, \"missing\" greyed;
  a click opens the tree there, Select uses it for the chat); on the left a filter field and the
  tree from \"/\" with the chat's folder (else the work folder) selected; in the middle what that
  folder holds, as a grid or a list (claudia.web.views.folder-contents); on the right the picked
  file's preview, or the folder (claudia.web.views.folder-preview, folder-detail: Use for this
  chat, New chat here, Pin, Trash, Delete now), or the Trash (claudia.web.views.folder-trash). Only the person's own chats: a view has no
  button for it and never reaches these calls."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.folders :as folders]
            [claudia.web.format :as format]
            [claudia.web.hooks :as hooks]
            [claudia.shared.bookmarks :as bookmarks]
            [claudia.web.views.dot :as dot]
            [claudia.web.views.folder-contents :as contents]
            [claudia.web.views.folder-preview :as preview]
            [claudia.web.views.folder-trash :as trash]
            [claudia.web.views.folder-tree :as tree]
            [claudia.web.views.pure.folders :as pure]))

(defn- use-listings
  "The folders of server loaded so far ({:loaded (path -> entries) :cwd :error}) and load! (a path,
  again when force?)."
  [server]
  (let [[state set-state] (h/use-state {:loaded {} :cwd nil :error nil})
        asked (h/use-ref #{})
        load! (fn [p force?]
                (when (or force? (not (contains? (.-current asked) p)))
                  (set! (.-current asked) (conj (.-current asked) p))
                  (-> (folders/listing! server p)
                      (.then (fn [{:keys [path entries cwd]}]
                               (set-state #(-> % (assoc-in [:loaded path] entries) (update :cwd (fn [c] (or c cwd))) (assoc :error nil)))
                               cwd))
                      (.catch (fn [e] (set! (.-current asked) (disj (.-current asked) p))
                                (set-state #(assoc % :error (.-message e))))))))]
    [state load!]))

(defn- use-first-load!
  "Loads \"/\", then every folder down to start (the chat's folder, else the work folder), and
  selects it (go!)."
  [load! go! start]
  (h/use-effect
   (fn []
     (some-> (load! "/" false)
             (.then (fn [cwd] (when-let [p (or start cwd)] (go! p)))))
     js/undefined)
   []))

(defn- use-missing
  "The bookmarked folders of server where no folder sits now (a set), asked when the list changes."
  [server rows]
  (let [[missing set-missing] (h/use-state #{})
        paths (mapv :folder rows)]
    (h/use-effect
     (fn []
       (when (seq paths)
         (-> (folders/missing! server paths) (.then #(set-missing (set (:missing %)))) (.catch (fn [_] nil))))
       js/undefined)
     [server (pr-str paths)])
    missing))

(defn- card
  "One bookmark as a card: a click opens the tree there (on: it is the selected folder), ★ unpins,
  Select uses it for the chat."
  [server {:keys [folder label missing]} on? go!]
  [:div.fd-card {:class [(when missing "missing") (when on? "on")] :title folder
                 :on-click #(when-not missing (go! folder))}
   [:button.fd-card-star {:type "button" :aria-label "Unpin" :title "Unpin"
                          :on-click (fn [e] (.stopPropagation e) (rf/dispatch [:bookmarks/toggle server folder]))} "★"]
   [:span.fd-card-face [dot/project-face server folder pure/folder-icon]]
   [:span.fd-card-name label]
   [:span.fd-card-path.mono [:bdi (format/tilde folder)]]
   (if missing
     [:span.fd-missing "missing"]
     [:button.fd-btn.primary.fd-card-use {:type "button" :on-click (fn [e] (.stopPropagation e) (rf/dispatch [:folders/use folder]))}
      "Select"])])

(defn- cards [server selected go!]
  (let [rows (bookmarks/on-server @(rf/subscribe [:bookmarks]) server)
        missing (use-missing server rows)]
    [:div.fd-cards
     (if (empty? rows)
       [:p.muted.fd-none "No bookmarks on this server yet: ☆ on a folder's row pins it here."]
       (for [b (pure/bookmark-rows rows server missing)]
         ^{:key (:folder b)} [card server b (= selected (:folder b)) go!]))]))

(defn- filter-field
  "Filters the loaded folders by name; a pasted or typed absolute path (Enter) is revealed."
  [text set-text go!]
  (let [reveal! #(when-let [p (pure/typed-path %)] (go! p))]
    [:input.fd-filter {:type "search" :value text :placeholder "Filter by name, or paste a path"
                       :on-change #(set-text (.. % -target -value))
                       :on-paste #(reveal! (.getData (.-clipboardData %) "text"))
                       :on-key-down #(when (= "Enter" (.-key %)) (reveal! text))}]))

(defn- top-bar [{:keys [label hidden? set-hidden trash? set-trash]}]
  [:header.fd-bar
   [:h1.fd-heading "Folders" [:span.fd-on (str " on " label)]]
   [:label.fd-switch [:input {:type "checkbox" :checked hidden? :on-change #(set-hidden (not hidden?))}] "show hidden"]
   [:button.fd-btn {:class (when trash? "on") :on-click #(set-trash (not trash?))} "Trash"]
   [:button.fd-close {:aria-label "Close Folders" :title "Close (Escape)" :on-click #(rf/dispatch [:folders/open nil])} "×"]])

(defn- use-contents
  "What folder p of server holds ({:entries :more :error}, nil while asked) and reload!."
  [server p]
  (let [[answer set-answer] (h/use-state nil)
        [tick set-tick] (h/use-state 0)]
    (h/use-effect
     (fn []
       (set-answer nil)
       (when p
         (let [gone (atom false)]
           (-> (folders/contents! server p)
               (.then #(when-not @gone (set-answer %)))
               (.catch #(when-not @gone (set-answer {:error (.-message %)}))))
           #(reset! gone true))))
     [server p tick])
    [answer #(set-tick inc)]))

(defn- right-side [{:keys [server selected item readme trash? label on-gone on-restored]}]
  (let [items @(rf/subscribe [:bookmarks])
        shown (or (:path item) selected)]
    (cond
      trash? [:f> trash/view {:server server :on-restored on-restored}]
      selected [preview/pane {:server server :item item :folder selected :readme readme :label label :on-gone on-gone
                              :bookmarked? (bookmarks/bookmarked? items server shown)}]
      :else [:p.muted.fd-detail "Pick a folder."])))

(defn- middle
  "The contents pane of the selected folder; picked: the picked item's path."
  [{:keys [server selected answer hidden? picked set-picked set-trash go!]}]
  (when selected
    [:f> contents/pane {:server server :folder selected :error (:error answer) :more (:more answer)
                        :items (some-> (:entries answer) (pure/shown-items hidden?))
                        :picked picked :pick! (fn [p] (set-trash false) (set-picked p)) :open! go!}]))

(defn- left-side
  "The filter field and the tree (once the work folder is known)."
  [{:keys [loaded cwd error hidden? selected reveal set-reveal load! go! pick! server]}]
  (let [[text set-text] (h/use-state "")
        pinned (set (map :folder (bookmarks/on-server @(rf/subscribe [:bookmarks]) server)))]
    [:aside.fd-left
     [filter-field text set-text go!]
     (when cwd
       [:f> tree/tree {:loaded loaded :cwd cwd :hidden? hidden? :pinned pinned :term (pure/filter-term text)
                       :selected selected :reveal reveal :revealed! #(set-reveal nil) :on-open #(load! % false)
                       :on-select pick! :on-pin #(rf/dispatch [:bookmarks/toggle server %])}])
     (when error [:p.fd-warn error])]))

(defn- body
  "The three panes: the tree, the selected folder's contents, the preview (or the Trash)."
  [{:keys [server selected picked set-picked set-selected refresh!] :as st}]
  (let [[answer reload!] (use-contents server selected)
        item (some #(when (= picked (:path %)) %) (:entries answer))]
    [:div.fd-body
     [:f> left-side (assoc st :pick! (fn [p] ((:set-trash st) false) (set-picked nil) (set-selected p)))]
     [middle (assoc st :answer answer)]
     [:section.fd-right
      [right-side (assoc st :item item :readme (pure/readme-of (:entries answer))
                         :on-gone (fn [] (if item
                                           (do (set-picked nil) (reload!))
                                           (do (refresh! selected) (set-selected (pure/parent-of selected)))))
                         :on-restored (fn [p] (refresh! p) (reload!)))]]]))

(defn- browser
  "The server's bookmark cards and the three panes. start: the folder selected first (nil: the work
  folder)."
  [{:keys [server start]}]
  (let [[{:keys [loaded cwd error]} load!] (use-listings server)
        [selected set-selected] (h/use-state nil)
        [picked set-picked] (h/use-state nil)
        [reveal set-reveal] (h/use-state nil)
        [hidden? set-hidden] (h/use-state false)
        [trash? set-trash] (h/use-state false)
        go! (fn [p] (set-trash false) (set-picked nil) (set-selected p) (doseq [up (pure/lineage p)] (load! up false)) (set-reveal p))
        label @(rf/subscribe [:server-label server])]
    (use-first-load! load! go! start)
    [:<>
     [top-bar {:label label :hidden? hidden? :set-hidden set-hidden :trash? trash? :set-trash set-trash}]
     [:f> cards server selected go!]
     [:f> body {:server server :loaded loaded :cwd cwd :error error :hidden? hidden? :selected selected
                :set-selected set-selected :picked picked :set-picked set-picked :reveal reveal :set-reveal set-reveal
                :load! load! :go! go! :trash? trash? :set-trash set-trash :label label
                :refresh! (fn [p] (when-let [up (pure/parent-of p)] (load! up true)))}]]))

(defn- close-on-escape
  "Escape closes the modal (before the shell's Escape, which would stop the open chat's turn),
  unless a dialog is open: the dialog takes it."
  [^js e]
  (when (and (= "Escape" (.-key e)) (not (js/document.querySelector ".confirm-backdrop")))
    (.preventDefault e)
    (rf/dispatch [:folders/open nil])))

(defn- modal-view [{:keys [server folder]}]
  (hooks/use-key close-on-escape true [])
  [:div.fd-backdrop {:on-click #(rf/dispatch [:folders/open nil])}
   [:div.fd-screen {:role "dialog" :aria-modal true :aria-label "Folders" :on-click #(.stopPropagation %)}
    ^{:key server} [:f> browser {:server server :start folder}]]])

(defn screen
  "The Folders modal while it is open ({:server :folder :chat} from the header)."
  []
  (when-let [opts @(rf/subscribe [:folders])] [:f> modal-view opts]))
