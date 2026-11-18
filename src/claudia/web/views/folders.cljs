(ns claudia.web.views.folders
  "The Folders screen (README decisions 61 to 64), over the chats: on the left the person's servers
  as pills, their bookmarks there (greyed \"missing\" when the folder is gone) and the tree from
  \"/\" with the work folder open; on the right the selected folder (claudia.web.views.folder-detail)
  or the Trash (claudia.web.views.folder-trash). Only the person's own servers: a view never
  reaches these calls."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.folders :as folders]
            [claudia.web.hooks :as hooks]
            [claudia.shared.bookmarks :as bookmarks]
            [claudia.web.views.folder-detail :as detail]
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
  "Loads \"/\", then every folder down to the work folder, and selects the work folder (go!)."
  [load! go!]
  (h/use-effect
   (fn []
     (some-> (load! "/" false)
             (.then (fn [cwd] (when cwd (go! cwd)))))
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

(defn- server-pills [server set-server]
  (let [servers @(rf/subscribe [:server-list])]
    (when (> (count servers) 1)
      [:div.fd-pills
       (for [{nm :name label :label} servers]
         ^{:key nm}
         [:button.fd-pill {:class (when (= nm server) "on") :on-click #(set-server nm)} (or label nm)])])))

(defn- bookmark-list [server go!]
  (let [rows (bookmarks/on-server @(rf/subscribe [:bookmarks]) server)
        missing (use-missing server rows)]
    [:div.fd-marks
     [:div.fd-head "Bookmarks"]
     (if (empty? rows)
       [:p.muted.fd-none "Pin a folder to keep it here."]
       (for [{:keys [folder label missing]} (pure/bookmark-rows rows server missing)]
         ^{:key folder}
         [:button.fd-mark {:class (when missing "missing") :title folder
                           :on-click #(when-not missing (go! folder))}
          [:span "★ " label] (when missing [:span.fd-missing "missing"])]))]))

(defn- top-bar [{:keys [hidden? set-hidden trash? set-trash]}]
  [:header.fd-bar
   [:h1.fd-heading "Folders"]
   [:label.fd-switch [:input {:type "checkbox" :checked hidden? :on-change #(set-hidden (not hidden?))}] "show hidden"]
   [:button.fd-btn {:class (when trash? "on") :on-click #(set-trash (not trash?))} "Trash"]
   [:button.fd-close {:aria-label "Close Folders" :title "Close (Escape)" :on-click #(rf/dispatch [:folders/open false])} "×"]])

(defn- right-side [{:keys [server selected trash? label on-gone on-restored]}]
  (let [items @(rf/subscribe [:bookmarks])]
    (cond
      trash? [:f> trash/view {:server server :on-restored on-restored}]
      selected ^{:key (str server selected)}
      [:f> detail/panel {:server server :p selected :label label :on-gone on-gone
                         :bookmarked? (bookmarks/bookmarked? items server selected)}]
      :else [:p.muted.fd-detail "Pick a folder."])))

(defn- browser
  "One server's bookmarks, tree and the selected folder's panel."
  [{:keys [server set-server]}]
  (let [[{:keys [loaded cwd error]} load!] (use-listings server)
        [selected set-selected] (h/use-state nil)
        [reveal set-reveal] (h/use-state nil)
        [hidden? set-hidden] (h/use-state false)
        [trash? set-trash] (h/use-state false)
        go! (fn [p] (set-trash false) (set-selected p) (doseq [up (pure/lineage p)] (load! up false)) (set-reveal p))
        refresh! (fn [p] (when-let [up (pure/parent-of p)] (load! up true)))]
    (use-first-load! load! go!)
    [:<>
     [top-bar {:hidden? hidden? :set-hidden set-hidden :trash? trash? :set-trash set-trash}]
     [:div.fd-body
      [:aside.fd-left
       [server-pills server set-server]
       [:f> bookmark-list server go!]
       (when cwd
         [:f> tree/tree {:loaded loaded :cwd cwd :hidden? hidden? :selected selected :reveal reveal
                         :revealed! #(set-reveal nil) :on-open #(load! % false)
                         :on-select (fn [p] (set-trash false) (set-selected p))}])
       (when error [:p.fd-warn error])]
      [:section.fd-right
       [right-side {:server server :selected selected :trash? trash? :label @(rf/subscribe [:server-label server])
                    :on-gone (fn [] (refresh! selected) (set-selected (pure/parent-of selected)))
                    :on-restored (fn [p] (refresh! p))}]]]]))

(defn- close-on-escape
  "Escape closes the screen (before the shell's Escape, which would stop the open chat's turn),
  unless a dialog is open: the dialog takes it."
  [^js e]
  (when (and (= "Escape" (.-key e)) (not (js/document.querySelector ".confirm-backdrop")))
    (.preventDefault e)
    (rf/dispatch [:folders/open false])))

(defn- screen-view []
  (let [servers @(rf/subscribe [:server-list])
        first-server @(rf/subscribe [:new-server])
        [server set-server] (h/use-state (if (some #(= first-server (:name %)) servers) first-server (:name (first servers))))]
    (hooks/use-key close-on-escape true [])
    [:div.fd-screen {:role "dialog" :aria-label "Folders"}
     (if server
       ^{:key server} [:f> browser {:server server :set-server set-server}]
       [:p.muted.fd-detail "No server of yours to browse."])]))

(defn screen
  "The Folders screen while it is open."
  []
  (when @(rf/subscribe [:folders?]) [:f> screen-view]))
