(ns claudia.web.views.folders
  "The File Browser in the right half (README decisions 61 to 64, 68 to 72), in place of the picture
  while the header's place pill has it on, on the open chat's server; the top bar's computer toggle
  picks a new chat's computer (README decision 71) and, for a started chat, only which computer is
  browsed (decision 72: on another one Select is gone): on top the server's bookmarks as cards (the
  project's logo as the chats show it, else a folder; name, path, ★ to unpin, \"missing\" greyed;
  a click opens the tree there, Select uses it for the chat); on the left a filter field and the
  tree from \"/\" with the chat's folder (else the work folder) selected; in the middle what that
  folder holds, as a grid or a list (claudia.web.views.folder-contents); on the right the picked
  file's preview, else the folder's README (claudia.web.views.folder-preview; no folder section
  since README decision 74). Nothing here deletes (README decision 69). Only the person's own
  chats: a view has no button for it and never reaches these calls."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.api :as api]
            [claudia.web.folders :as folders]
            [claudia.web.format :as format]
            [claudia.shared.bookmarks :as bookmarks]
            [claudia.web.views.dot :as dot]
            [claudia.web.views.folder-contents :as contents]
            [claudia.web.views.folder-preview :as preview]
            [claudia.web.views.folder-tree :as tree]
            [claudia.web.views.pure.folders :as pure]
            [claudia.web.views.pure.systems :as systems-pure]))

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
  Select uses it for the chat (only on the chat's own computer: use?)."
  [server {:keys [folder label missing]} on? go! chat use?]
  [:div.fd-card {:class [(when missing "missing") (when on? "on")] :title folder
                 :on-click #(when-not missing (go! folder))}
   [:button.fd-card-star {:type "button" :aria-label "Unpin" :title "Unpin"
                          :on-click (fn [e] (.stopPropagation e) (rf/dispatch [:bookmarks/toggle server folder]))} "★"]
   [:span.fd-card-face [dot/project-face server folder pure/folder-icon]]
   [:span.fd-card-name label]
   [:span.fd-card-path.mono [:bdi (format/tilde folder)]]
   (cond
     missing [:span.fd-missing "missing"]
     use? [:button.fd-btn.primary.fd-card-use {:type "button" :on-click (fn [e] (.stopPropagation e) (rf/dispatch [:folders/use folder chat]))}
      "Select"])])

(defn- cards [server selected go! chat use?]
  (let [rows (bookmarks/on-server @(rf/subscribe [:bookmarks]) server)
        missing (use-missing server rows)]
    [:div.fd-cards
     (if (empty? rows)
       [:p.muted.fd-none "No bookmarks on this server yet: ☆ on a folder's row pins it here."]
       (for [b (pure/bookmark-rows rows server missing)]
         ^{:key (:folder b)} [card server b (= selected (:folder b)) go! chat use?]))]))

(defn- filter-field
  "Filters the loaded folders by name; a pasted or typed absolute path (Enter) is revealed."
  [text set-text go!]
  (let [reveal! #(when-let [p (pure/typed-path %)] (go! p))]
    [:input.fd-filter {:type "search" :value text :placeholder "Filter by name, or paste a path"
                       :on-change #(set-text (.. % -target -value))
                       :on-paste #(reveal! (.getData (.-clipboardData %) "text"))
                       :on-key-down #(when (= "Enter" (.-key %)) (reveal! text))}]))

(defn- use-machine
  "The computer's newest sample (GET /hub/servers/<server>/system, once), nil until it answers."
  [server]
  (let [[sample set-sample] (h/use-state nil)]
    (h/use-effect
     (fn []
       (-> (api/fetch-json (str "/hub/servers/" (js/encodeURIComponent server) "/system"))
           (.then set-sample)
           (.catch (fn [_] (set-sample {:error true}))))
       js/undefined)
     [server])
    sample))

(defn- machine-line
  "The shown computer's system, kernel, processor, memory and uptime in one quiet line."
  [server]
  (let [sample (use-machine server)]
    [:p.fd-machine
     (cond (nil? sample) "…"
           (:error sample) "not reached"
           :else (str/join " · " (systems-pure/machine-lines sample)))]))

(defn- server-toggle
  "The computers the File Browser can show, one button each: for a new chat a pick makes it the
  chat's computer and drops the folder picked on the other (README decision 71); for a started chat
  a pick only browses that computer (browse!), the chat stays on its own (home, decision 72)."
  [{:keys [server chat home browse!]}]
  (let [servers @(rf/subscribe [:server-list])
        all @(rf/subscribe [:servers])]
    (if (> (count servers) 1)
      [:div.fd-servers {:role "radiogroup" :aria-label "Computer"}
       (for [{n :name l :label} servers]
         ^{:key n}
         [:button.fd-server {:type "button" :role "radio" :aria-checked (= n server)
                             :class [(when (= n server) "on") (when (= :closed (get-in all [n :status])) "off")]
                             :title (when (and chat (= n home)) "This chat's computer")
                             :on-click #(when-not (= n server) (if chat (browse! n) (rf/dispatch [:new/server n])))}
          l (when (and chat (= n home)) [:span.fd-home " · this chat"])])]
      [:span.fd-on (str " on " @(rf/subscribe [:server-label server]))])))

(defn- top-bar [{:keys [server hidden? set-hidden] :as where}]
  [:header.fd-bar
   [:div.fd-where
    [:div.fd-title-row [server-toggle where]]
    [:f> machine-line server]]
   [:label.fd-switch [:input {:type "checkbox" :checked hidden? :on-change #(set-hidden (not hidden?))}] "show hidden"]])

(defn- use-contents
  "What folder p of server holds ({:entries :more :error}, nil while asked)."
  [server p]
  (let [[answer set-answer] (h/use-state nil)]
    (h/use-effect
     (fn []
       (set-answer nil)
       (when p
         (let [gone (atom false)]
           (-> (folders/contents! server p)
               (.then #(when-not @gone (set-answer %)))
               (.catch #(when-not @gone (set-answer {:error (.-message %)}))))
           #(reset! gone true))))
     [server p])
    answer))

(defn- right-side [{:keys [server selected item readme]}]
  (if selected
    [preview/pane {:server server :item item :readme readme}]
    [:p.muted.fd-detail "Pick a folder."]))

(defn- middle
  "The contents pane of the selected folder; picked: the picked item's path."
  [{:keys [server selected answer hidden? picked set-picked go!]}]
  (when selected
    [:f> contents/pane {:server server :folder selected :error (:error answer) :more (:more answer)
                        :items (some-> (:entries answer) (pure/shown-items hidden?))
                        :picked picked :pick! set-picked :open! go!}]))

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
  "The three panes: the tree, the selected folder's contents, the preview."
  [{:keys [server selected picked set-picked set-selected] :as st}]
  (let [answer (use-contents server selected)
        item (some #(when (= picked (:path %)) %) (:entries answer))]
    [:div.fd-body
     [:f> left-side (assoc st :pick! (fn [p] (set-picked nil) (set-selected p)))]
     [middle (assoc st :answer answer)]
     [:section.fd-right
      [right-side (assoc st :item item :readme (pure/readme-of (:entries answer)))]]]))

(defn- browser
  "The server's bookmark cards and the three panes. start: the folder selected first (nil: the work
  folder); chat: the chat Select works on ([server chat]; nil: the new chat); home: a started chat's
  computer (Select only there), browse!: shows another computer."
  [{:keys [server start chat home browse!]}]
  (let [[{:keys [loaded cwd error]} load!] (use-listings server)
        [selected set-selected] (h/use-state nil)
        [picked set-picked] (h/use-state nil)
        [reveal set-reveal] (h/use-state nil)
        [hidden? set-hidden] (h/use-state false)
        go! (fn [p] (set-picked nil) (set-selected p) (doseq [up (pure/lineage p)] (load! up false)) (set-reveal p))
        use? (or (nil? chat) (= server home))]
    (use-first-load! load! go! start)
    [:<>
     [top-bar {:server server :chat chat :home home :browse! browse! :hidden? hidden? :set-hidden set-hidden}]
     [:f> cards server selected go! chat use?]
     [:f> body {:server server :loaded loaded :cwd cwd :error error :hidden? hidden? :selected selected
                :set-selected set-selected :picked picked :set-picked set-picked :reveal reveal :set-reveal set-reveal
                :load! load! :go! go!}]]))

(defn- shown
  "The browser on the computer shown: a started chat's own at first, another one picked in the top
  bar (decision 72) starts the browser again there, at its work folder."
  [{:keys [server folder ck]}]
  (let [[browsed set-browsed] (h/use-state server)]
    ^{:key browsed} [:f> browser {:server browsed :start (when (= browsed server) folder) :chat ck
                                  :home server :browse! set-browsed}]))

(defn pane
  "The File Browser for the open chat (claudia.web.folders/chat-place: {:server :folder :ck}),
  started again when the chat or its server changes."
  [{:keys [server ck] :as place}]
  [:div.fd-files
   ^{:key (str server "|" ck)} [:f> shown place]])
