(ns claudia.web.views.codeforms
  "The page's side of the forms and templates: which renderer a block gets, the \"save as
  template\" button on html badges, and the Forms gallery."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.shared.forms :as forms]
            [claudia.web.api :as api]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.confirm :as confirm]
            [claudia.web.views.calls :as calls]
            [claudia.web.views.clojure :as clojure]
            [claudia.web.views.code :as code]
            [claudia.web.views.editor :as editor]
            [claudia.web.views.files :as files]
            [claudia.web.views.flow :as flow]
            [claudia.web.views.forms :as base-forms]
            [claudia.web.views.frame :as frame]
            [claudia.web.views.git :as git]
            [claudia.web.views.graph :as graph]
            [claudia.web.scene3d :as scene3d]))

;; ---- which renderer ----

(defn form-view
  "A block's body by its form. ctx: {:server :chat :block :live :changed :seq-no} (changed, seq-no:
  what the last draw changed and its number, for a graph's rings)."
  [form data ctx]
  (case form
    "graph" [:f> graph/graph-view data (or (:changed ctx) #{}) (or (:seq-no ctx) 0)]
    "html" [:f> frame/page-frame (:html data) (:parts data)]
    "files" [:div.form-view.form-files [:f> files/files-form data ctx]]
    "git" [:div.form-view.form-git [:f> git/git-form data ctx]]
    "clojure" [:div.form-view.form-clojure [:f> clojure/clojure-form data ctx]]
    "calls" [:div.form-view.form-calls [:f> calls/calls-form data ctx]]
    "editor" [:div.form-view.form-editor [:f> editor/editor-form data ctx]]
    "flow" [:div.form-view.form-flow [:f> flow/flow-form data]]
    ;; "code" was a form of its own until 2026-10-22: pictures saved before still show theirs.
    "code" [:div.form-view.form-code [:f> code/code-view data]]
    "scene3d" [:div.form-view.form-scene3d [scene3d/scene3d-form data (:block ctx) ctx]]
    [base-forms/form-view form data (:block ctx)]))

;; ---- "save as template" on an html block's badge ----

(defn template-save
  "The badge's button: a name field (the block's name to start with), then the block is kept as a
  template on the chat's server. block: the block, or its id."
  [server chat block]
  (let [{:keys [id] :as b} (if (map? block) block {:id block})
        [nm set-nm] (h/use-state nil) ; nil: the button; a string: naming
        [note set-note] (h/use-state nil)
        save (fn []
               (let [n (str/trim (str nm))]
                 (if-not (seq n)
                   (set-nm nil)
                   (-> (api/post-json (api/url server (str "chats/" (js/encodeURIComponent chat) "/blocks/" (js/encodeURIComponent id) "/template") nil)
                                      {:name n})
                       (.then (fn [r] (set-nm nil) (set-note (:text r))))
                       (.catch (fn [_] (set-nm nil) (set-note "Couldn't save the template.")))))))]
    (h/use-effect
     (fn [] (when note (let [t (js/setTimeout #(set-note nil) 3500)] #(js/clearTimeout t))))
     [note])
    (cond
      note [:span.tpl-note note]
      (nil? nm) [:button.tpl-save {:type "button" :title "Save as a template Claude can reuse and change by name"
                                   :on-click #(set-nm (or (:name b) (get-in b [:data :template]) id))}
                 "save as template"]
      :else [:span.tpl-form
             [:input {:auto-focus true :value nm :placeholder "Template name"
                      :on-change #(set-nm (.. % -target -value))
                      :on-key-down #(case (.-key %) "Enter" (save) "Escape" (set-nm nil) nil)}]
             [:button {:type "button" :on-click save} "save"]])))

;; ---- what a server has: its forms, the ones deleted, its templates (GET /api/forms) ----

(rf/reg-event-db
 :codeforms/info
 (fn [db [_ server info]] (assoc-in db [:codeforms :info server] info)))

(rf/reg-sub :codeforms/info (fn [db [_ server]] (get-in db [:codeforms :info server])))

(defn- load-info! [server]
  (-> (api/fetch-json (api/url server "forms" nil))
      (.then #(rf/dispatch [:codeforms/info server %]))
      (.catch (fn [_]))))


(defn- badge
  "An entry's badge, as the picture's: the kind (a form by its label), a template by its name."
  [kind label tpl-name]
  [:span.block-badge {:class (str "b-" kind) :title (when-not (= "template" kind) (forms/when-line kind))}
   [:b label]
   (when tpl-name [:span.block-name tpl-name])])

;; n: the entry's number in the window, so it can be named in a chat ("#7 and #12 look the same").
(defn- entry [{:keys [n deleted on-delete on-restore] badge-el :badge when-line :when} body]
  [:section.g-entry {:class (when deleted "deleted")}
   [:div.block-head
    [:span.g-num (str "#" n)] badge-el
    (when on-delete [:button.tpl-delete {:type "button" :on-click on-delete} "delete"])
    (when on-restore [:button.tpl-delete.restore {:type "button" :on-click on-restore} "restore"])]
   (when when-line [:p.g-when when-line])
   [:div.g-sample body]])

(defn- confirm-delete
  "Asked before a form or template goes."
  [{:keys [kind] nm :name} on-yes on-no]
  [confirm/card {:title (str "Delete this " kind "?") :yes "Delete" :on-yes on-yes :on-no on-no}
   (if (= kind "template")
     [:p [:b nm] " goes from the Forms view and Claude can't draw it by name any more. Pictures that already use it keep their copy."]
     [:p "Claude stops getting the " [:b nm] " form, in open chats too. Pictures that already have one keep showing it, and you can restore it here any time."])])

(defn not-here-of
  "The forms this server doesn't have: as it says, else the opt-in forms it doesn't list."
  [info]
  (set (or (:notHere info)
           (when (:forms info) (remove (set (:forms info)) (filter forms/opt-in forms/order)))
           (filter forms/opt-in forms/order))))

(defn- set-hidden!
  "The forms deleted in the Forms view: shown at once, saved on the server, then read again."
  [server info hidden]
  (rf/dispatch [:codeforms/info server (assoc info :hidden hidden)])
  (-> (api/fetch-json (api/url server "forms" nil)
                      {:method "PATCH" :headers {"content-type" "application/json" "accept" "application/json"}
                       :body (js/JSON.stringify (clj->js {:hidden hidden}))})
      (.finally #(load-info! server))))

(defn- remove-template! [server nm]
  (-> (api/fetch-json (api/url server (str "templates/" (js/encodeURIComponent nm)) nil) {:method "DELETE"})
      (.finally #(load-info! server))))

(def ^:private gallery-head
  [:header
   [:p.muted "What Claude can draw on the right. Ask for one by its name, or for changes to a block by the name on its badge."]])

(defn- form-entries [fs hidden on-hide set-deleting]
  [:div.g-list
   (for [[i f] (map-indexed vector fs)
         :let [gone (some #{f} hidden)
               core? (forms/core f)]]
     ^{:key f}
     [entry {:n (inc i) :badge [badge f (str/lower-case (or (forms/label f) f))] :when (forms/when-line f)
             :deleted gone
             :on-delete (when-not (or gone core?) #(set-deleting {:kind "form" :name f}))
             :on-restore (when gone #(on-hide (vec (remove #{f} hidden))))}
      [form-view f (forms/sample f) {}]])])

(defn- template-entries [info n0 set-deleting]
  (let [templates (:templates info)]
    (cond
      (nil? info) [:p.muted "Loading…"]
      (empty? templates) [:p.muted "None yet. Use \"save as template\" on an html block, or ask Claude to save one."]
      :else
      [:div.g-list
       (for [[i t] (map-indexed vector templates)]
         ^{:key (:name t)}
         [entry {:n (+ n0 1 i) :badge [badge "template" "template" (:name t)] :when (not-empty (:description t))
                 :on-delete #(set-deleting {:kind "template" :name (:name t)})}
          [:f> frame/page-frame (:html t) (:parts t)]])])))

(defn- confirm-yes
  "Yes on the card: a template is deleted on the server, a form hidden."
  [server hidden on-hide deleting set-deleting]
  (fn []
    (if (= "template" (:kind deleting))
      (do (set-deleting nil) (remove-template! server (:name deleting)))
      (do (on-hide (conj hidden (:name deleting))) (set-deleting nil)))))

(defn gallery
  "Every kind of block Claude can draw, each on sample data with its when-line, and the templates
  saved on this server, as a view of the right half (README decision 75). A built-in form can be
  deleted (Claude no longer gets it) and restored."
  [server]
  (let [info @(rf/subscribe [:codeforms/info server])
        [deleting set-deleting] (h/use-state nil) ; {:kind "form"|"template" :name}
        hidden (vec (:hidden info))
        on-hide #(set-hidden! server info %)
        fs (remove (not-here-of info) forms/order)]
    (h/use-effect (fn [] (load-info! server) nil) [server])
    [:div.gallery.rv-pane {:role "region" :aria-label "Forms"}
     gallery-head
     (form-entries fs hidden on-hide set-deleting)
     [:h3.g-section "Templates"]
     (template-entries info (count fs) set-deleting)
     (when deleting
       [confirm-delete deleting (confirm-yes server hidden on-hide deleting set-deleting) #(set-deleting nil)])]))
