(ns claudia.web.views.my-words
  "My words on the page (claudia.web.words): Settings' Dictation section (the list with words
  learned from the chat marked, the switch for the chat's cards, the words said No to, Reset), the
  card in the chat for a word Claude offers (README decision 95), and the composer's chip that
  swaps the last dictated words between clean and as heard."
  (:require [clojure.string :as str]
            [claudia.web.views.pure.dictated :as dictated]
            [re-frame.core :as rf]
            [reagent.core :as r]))

;; ---- Settings ----

(defn- word-chip [w learned? busy]
  [:li.word-chip
   [:span w (when learned? [:span.muted.small " from chat"])]
   [:button {:type "button" :disabled busy :aria-label (str "Remove " w) :title (str "Remove " w)
             :on-click #(rf/dispatch [:words/remove w])}
    "×"]])

(defn- add-form [_words _busy]
  (let [value (r/atom "")]
    (fn [words busy]
      (let [w (str/trim @value)
            ready (and (seq w) (not busy) (not (dictated/has-word? words w)))]
        [:form.token-form.words-form
         {:on-submit (fn [e]
                       (.preventDefault e)
                       (when ready
                         (rf/dispatch [:words/save (dictated/with-word words w)])
                         (reset! value "")))}
         [:input {:auto-complete "off" :spell-check false :placeholder "A name or term"
                  :max-length 60 :value @value :on-change #(reset! value (.. % -target -value))}]
         [:button {:disabled (not ready)} (if busy "Saving…" "Add")]]))))

(defn- word-list [{:keys [words busy error]} {:keys [learned]}]
  [:<>
   (if (seq words)
     [:ul.word-chips (for [w words] ^{:key w} [word-chip w (dictated/has-word? learned w) busy])]
     [:p.small "None yet."])
   [add-form words busy]
   (when error [:p.token-error error])])

(defn- label [text note] [:span.st-label text [:span.st-note note]])

(defn- offers-row [{:keys [offers]}]
  [:label.st-row.st-switch
   [label "Offer words in the chat" "Claude suggests a name you were misheard on"]
   [:input {:type "checkbox" :role "switch" :checked (boolean offers)
            :on-change #(rf/dispatch [:words/offers (.. % -target -checked)])}]
   [:span.st-knob]])

(defn- declined-row [{:keys [declined]}]
  [:div.st-row.words-row
   [label (str "Words you said No to (" (count declined) ")") "never offered again"]
   [:button {:type "button" :disabled (empty? declined) :on-click #(rf/dispatch [:words/offer-again])} "Offer again"]])

(defn- reset-row []
  (let [sure? (r/atom false)]
    (fn []
      [:div.st-row.words-row
       [label "Reset dictation profile" "clears My words and the No list"]
       (if @sure?
         [:span.words-sure
          [:button.danger {:type "button" :on-click #(do (reset! sure? false) (rf/dispatch [:words/reset]))} "Reset"]
          [:button {:type "button" :on-click #(reset! sure? false)} "Cancel"]]
         [:button.danger {:type "button" :on-click #(reset! sure? true)} "Reset…"])])))

(defn- words-body
  "The section's rows, or where loading the list stands."
  [{:keys [words error] :as m} prefs]
  (cond
    (nil? m) [:p.small "Checking…"]
    (and (nil? words) error) [:p.token-error error]
    (nil? words) [:p.small "Checking…"]
    :else [:<> [word-list m prefs] [offers-row prefs] [declined-row prefs] [reset-row]]))

(def ^:private heading
  [:h3 "Dictation " [:span.muted.small "your words, the same on every device"]])

(defn settings-section
  "Dictation in Settings; nothing when the hub has no listen service."
  []
  (r/with-let [_ (rf/dispatch [:words/load])]
    (let [m @(rf/subscribe [:my-words])]
      (when-not (:off m)
        [:section.st-section.st-words heading [words-body m @(rf/subscribe [:listen-prefs])]]))))

;; ---- the card in the chat (README decision 95) ----

(defn- card-view [{:keys [word heard]}]
  [:div.words-offer {:role "status"}
   [:span "Add " [:b word] " to My words?" (when (seq heard) [:span.muted (str " heard: " heard)])]
   [:button {:type "button" :on-click #(rf/dispatch [:words/card-add word])} "Add"]
   [:button {:type "button" :on-click #(rf/dispatch [:words/card-no word])} "No"]])

(defn word-card
  "The card for a word Claude offered (tool: its step, :word {:word :heard}): only in the person's
  own chats, and only while pure card-shown? holds; nothing waits on it."
  [tool]
  (r/with-let [_ (when (nil? @(rf/subscribe [:my-words])) (rf/dispatch [:words/load]))]
    (let [offer (:word tool)
          m @(rf/subscribe [:my-words])]
      (when (and @(rf/subscribe [:owner?]) (:words m)
                 (dictated/card-shown? offer (:words m) @(rf/subscribe [:listen-prefs])))
        [card-view offer]))))

;; ---- the composer ----

(defn raw-chip
  "Over composer k after a dictation whose clean and raw words differ: the other version; a tap
  swaps it into the draft."
  [k]
  (when-let [{:keys [shown] :as piece} @(rf/subscribe [:dictated-swap k])]
    (let [other (if (= :text shown) (:raw piece) (:text piece))]
      [:button.raw-chip {:type "button" :on-click #(rf/dispatch [:dictated/swap k])
                         :title (if (= :text shown) "Use the words as heard" "Use the cleaned-up words")}
       [:span.raw-chip-k (if (= :text shown) "As heard:" "Cleaned:")]
       [:span.raw-chip-t other]])))
