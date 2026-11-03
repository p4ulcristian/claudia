(ns claudia.web.views.codeforms
  "The page's side of the code forms and templates: which renderer a form block gets, the
  \"save as template\" button on freeform badges, the per-chat form row (each kind allowed ->
  required -> off, and the picture's columns), the Forms gallery, and the settings frame."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.shared.forms :as forms]
            [claudia.web.api :as api]
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
            [claudia.web.events :as events]))

;; ---- state: a chat's settings come with `chat` and with `settings` frames ----

(defmethod events/on-frame "picture_settings" [db server {:keys [chat settings]}]
  {:db (assoc-in db [:chats [server chat] :pictureSettings] settings)})

(rf/reg-event-fx
 :codeforms/settings
 ;; The form row or the columns changed: shown at once, sent to the chat's runner.
 (fn [{:keys [db]} [_ [server chat :as k] change]]
   {:db (update-in db [:chats k :pictureSettings] merge change)
    :ws/send [server (merge {:type "picture_settings" :chat chat} change)]}))

(rf/reg-sub :codeforms/settings (fn [db [_ k]] (get-in db [:chats k :pictureSettings])))

;; ---- which renderer ----

(defn form-view
  "A form block's body. ctx: {:server :chat :block :live}."
  [form data ctx]
  (case form
    "files" [:div.form-view.form-files [:f> files/files-form data ctx]]
    "git" [:div.form-view.form-git [:f> git/git-form data ctx]]
    "clojure" [:div.form-view.form-clojure [:f> clojure/clojure-form data ctx]]
    "calls" [:div.form-view.form-calls [:f> calls/calls-form data ctx]]
    "editor" [:div.form-view.form-editor [:f> editor/editor-form data ctx]]
    "flow" [:div.form-view.form-flow [:f> flow/flow-form data]]
    ;; "code" was a form of its own until 2026-10-22: pictures saved before still show theirs.
    "code" [:div.form-view.form-code [:f> code/code-view data]]
    [base-forms/form-view form data (:block ctx)]))

;; ---- "save as template" on a freeform block's badge ----

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
                                   :on-click #(set-nm (or (:name b) (when (= "html" (:kind b)) (:template b)) id))}
                 "save as template"]
      :else [:span.tpl-form
             [:input {:auto-focus true :value nm :placeholder "Template name"
                      :on-change #(set-nm (.. % -target -value))
                      :on-key-down #(case (.-key %) "Enter" (save) "Escape" (set-nm nil) nil)}]
             [:button {:type "button" :on-click save} "save"]])))

;; ---- the form row ----

(def ^:private glyphs
  {"graph" "M5 6.5a2 2 0 1 0 0 .01M15 6.5a2 2 0 1 0 0 .01M10 15a2 2 0 1 0 0 .01M7 6.5h6M6 8.3l3 5M14 8.3l-3 5"
   "pipeline" "M3 10h3M14 10h3M7 7h6v6H7z"
   "timeline" "M10 3v14M10 6h5M10 11h-5M10 15h4"
   "compare" "M3 5h6v10H3zM11 5h6v10h-6zM5 8h2M13 8h2M5 11h2M13 11h2"
   "chart" "M4 16V9M8 16V5M12 16v-6M16 16V7M3 16.5h14"
   "stats" "M4 14l3-3 3 2 5-6M4 16h12"
   "files" "M3 5.5h5l1.5 1.5H17v8.5H3zM6 10h8M6 13h5"
   "git" "M7 4v12M7 5.2a1.6 1.6 0 1 0 0 .01M7 15a1.6 1.6 0 1 0 0 .01M14 6.5a1.6 1.6 0 1 0 0 .01M14 8.2c0 3-7 2.5-7 5"
   "clojure" "M6 6a2.5 2.5 0 1 0 0 .01M14 5a1.8 1.8 0 1 0 0 .01M13 14.5a2.8 2.8 0 1 0 0 .01M8.4 6.4l3.8-.9M7.6 7.8l3.4 4.6M14 6.8l-.5 4.9"
   "calls" "M3 4h6M5 4v12M5 8h5M5 12h5M5 16h5M12 8a1.6 1.6 0 1 0 0 .01M12 12a1.6 1.6 0 1 0 0 .01M12 16a1.6 1.6 0 1 0 0 .01M14 8h3M14 12h3"
   "editor" "M2 3h16v14H2zM2 6h16M5 9l-2 2 2 2M8 14l3-6M14 9l2 2-2 2"
   "scene3d" "M10 3l6 3v8l-6 3-6-3V6zM4 6l6 3 6-3M10 9v8"
   "flow" "M2 4h4v4H2zM8 4h4v4H8zM14 4h4v4h-4zM4 12h6v4H4zM12 14h4M14 12l2 2-2 2"
   "freeform" "M4 15c2-6 4-9 6-9s1 5 3 5 2-3 3-5M5 17h10"
   "questions" "M7.5 7.5a2.5 2.5 0 1 1 3.6 2.2c-.7.4-1.1.9-1.1 1.7v.6M10 15v.2"})

;; The non-form circles: label and text.
(def ^:private extra
  {"graph" {:label "Graph" :when "Circles, boxes and arrows: how things connect."}
   "questions" {:label "Questions" :when "Claude asking you with buttons (the full-window choices). Off: it decides itself and says in a line what it chose."}
   "freeform" {:label "Freeform" :when "Claude's own page: interactive, playful, made for this one thing. Required: Claude gets creative where it helps. Off: only graphs and forms."}})

;; The user's own tools, at the foot of the rail in blue: a click shows one in the picture for the
;; chat's folder (required) or takes it away again (allowed). Never off: Claude may always draw them too.
(def ^:private tool-forms ["files" "git" "clojure"])
(def ^:private tool? (set tool-forms))

(defn- label-of [f] (or (get-in extra [f :label]) (forms/label f) f))
(defn- when-of [f] (or (get-in extra [f :when]) (forms/when-line f) ""))

(def ^:private say-form {"allowed" "allowed: Claude may draw it" "required" "required: always in the picture" "off" "off: Claude can't draw it"})
(def ^:private say-q {"allowed" "allowed: Claude may ask you" "off" "off: Claude decides itself"})
(def ^:private say-t {"allowed" "yours: click to show it in the picture" "required" "in the picture: click to take it away" "off" "off: click to show it in the picture"})
(defn- say [f state] (get (cond (= f "questions") say-q (tool? f) say-t :else say-form) state))

(defn- next-state [f now]
  (cond (tool? f) (when-not (= now "required") "required")
        (= f "questions") (case now "allowed" "off" nil)
        :else (case now "allowed" "required" "required" "off" nil)))

(defn- col-glyph [n]
  (let [w (/ (- 16 (* (dec n) 2)) n)]
    (into [:svg {:viewBox "0 0 20 14" :aria-hidden true}]
          (for [i (range n)] ^{:key i} [:rect {:x (+ 2 (* i (+ w 2))) :y 2 :width w :height 10 :rx 1.5}]))))

(defn form-row
  "The open chat's forms: one chalk circle per form (hover shows its sample; a click goes allowed ->
  required -> off for this chat) and the picture's columns. k: the chat's [server chat]; hidden:
  forms the user deleted or this server doesn't have; rail: a column in the right rail (the sample
  opens to its left, level with the circle), else a row over the picture (phones)."
  [k hidden rail]
  (let [settings @(rf/subscribe [:codeforms/settings k])
        choice (into {} (for [[f v] (:forms settings)] [(if (keyword? f) (name f) (str f)) v]))
        cols (or (:cols settings) 0)
        [peek set-peek] (h/use-state nil)
        [peek-top set-peek-top] (h/use-state 0)
        box (h/use-ref nil)
        peek-box (h/use-ref nil)
        hidden (set hidden)
        show (fn [f ^js el]
               (set-peek f)
               (when (and rail (.-current box))
                 (set-peek-top (- (.-top (.getBoundingClientRect el)) (.-top (.getBoundingClientRect (.-current box)))))))
        shown (concat ["graph"] (remove #(or (hidden %) (tool? %)) forms/order) ["freeform" "questions"])
        tools (remove hidden tool-forms)
        cycle (fn [f]
                (let [to (next-state f (get choice f "allowed"))]
                  (rf/dispatch [:codeforms/settings k {:forms (if to (assoc choice f to) (dissoc choice f))}])))
        dot (fn [f]
              (let [state (get choice f "allowed")]
                ^{:key f}
                [:button.form-dot {:type "button" :class [(when (tool? f) "tool") state]
                                   :on-click #(cycle f)
                                   :on-mouse-enter #(show f (.-currentTarget %))
                                   :on-focus #(show f (.-currentTarget %))
                                   :aria-label (str (label-of f) ": " (say f state))
                                   :aria-pressed (when (tool? f) (= state "required"))}
                 [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d (get glyphs f)}]]]))]
    ;; In the rail the sample never runs off the bottom of the window.
    (h/use-layout-effect
     (fn []
       (let [el (.-current peek-box) b (.-current box)]
         (when (and rail el b)
           (let [top (.-top (.getBoundingClientRect b))
                 bottom (or (some-> js/window.visualViewport .-height) js/innerHeight)]
             (set! (.. el -style -top) (str (max (- 8 top) (min peek-top (- bottom 8 top (.-offsetHeight el)))) "px")))))
       nil))
    [:div {:class (if rail "form-rail" "form-row") :ref #(set! (.-current box) %) :on-mouse-leave #(set-peek nil)}
     (into [:div.form-dots] (map dot shown))
     (when (seq tools) (into [:div.form-tools] (map dot tools)))
     ;; Columns for this chat's picture; only the counts that fit the pane show (container queries).
     [:div.col-pick {:role "radiogroup" :aria-label "Columns"}
      (for [n [0 1 2 3]]
        ^{:key n}
        [:button.col-btn {:type "button" :class [(str "c" n) (when (= cols n) "on")] :role "radio" :aria-checked (= cols n)
                          :on-click #(rf/dispatch [:codeforms/settings k {:cols n}])
                          :title (if (pos? n) (str n " column" (when (> n 1) "s")) "Claude's layout")}
         (if (pos? n) [col-glyph n] "auto")])]
     (when peek
       (let [state (get choice peek "allowed")]
         [:div.form-peek {:ref #(set! (.-current peek-box) %)}
          [:div.form-peek-head [:b (label-of peek)] [:span.form-peek-state {:class state} (say peek state)]]
          [:p (when-of peek)]
          (when-not (extra peek)
            [:div.form-peek-sample [form-view peek (forms/sample peek) {}]])
          [:p.form-peek-hint (cond (= peek "questions") "click: allowed ↔ off · this chat only"
                                   (tool? peek) "the chat's folder, live · Claude may draw it too"
                                   :else "click: allowed → required → off · this chat only")]]))]))

;; ---- what a server has: its forms, the ones deleted, its templates (GET /api/forms) ----

(rf/reg-event-db
 :codeforms/info
 (fn [db [_ server info]] (assoc-in db [:codeforms :info server] info)))

(rf/reg-sub :codeforms/info (fn [db [_ server]] (get-in db [:codeforms :info server])))

(defn- load-info! [server]
  (-> (api/fetch-json (api/url server "forms" nil))
      (.then #(rf/dispatch [:codeforms/info server %]))
      (.catch (fn [_]))))

(defn- off-here
  "The forms a server leaves out: deleted in the Forms window, or not on this runner."
  [info]
  (into (set (:hidden info)) (:notHere info)))

;; ---- the Forms gallery ----

(rf/reg-event-db
 :codeforms/gallery
 ;; Opens the Forms window for a server (nil: the open chat's, else the first), or closes it (false).
 (fn [db [_ server]]
   (if (false? server)
     (assoc-in db [:codeforms :gallery] nil)
     (assoc-in db [:codeforms :gallery] (or server (first (:open db)) (first (keys (:servers db))))))))

(rf/reg-sub :codeforms/gallery (fn [db] (get-in db [:codeforms :gallery])))

(def ^:private sample-graph
  {:id "graph" :kind "graph"
   :nodes [{:id "you" :label "You" :color "violet"} {:id "page" :label "Page" :shape "box" :color "blue"}
           {:id "claude" :label "Claude" :color "amber" :state "active"}]
   :edges [{:from "you" :to "page" :label "type"} {:from "page" :to "claude" :label "turn"}]
   :groups []})

(def ^:private sample-freeform
  "<div style=\"display:flex;gap:10px;align-items:center\"><span style=\"font:700 20px var(--hand)\">Anything else</span><input type=\"range\" min=\"0\" max=\"10\" value=\"6\"><span style=\"color:var(--muted)\">sliders, mockups, a table, code…</span></div>")

(defn- badge
  "An entry's badge, as the picture's: the kind (a form by its label), a template by its name."
  [kind label tpl-name]
  [:span.block-badge {:class (str "b-" kind) :title (when-not (#{"graph" "freeform" "template"} kind) (forms/when-line kind))}
   [:b label]
   (when tpl-name [:span.block-name tpl-name])])

;; n: the entry's number in the window, so it can be named in a chat ("#7 and #12 look the same").
(defn- entry [{:keys [n badge deleted on-delete on-restore] when-line :when} body]
  [:section.g-entry {:class (when deleted "deleted")}
   [:div.block-head
    [:span.g-num (str "#" n)] badge
    (when on-delete [:button.tpl-delete {:type "button" :on-click on-delete} "delete"])
    (when on-restore [:button.tpl-delete.restore {:type "button" :on-click on-restore} "restore"])]
   (when when-line [:p.g-when when-line])
   [:div.g-sample body]])

(defn- confirm-delete
  "Asked before a form or template goes: a chalk card over the window; Escape or a click outside keeps it."
  [{:keys [kind name]} on-yes on-no]
  (h/use-effect
   (fn []
     (let [k (fn [e] (when (= "Escape" (.-key e)) (.stopImmediatePropagation e) (on-no)))]
       (js/window.addEventListener "keydown" k true)
       #(js/window.removeEventListener "keydown" k true)))
   [on-yes on-no])
  [:div.confirm-backdrop {:on-click (fn [e] (.stopPropagation e) (on-no))}
   [:div.confirm-card {:role "alertdialog" :aria-modal true :aria-label (str "Delete " kind) :on-click #(.stopPropagation %)}
    [:h3 (str "Delete this " kind "?")]
    (if (= kind "template")
      [:p [:b name] " goes from the Forms window and Claude can't draw it by name any more. Pictures that already use it keep their copy."]
      [:p "Claude stops getting the " [:b name] " form, in open chats too. Pictures that already have one keep showing it, and you can restore it here any time."])
    [:div.confirm-actions
     [:button.confirm-no {:type "button" :auto-focus true :on-click on-no} "Keep it"]
     [:button.confirm-yes {:type "button" :on-click on-yes} "Delete"]]]])

(defn gallery
  "Every kind of block Claude can draw, each on sample data with its when-line, and the templates
  saved on this server. A built-in form can be deleted (Claude no longer gets it) and restored.
  Escape or a click outside closes it."
  [server close]
  (let [info @(rf/subscribe [:codeforms/info server])
        [deleting set-deleting] (h/use-state nil) ; {:kind "form"|"template" :name}
        hidden (vec (:hidden info))
        not-here (set (or (:notHere info)
                          (when (:forms info) (remove (set (:forms info)) (filter forms/opt-in forms/order)))
                          (filter forms/opt-in forms/order)))
        set-hidden! (fn [next]
                      (rf/dispatch [:codeforms/info server (assoc info :hidden next)])
                      (-> (api/fetch-json (api/url server "forms" nil)
                                          {:method "PATCH" :headers {"content-type" "application/json" "accept" "application/json"}
                                           :body (js/JSON.stringify (clj->js {:hidden next}))})
                          (.finally #(load-info! server))))
        remove-template! (fn [nm]
                           (set-deleting nil)
                           (-> (api/fetch-json (api/url server (str "templates/" (js/encodeURIComponent nm)) nil) {:method "DELETE"})
                               (.finally #(load-info! server))))]
    (h/use-effect (fn [] (load-info! server) nil) [server])
    (h/use-effect
     (fn []
       (let [k (fn [e] (when (= "Escape" (.-key e)) (close)))]
         (js/window.addEventListener "keydown" k)
         #(js/window.removeEventListener "keydown" k)))
     [close])
    (let [fs (remove not-here forms/order)
          templates (:templates info)
          n0 (+ 2 (count fs))]
      [:div.gallery-backdrop {:on-click close}
       [:div.gallery.right-scroll {:role "dialog" :aria-label "Forms" :on-click #(.stopPropagation %)}
        [:header
         [:h2 "Forms"]
         [:p.muted "What Claude can draw on the right. Ask for one by its name, or for changes to a block by the name on its badge."]
         [:button.icon {:type "button" :on-click close :title "Close (Esc)"} "×"]]
        [:div.g-list
         [entry {:n 1 :badge [badge "graph" "graph"] :when "Things and how they connect: circles, boxes, arrows, groups. The default."}
          [:f> graph/graph-view sample-graph]]
         (for [[i f] (map-indexed vector fs)
               :let [gone (some #{f} hidden)]]
           ^{:key f}
           [entry {:n (+ i 2) :badge [badge f (str/lower-case (or (forms/label f) f))] :when (forms/when-line f)
                   :deleted gone
                   :on-delete (when-not gone #(set-deleting {:kind "form" :name f}))
                   :on-restore (when gone #(set-hidden! (vec (remove #{f} hidden))))}
            [form-view f (forms/sample f) {}]])
         [entry {:n n0 :badge [badge "freeform" "freeform"] :when "Claude's own small page, for what no graph or form shows. Save a good one as a template from its badge."}
          [:f> frame/page-frame sample-freeform nil]]]
        [:h3.g-section "Templates"]
        (cond
          (nil? info) [:p.muted "Loading…"]
          (empty? templates) [:p.muted "None yet. Use \"save as template\" on a freeform block, or ask Claude to save one."]
          :else
          [:div.g-list
           (for [[i t] (map-indexed vector templates)]
             ^{:key (:name t)}
             [entry {:n (+ n0 1 i) :badge [badge "template" "template" (:name t)] :when (not-empty (:description t))
                     :on-delete #(set-deleting {:kind "template" :name (:name t)})}
              [:f> frame/page-frame (:html t) (:parts t)]])])]
       (when deleting
         [:f> confirm-delete deleting
          (fn []
            (if (= "template" (:kind deleting))
              (remove-template! (:name deleting))
              (do (set-hidden! (conj hidden (:name deleting))) (set-deleting nil))))
          #(set-deleting nil)])])))

(defn gallery-host
  "The Forms window when it is open ([:codeforms/gallery server] opens it). Mounted once, in the app."
  []
  (let [server @(rf/subscribe [:codeforms/gallery])]
    (when server [:f> gallery server #(rf/dispatch [:codeforms/gallery false])])))

;; ---- where the form row goes ----

(defn- use-phone []
  (let [mq (h/use-memo #(js/window.matchMedia "(max-width: 679px)") [])
        [phone set-phone] (h/use-state (.-matches mq))]
    (h/use-effect
     (fn [] (let [f #(set-phone (.-matches mq))] (.addEventListener mq "change" f) #(.removeEventListener mq "change" f)))
     [])
    phone))

(defn- use-info [server]
  (let [info @(rf/subscribe [:codeforms/info server])]
    (h/use-effect (fn [] (when (and server (not info)) (load-info! server)) nil) [server])
    info))

(defn form-rail
  "The right rail's contents on a desktop (the rail mirrors the chats rail): the open chat's forms.
  Nothing on phones (their row is in the picture) and nothing for a read-only viewer."
  []
  (let [{:keys [server ck]} @(rf/subscribe [:open-chat])
        ro @(rf/subscribe [:read-only?])
        phone (use-phone)
        info (use-info server)]
    (when (and ck info (not ro) (not phone))
      [:f> form-row ck (off-here info) true])))

(defn picture-tools
  "Over the picture of the open chat: on phones, the form row (desktops have it in the right rail)."
  [{:keys [server ck]}]
  (let [ro @(rf/subscribe [:read-only?])
        phone (use-phone)
        info (use-info server)]
    [:<>
     (when server [:button.forms-btn {:type "button" :on-click #(rf/dispatch [:codeforms/gallery server]) :title "Every form Claude can draw, and the saved templates"} "Forms"])
     (when (and ck info (not ro) phone) [:f> form-row ck (off-here info) false])]))
