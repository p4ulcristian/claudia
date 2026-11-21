(ns claudia.web.views.prompts
  "Prompt profiles (README decisions 60, 117): the Profiles sheet of the right half (decision 75,
  opened from the rail), where the open chat picks its profile and a server's profiles are added,
  edited and deleted. A profile's text goes after Claudia's part of the system prompt of every chat
  that picks it."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.api :as api]
            [claudia.web.views.chat-context :as chat-context]))

;; ---- the server's profiles ----

(rf/reg-event-db :prompts/list
 (fn [db [_ server answer]]
   (-> db
       (assoc-in [:prompts server] (vec (:profiles answer)))
       (assoc-in [:prompts-built-in server] (:builtIn answer)))))
(rf/reg-sub :prompts/list (fn [db [_ server]] (get-in db [:prompts server])))
(rf/reg-sub :prompts/built-in (fn [db [_ server]] (get-in db [:prompts-built-in server])))

(defn- took! [server p]
  (.then p #(rf/dispatch [:prompts/list server %])))

(defn load! [server]
  (.catch (took! server (api/fetch-json (api/url server "prompts" nil))) (fn [_])))

(defn- profile-url [server n] (api/url server (str "prompts/" (js/encodeURIComponent n)) nil))

(defn- save! [server n text] (took! server (api/call-json "PUT" (profile-url server n) {:text text})))

(defn- delete! [server n] (.catch (took! server (api/call-json "DELETE" (profile-url server n) nil)) (fn [_])))

;; ---- the open chat's pick ----

(defn picked-name
  "The chat's pick when it is one of profiles (else nil: none, deleted, or still loading)."
  [pick profiles]
  (when (some #(= pick (:name %)) profiles) pick))

(defn first-line
  "A profile's text cut to its first line, at most 80 characters (\"\" when blank)."
  [text]
  (let [l (str/trim (first (str/split-lines (str/trim (str text)))))]
    (if (> (count l) 80) (str (subs l 0 79) "…") l)))

(defn- built-in-card
  "The locked part every chat has; Show full text opens its exact words."
  [text]
  (let [[open? set-open] (h/use-state false)]
    [:div.prompt-base
     [:span.lock "🔒 Claudia's built-in prompt"]
     [:p "Short chat replies, explaining in the picture, secrets, working folder, progress bars, images; after Claude Code's own prompt. Can't be changed."]
     (when (seq text)
       [:button.link {:type "button" :on-click #(set-open (not open?))} (if open? "Hide full text ▴" "Show full text ▾")])
     (when open? [:pre.prompt-full text])]))

(defn- profile-option [label line sel? on-pick]
  [:button.prompt-opt {:type "button" :class (when sel? "sel") :on-click on-pick}
   [:span.dot] [:span [:span.nm label] (when (seq line) [:small line])]])

(defn- pick-body [server picked profiles choose!]
  [:<>
   [:div.prompt-sec "Always on"]
   [:f> built-in-card @(rf/subscribe [:prompts/built-in server])]
   [:div.prompt-plus "+"]
   [:div.prompt-sec "Profile on top"]
   [profile-option "None" "only the built-in prompt" (nil? picked) #(choose! nil)]
   (for [p profiles]
     ^{:key (:name p)}
     [profile-option (:name p) (first-line (:text p)) (= picked (:name p)) #(choose! (:name p))])])

(defn- chat-pick
  "The open chat's (or the new chat's) profile, when it runs on server and may be changed: the
  locked built-in part and the profiles to pick from; a pick applies at once (a live chat takes it
  after its turn)."
  [server profiles]
  (let [{:keys [settings put! ro] :as hc} (chat-context/context @(rf/subscribe [:open-chat]) @(rf/subscribe [:read-only?]))]
    (when (and (= server (:server hc)) (not ro))
      [:section.prompt-chat {:role "group" :aria-label "This chat's profile"}
       [:h3 "This chat's profile"]
       [pick-body server (picked-name (:prompt settings) profiles) profiles #(put! {:prompt %})]])))

;; ---- the Profiles sheet ----

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

(defn- window
  "The open chat's pick, then the server's prompt profiles to add, edit and delete."
  [server]
  (let [profiles @(rf/subscribe [:prompts/list server])]
    (h/use-effect (fn [] (load! server) nil) [server])
    [:div.gallery.prompts.rv-pane {:role "region" :aria-label "Profiles"}
     [chat-pick server profiles]
     [:header
      [:h3 (str "Profiles on " @(rf/subscribe [:server-label server]))]
      [:p.muted "The text goes after Claudia's own instructions; a chat on a profile takes a change after its turn."]]
     [:f> new-profile server profiles]
     (for [p profiles] ^{:key (:name p)} [:f> profile-entry server p])]))

(defn pane
  "Profiles in the right half: the open chat's server's, else the new chat's."
  []
  (when-let [server (or (:server @(rf/subscribe [:open-chat])) @(rf/subscribe [:new-server]))]
    ^{:key server} [:f> window server]))
