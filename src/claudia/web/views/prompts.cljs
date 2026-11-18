(ns claudia.web.views.prompts
  "Prompt profiles (README decision 60): the picker in the chat's header (beside model and effort)
  and the Prompts window, where a server's profiles are added, edited and deleted. A profile's text
  goes after Claudia's part of the system prompt of every chat that picks it."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.api :as api]
            [claudia.web.hooks :as hooks]))

;; ---- the server's profiles ----

(rf/reg-event-db :prompts/list (fn [db [_ server ps]] (assoc-in db [:prompts server] (vec ps))))
(rf/reg-sub :prompts/list (fn [db [_ server]] (get-in db [:prompts server])))
(rf/reg-event-db :prompts/open (fn [db [_ server]] (assoc db :prompts-open server)))
(rf/reg-sub :prompts/open (fn [db] (:prompts-open db)))

(defn- took! [server p]
  (.then p #(rf/dispatch [:prompts/list server (:profiles %)])))

(defn load! [server]
  (.catch (took! server (api/fetch-json (api/url server "prompts" nil))) (fn [_])))

(defn- profile-url [server n] (api/url server (str "prompts/" (js/encodeURIComponent n)) nil))

(defn- save! [server n text] (took! server (api/call-json "PUT" (profile-url server n) {:text text})))

(defn- delete! [server n] (.catch (took! server (api/call-json "DELETE" (profile-url server n) nil)) (fn [_])))

;; ---- the picker ----

(def edit-value "The picker's last option: opens the Prompts window." "\u0001edit")

(defn picked-value
  "What the picker shows for the chat's pick: its profile, or none (\"\") when the pick is not one
  of profiles (deleted, or still loading)."
  [pick profiles]
  (if (some #(= pick (:name %)) profiles) pick ""))

(defn pick
  "The chat's prompt profile; picking \"Edit prompts…\" opens the Prompts window."
  [server settings put!]
  (let [profiles @(rf/subscribe [:prompts/list server])]
    (h/use-effect (fn [] (load! server) nil) [server])
    [:label.pick.prompt {:title (str "Prompt profile: " (or (:prompt settings) "none")
                                     " (applied after this chat's turn)")}
     [:select {:value (picked-value (:prompt settings) profiles)
               :on-change #(let [v (.. % -target -value)]
                             (if (= v edit-value)
                               (rf/dispatch [:prompts/open server])
                               (put! {:prompt (not-empty v)})))}
      [:option {:value ""} "No profile"]
      (for [p profiles] ^{:key (:name p)} [:option {:value (:name p)} (:name p)])
      [:option {:value edit-value} "Edit prompts…"]]]))

;; ---- the Prompts window ----

(defn- profile-entry
  "One profile: its name, its text to edit, Save (when changed) and Delete."
  [server {n :name text :text}]
  (let [[draft set-draft] (h/use-state text)
        [error set-error] (h/use-state nil)]
    (h/use-effect (fn [] (set-draft text) nil) [text])
    [:section.prompt-entry
     [:div.block-head [:b n]
      (when (not= draft text)
        [:button.tpl-delete.restore {:type "button"
                                     :on-click #(.catch (save! server n draft) (fn [e] (set-error (.-message e))))}
         "save"])
      [:button.tpl-delete {:type "button" :on-click #(delete! server n)} "delete"]]
     [:textarea.prompt-text {:value draft :rows 8 :on-change #(set-draft (.. % -target -value))}]
     (when error [:p.error error])]))

(defn- new-profile
  "A name field and Add: a new profile with an empty text, to fill in its entry."
  [server profiles]
  (let [[n set-n] (h/use-state "")
        taken? (some #(= (str/trim n) (:name %)) profiles)
        add! #(when-not (or (str/blank? n) taken?) (save! server (str/trim n) "") (set-n ""))]
    [:form.prompt-new {:on-submit (fn [e] (.preventDefault e) (add!))}
     [:input {:value n :max-length 60 :placeholder "New profile name (e.g. Reviewer)"
              :on-change #(set-n (.. % -target -value))}]
     [:button {:type "submit" :disabled (or (str/blank? n) taken?)} "Add"]]))

(defn- window-head [close]
  [:header
   [:h2 "Prompts"]
   [:p.muted "Profiles a chat can pick in its header. The text goes after Claudia's own instructions; a chat on a profile takes a change after its turn."]
   [:button.icon {:type "button" :on-click close :title "Close (Esc)"} "×"]])

(defn window
  "The server's prompt profiles to add, edit and delete. Escape or a click outside closes it."
  [server close]
  (let [profiles @(rf/subscribe [:prompts/list server])]
    (h/use-effect (fn [] (load! server) nil) [server])
    (hooks/use-key (fn [e] (when (= "Escape" (.-key e)) (close))) false [close])
    [:div.gallery-backdrop {:on-click close}
     [:div.gallery.prompts {:role "dialog" :aria-label "Prompts" :on-click #(.stopPropagation %)}
      (window-head close)
      [:f> new-profile server profiles]
      (for [p profiles] ^{:key (:name p)} [:f> profile-entry server p])]]))

(defn overlay
  "The Prompts window, while one is open."
  []
  (when-let [server @(rf/subscribe [:prompts/open])]
    [:f> window server #(rf/dispatch [:prompts/open nil])]))
