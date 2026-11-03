(ns claudia.web.views.codeforms
  "The page's side of the code forms and templates: which renderer a form block gets, the
  \"save as template\" button on freeform badges, the per-chat form row (each kind allowed ->
  required -> off, and the picture's columns), the Forms gallery, and the settings frame."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.shared.forms :as forms]
            [claudia.web.api :as api]
            [claudia.web.views.editor :as editor]
            [claudia.web.views.files :as files]
            [claudia.web.views.flow :as flow]
            [claudia.web.views.forms :as base-forms]
            [claudia.web.views.frame :as frame]
            [claudia.web.views.git :as git]
            [claudia.web.views.graph :as graph]
            [claudia.web.events :as events]
            [claudia.web.ws :as ws]))

;; ---- state: a chat's settings come with `chat` and with `settings` frames ----

(defmethod events/on-frame "settings" [db server {:keys [chat settings]}]
  {:db (assoc-in db [:chats [server chat] :settings] settings)})

(rf/reg-event-fx
 :codeforms/settings
 ;; The form row or the columns changed: shown at once, sent to the chat's runner.
 (fn [{:keys [db]} [_ [server chat :as k] change]]
   {:db (update-in db [:chats k :settings] merge change)
    :ws/send [server (merge {:type "settings" :chat chat} change)]}))

(rf/reg-sub :codeforms/settings (fn [db [_ k]] (get-in db [:chats k :settings])))

;; ---- which renderer ----

(defn form-view
  "A form block's body. ctx: {:server :chat :block :live}."
  [form data ctx]
  (case form
    "files" [:div.form-view.form-files [:f> files/files-form data ctx]]
    "git" [:div.form-view.form-git [:f> git/git-form data ctx]]
    "editor" [:div.form-view.form-editor [:f> editor/editor-form data ctx]]
    "flow" [:div.form-view.form-flow [:f> flow/flow-form data]]
    [base-forms/form-view form data]))

;; ---- "save as template" on a freeform block's badge ----

(defn template-save
  "The badge's button: a name field, then the block is kept as a template on the chat's server."
  [server chat block-id]
  (let [[state set-state] (h/use-state nil) ; nil | :naming | {:note text}
        [name set-name] (h/use-state "")
        save (fn []
               (when (seq (str/trim name))
                 (-> (api/post-json (api/url server (str "chats/" (js/encodeURIComponent chat) "/blocks/" (js/encodeURIComponent block-id) "/template") nil)
                                    {:name (str/trim name)})
                     (.then (fn [r] (set-state {:note (if (:ok r) "saved" (:text r))})))
                     (.catch (fn [e] (set-state {:note (.-message e)}))))
                 (js/setTimeout #(set-state nil) 2500)))]
    (cond
      (= state :naming)
      [:form.tpl-form {:on-submit (fn [e] (.preventDefault e) (save))}
       [:input {:auto-focus true :value name :placeholder "Template name" :on-change #(set-name (.. % -target -value))
                :on-key-down #(when (= "Escape" (.-key %)) (set-state nil))}]
       [:button {:type "submit"} "save"]]
      (map? state) [:span.tpl-note (:note state)]
      :else [:button.tpl-save {:type "button" :on-click #(set-state :naming)} "save as template"])))

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
   "editor" "M2 3h16v14H2zM2 6h16M5 9l-2 2 2 2M8 14l3-6M14 9l2 2-2 2"
   "flow" "M2 4h4v4H2zM8 4h4v4H8zM14 4h4v4h-4zM4 12h6v4H4zM12 14h4M14 12l2 2-2 2"
   "freeform" "M4 15c2-6 4-9 6-9s1 5 3 5 2-3 3-5M5 17h10"
   "questions" "M7.5 7.5a2.5 2.5 0 1 1 3.6 2.2c-.7.4-1.1.9-1.1 1.7v.6M10 15v.2"})

(def ^:private extra
  {"graph" {:label "Graph" :when "Circles, boxes and arrows: how things connect."}
   "freeform" {:label "Freeform" :when "Claude's own page: interactive, playful, made for this one thing. Required: Claude gets creative where it helps. Off: only graphs and forms."}
   "questions" {:label "Questions" :when "Claude asking you with buttons. Off: it decides itself and says in a line what it chose."}})

;; The user's own tools: a click shows one in the picture for the chat's folder (required) or takes
;; it away (allowed). Never off: Claude may always draw them too.
(def ^:private tool-forms #{"files" "git"})

(defn- label-of [f] (or (get-in extra [f :label]) (get-in forms/specs [f :label]) (str/capitalize f)))
(defn- when-of [f] (or (get-in extra [f :when]) (get-in forms/specs [f :when]) (get forms/base-when f) ""))
(defn- sample-of [f] (or (get-in forms/specs [f :sample]) (get forms/base-samples f)))

(defn- say [f state]
  (cond (= f "questions") (if (= state "off") "off: Claude decides itself" "allowed: Claude may ask you")
        (tool-forms f) (if (= state "required") "in the picture: click to take it away" "yours: click to show it in the picture")
        :else (case state "required" "required: always in the picture" "off" "off: Claude can't draw it" "allowed: Claude may draw it")))

(defn- next-state [f now]
  (cond (tool-forms f) (when-not (= now "required") "required")
        (= f "questions") (when-not (= now "off") "off")
        :else (case now "allowed" "required" "required" "off" nil)))

(defn- col-glyph [n]
  (let [w (/ (- 16 (* (dec n) 2)) n)]
    (into [:svg {:viewBox "0 0 20 14" :aria-hidden true}]
          (for [i (range n)] ^{:key i} [:rect {:x (+ 2 (* i (+ w 2))) :y 2 :width w :height 10 :rx 1.5}]))))

(defn form-row
  "The open chat's form row: one chalk circle per kind (hover shows its sample; a click goes allowed
  -> required -> off for this chat) and the picture's columns. available: the forms its server has."
  [k available read-only]
  (let [settings @(rf/subscribe [:codeforms/settings k])
        choice (or (:forms settings) {})
        choice (into {} (for [[f v] choice] [(if (keyword? f) (name f) (str f)) v]))
        cols (or (:cols settings) 0)
        [peek set-peek] (h/use-state nil)
        avail (set available)
        shown (concat ["graph"] (filter #(and (avail %) (not (tool-forms %))) available) ["freeform" "questions"])
        tools (filter #(and (avail %) (tool-forms %)) available)
        cycle (fn [f]
                (let [now (get choice f "allowed")
                      to (next-state f now)]
                  (rf/dispatch [:codeforms/settings k {:forms (if to (assoc choice f to) (dissoc choice f))}])))
        dot (fn [f]
              (let [state (get choice f "allowed")]
                ^{:key f}
                [:button.form-dot {:type "button" :class [(when (tool-forms f) "tool") state] :disabled read-only
                                   :on-click #(cycle f) :on-mouse-enter #(set-peek f) :on-focus #(set-peek f)
                                   :aria-label (str (label-of f) ": " (say f state))}
                 [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d (get glyphs f "M4 4h12v12H4z")}]]]))]
    [:div.form-row {:on-mouse-leave #(set-peek nil)}
     (into [:div.form-dots] (map dot shown))
     (when (seq tools) (into [:div.form-tools] (map dot tools)))
     [:div.col-pick {:role "radiogroup" :aria-label "Columns"}
      (for [n [0 1 2 3]]
        ^{:key n}
        [:button.col-btn {:type "button" :class [(str "c" n) (when (= cols n) "on")] :role "radio" :aria-checked (= cols n) :disabled read-only
                          :on-click #(rf/dispatch [:codeforms/settings k {:cols n}])
                          :title (if (pos? n) (str n " column" (when (> n 1) "s")) "Claude's layout")}
         (if (pos? n) [col-glyph n] "auto")])]
     (when peek
       (let [state (get choice peek "allowed")]
         [:div.form-peek
          [:div.form-peek-head [:b (label-of peek)] [:span.form-peek-state {:class state} (say peek state)]]
          [:p (when-of peek)]
          (when-let [s (and (not (extra peek)) (sample-of peek))]
            [:div.form-peek-sample [form-view peek s {}]])
          [:p.form-peek-hint (cond (= peek "questions") "click: allowed ↔ off · this chat only"
                                   (tool-forms peek) "the chat's folder, live · Claude may draw it too"
                                   :else "click: allowed → required → off · this chat only")]]))]))

;; ---- the Forms gallery ----

(def ^:private sample-graph
  {:id "graph" :kind "graph"
   :nodes [{:id "you" :label "You" :color "violet"} {:id "page" :label "Page" :shape "box" :color "blue"}
           {:id "claude" :label "Claude" :color "amber" :state "active"}]
   :edges [{:from "you" :to "page" :label "type"} {:from "page" :to "claude" :label "turn"}]
   :groups []})

(def ^:private sample-freeform
  "<div style=\"display:flex;gap:10px;align-items:center\"><span style=\"font:700 20px var(--hand)\">Anything else</span><input type=\"range\" min=\"0\" max=\"10\" value=\"6\"><span style=\"color:var(--muted)\">sliders, mockups, a table, code…</span></div>")

(defn- entry [n kind label when-text body & [action]]
  [:section.g-entry
   [:div.block-head
    [:span.g-num (str "#" n)]
    [:span.block-badge {:class (str "b-" (case kind "graph" "graph" "html" "freeform" "form"))} [:b label]]
    action]
   (when (seq when-text) [:p.g-when when-text])
   [:div.g-sample body]])

(defn gallery
  "Every kind of block Claude can draw, each on sample data with its when-line, and the templates
  saved on this server. Escape or a click outside closes it."
  [server close]
  (let [[info set-info] (h/use-state nil)
        [deleting set-deleting] (h/use-state nil)
        load (fn [] (-> (api/fetch-json (api/url server "forms" nil))
                        (.then set-info)
                        (.catch (fn [_] (set-info {:forms (vec (concat ["pipeline" "timeline" "compare" "chart" "stats"] (remove forms/opt-in forms/names))) :templates []})))))]
    (h/use-effect (fn [] (load) nil) [server])
    (h/use-effect
     (fn []
       (let [k (fn [e] (when (= "Escape" (.-key e)) (if deleting (set-deleting nil) (close))))]
         (js/window.addEventListener "keydown" k)
         #(js/window.removeEventListener "keydown" k)))
     [deleting])
    (let [fs (:forms info)
          templates (:templates info)
          n0 (+ 2 (count fs))]
      [:div.gallery-backdrop {:on-click close}
       [:div.gallery {:role "dialog" :aria-label "Forms" :on-click #(.stopPropagation %)}
        [:header
         [:h2 "Forms"]
         [:p.muted "What Claude can draw on the right. Ask for one by its name, or for changes to a block by the name on its badge."]
         [:button.icon {:type "button" :on-click close :title "Close (Esc)"} "×"]]
        (if-not info
          [:p.muted "Loading…"]
          [:<>
           [:div.g-list
            [entry 1 "graph" "graph" "Things and how they connect: circles, boxes, arrows, groups. The default."
             [:f> graph/graph-view sample-graph]]
            (for [[i f] (map-indexed vector fs)]
              ^{:key f}
              [entry (+ i 2) "form" f (when-of f) [form-view f (sample-of f) {}]])
            [entry n0 "html" "freeform" "Claude's own small page, for what no graph or form shows. Save a good one as a template from its badge."
             [:f> frame/page-frame sample-freeform nil]]]
           [:h3.g-section "Templates"]
           (if (empty? templates)
             [:p.muted "None yet. Use \"save as template\" on a freeform block, or ask Claude to save one."]
             [:div.g-list
              (for [[i t] (map-indexed vector templates)]
                ^{:key (:name t)}
                [entry (+ n0 1 i) "html" (:name t) (:description t)
                 [:f> frame/page-frame (:html t) (:parts t)]
                 [:button.tpl-delete {:type "button" :on-click #(set-deleting (:name t))} "delete"]])])])]
       (when deleting
         [:div.confirm-backdrop {:on-click (fn [e] (.stopPropagation e) (set-deleting nil))}
          [:div.confirm-card {:role "alertdialog" :aria-modal true :on-click #(.stopPropagation %)}
           [:h3 "Delete this template?"]
           [:p [:b deleting] " goes from the Forms window and Claude can't draw it by name any more. Pictures that already use it keep their copy."]
           [:div.confirm-actions
            [:button.confirm-no {:type "button" :auto-focus true :on-click #(set-deleting nil)} "Keep it"]
            [:button.confirm-yes {:type "button"
                                  :on-click (fn []
                                              (-> (api/fetch-json (api/url server (str "templates/" (js/encodeURIComponent deleting)) nil) {:method "DELETE"})
                                                  (.finally load))
                                              (set-deleting nil))}
             "Delete"]]]])])))

;; ---- the picture header's part: the form row, the gallery button ----

(defn picture-tools
  "Over the picture of the open chat: the Forms button and the form row."
  [{:keys [server ck]}]
  (let [[open set-open] (h/use-state false)
        [available set-available] (h/use-state nil)]
    (h/use-effect
     (fn []
       (when server
         (-> (api/fetch-json (api/url server "forms" nil))
             (.then #(set-available (:forms %)))
             (.catch (fn [_]))))
       nil)
     [server])
    [:<>
     (when server [:button.forms-btn {:type "button" :on-click #(set-open true) :title "Every form Claude can draw, and the saved templates"} "Forms"])
     (when (and ck available) [:f> form-row ck available false])
     (when open [:f> gallery server #(set-open false)])]))
