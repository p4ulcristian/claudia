(ns claudia.web.views.my-words
  "My words on the page (claudia.web.words): the Settings section that lists, adds and removes
  them, and the same as a sheet on the right half's Drawing board (README decisions 75 and 91,
  opened from the iPad's edge); the offer over the composer to add a word the person corrected
  before sending; and the composer's chip that swaps the last dictated words between clean and as heard."
  (:require [clojure.string :as str]
            [claudia.web.views.pure.dictated :as dictated]
            [re-frame.core :as rf]
            [reagent.core :as r]))

;; ---- Settings ----

(defn- word-chip [words w busy]
  [:li.word-chip
   [:span w]
   [:button {:type "button" :disabled busy :aria-label (str "Remove " w) :title (str "Remove " w)
             :on-click #(rf/dispatch [:words/save (vec (remove #{w} words))])}
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

(defn- word-list [{:keys [words busy error]}]
  [:<>
   (if (seq words)
     [:ul.word-chips (for [w words] ^{:key w} [word-chip words w busy])]
     [:p.small "None yet."])
   [add-form words busy]
   (when error [:p.token-error error])])

(defn- words-body
  "The list with its add field, or where loading it stands."
  [{:keys [words error] :as m}]
  (cond
    (nil? m) [:p.small "Checking…"]
    (and (nil? words) error) [:p.token-error error]
    (nil? words) [:p.small "Checking…"]
    :else [word-list m]))

(def ^:private hint [:span.muted.small "names and terms dictation should hear right"])

(def ^:private heading [:h3 "My words " hint])

(defn settings-section
  "My words in Settings; nothing when the hub has no listen service."
  []
  (let [m @(rf/subscribe [:my-words])]
    (when-not (:off m)
      [:section.st-section.st-words heading [words-body m]])))

;; ---- the right half's view (README decision 75) ----

(defn pane
  "My words in the right half: the same list and add field as in Settings."
  []
  (r/with-let [_ (rf/dispatch [:words/load])]
    [:div.sp-panel.wp-panel.rv-pane {:role "region" :aria-label "My words"}
     ;; The sheet's title strip names it (README decision 91).
     [:header.wp-head hint]
     [words-body @(rf/subscribe [:my-words])]]))

;; ---- the composer ----

(defn offer-strip
  "Over the composer: Add <word> to My words? (one at a time, each word once)."
  []
  (when-let [w @(rf/subscribe [:words-offer])]
    [:div.words-offer {:role "status"}
     [:span "Add " [:b w] " to My words?"]
     [:button {:type "button" :on-click #(rf/dispatch [:words/accept])} "Add"]
     [:button {:type "button" :on-click #(rf/dispatch [:words/dismiss])} "No"]]))

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
