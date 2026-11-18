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

(rf/reg-event-db :prompts/list
 (fn [db [_ server answer]]
   (-> db
       (assoc-in [:prompts server] (vec (:profiles answer)))
       (assoc-in [:prompts-built-in server] (:builtIn answer)))))
(rf/reg-sub :prompts/list (fn [db [_ server]] (get-in db [:prompts server])))
(rf/reg-sub :prompts/built-in (fn [db [_ server]] (get-in db [:prompts-built-in server])))
(rf/reg-event-db :prompts/open (fn [db [_ server]] (assoc db :prompts-open server)))
(rf/reg-sub :prompts/open (fn [db] (:prompts-open db)))

(defn- took! [server p]
  (.then p #(rf/dispatch [:prompts/list server %])))

(defn load! [server]
  (.catch (took! server (api/fetch-json (api/url server "prompts" nil))) (fn [_])))

(defn- profile-url [server n] (api/url server (str "prompts/" (js/encodeURIComponent n)) nil))

(defn- save! [server n text] (took! server (api/call-json "PUT" (profile-url server n) {:text text})))

(defn- delete! [server n] (.catch (took! server (api/call-json "DELETE" (profile-url server n) nil)) (fn [_])))

;; ---- the picker: a button with the chosen profile, its popup ----

(defn picked-name
  "The chat's pick when it is one of profiles (else nil: none, deleted, or still loading)."
  [pick profiles]
  (when (some #(= pick (:name %)) profiles) pick))

(defn first-line
  "A profile's text cut to its first line, at most 80 characters (\"\" when blank)."
  [text]
  (let [l (str/trim (first (str/split-lines (str/trim (str text)))))]
    (if (> (count l) 80) (str (subs l 0 79) "…") l)))

(defn popup-place
  "Where the popup opens under a button whose rect is {:left :right :bottom}, w wide, inside a
  window vw wide: right-aligned with the button, kept 6 px inside the window."
  [{:keys [right bottom]} w vw]
  {:top (+ bottom 6) :left (max 6 (min (- right w) (- vw w 6)))})

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

(defn- popup-body [server picked profiles choose!]
  [:<>
   [:div.prompt-sec "Always on"]
   [:f> built-in-card @(rf/subscribe [:prompts/built-in server])]
   [:div.prompt-plus "+"]
   [:div.prompt-sec "Profile on top"]
   [profile-option "None" "only the built-in prompt" (nil? picked) #(choose! nil)]
   (for [p profiles]
     ^{:key (:name p)}
     [profile-option (:name p) (first-line (:text p)) (= picked (:name p)) #(choose! (:name p))])
   [:button.prompt-edit {:type "button" :on-click #(do (choose! :edit) (rf/dispatch [:prompts/open server]))}
    "Edit profiles…"]])

(defn- popup [server picked profiles place choose!]
  (hooks/use-key (fn [e] (when (= "Escape" (.-key e)) (choose! :close))) false [choose!])
  [:<>
   [:div.prompt-pop-back {:on-click #(choose! :close)}]
   [:div.prompt-pop {:role "dialog" :aria-label "Prompt" :style place}
    [popup-body server picked profiles choose!]]])

(def ^:private popup-width 340)

(defn- place-of [^js btn]
  (let [r (.getBoundingClientRect btn)
        w (min popup-width (- js/innerWidth 12))]
    (assoc (popup-place {:right (.-right r) :bottom (.-bottom r)} w js/innerWidth) :width w)))

(defn pick
  "The chat's prompt profile: a button naming it; a click opens the popup (the locked built-in
  part, the profiles to choose from, Edit profiles…). A live chat takes a pick after its turn."
  [server settings put!]
  (let [profiles @(rf/subscribe [:prompts/list server])
        picked (picked-name (:prompt settings) profiles)
        [place set-place] (h/use-state nil)
        choose! (fn [v] (set-place nil) (when-not (keyword? v) (put! {:prompt v})))]
    (h/use-effect (fn [] (load! server) nil) [server])
    [:span.pick.prompt
     [:button.prompt-btn {:type "button" :class (when place "on")
                          :title "Prompt: the built-in part and the profile on top"
                          :on-click #(if place (set-place nil) (set-place (place-of (.-currentTarget %))))}
      "Prompt: " [:b (or picked "built-in")] (if place " ▴" " ▾")]
     (when place [:f> popup server picked profiles place choose!])]))

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
