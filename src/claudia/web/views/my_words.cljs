(ns claudia.web.views.my-words
  "My words on the page (claudia.web.words): the Settings section that lists, adds and removes
  them, and the same in a panel opened from the iPad's edge (claudia.web.views.thumb-edge); the offer over the composer to add a word the person corrected before sending; and the
  composer's chip that swaps the last dictated words between clean and as heard."
  (:require [clojure.string :as str]
            [claudia.web.views.pure.dictated :as dictated]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]))

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

(def ^:private heading [:h3 "My words " [:span.muted.small "names and terms dictation should hear right"]])

(defn settings-section
  "My words in Settings; nothing when the hub has no listen service."
  []
  (let [m @(rf/subscribe [:my-words])]
    (when-not (:off m)
      [:section.st-section.st-words heading [words-body m]])))

;; ---- the panel (the iPad's edge button) ----

(defn- close! [] (rf/dispatch [:words/panel false]))

(defn- use-escape-close!
  "Escape closes the panel, before the page's own keys see it (Escape there stops a chat)."
  []
  (h/use-effect
   (fn []
     (let [esc (fn [^js e]
                 (when (= "Escape" (.-key e))
                   (.preventDefault e)
                   (.stopPropagation e)
                   (close!)))]
       (js/window.addEventListener "keydown" esc true)
       #(js/window.removeEventListener "keydown" esc true)))
   []))

(defn- panel-view []
  (use-escape-close!)
  [:div.sp-back {:on-click #(when (= (.-target %) (.-currentTarget %)) (close!))}
   [:div.sp-panel.wp-panel {:role "dialog" :aria-label "My words"}
    [:header.wp-head heading
     [:button.sp-close {:type "button" :aria-label "Close My words" :on-click close!} "✕"]]
    [words-body @(rf/subscribe [:my-words])]]])

(defn panel
  "My words over the dimmed page, as the search panel (claudia.web.views.search-panel): the same
  list and add field as in Settings; ✕, Escape or a tap on the dimmed page closes it."
  []
  (when @(rf/subscribe [:words-panel?]) [:f> panel-view]))

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
