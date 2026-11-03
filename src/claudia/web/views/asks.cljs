(ns claudia.web.views.asks
  "What Claude asks the person (web/panels.tsx of the TypeScript Claudia): its questions fill the
  window as a modal, so they have room to be read; Esc or \"See picture\" hides it and the pill at the
  bottom brings it back. Keys: 1-9 pick an option of the first question still without one, Enter
  answers. Beta's secret card (1Password) sits in the same modal."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.md :as md]))

(defn- answer-of
  "One question's answer: the picked labels (and the other text), joined."
  [{:keys [picked other]}]
  (str/join ", " (cond-> (vec picked) (not (str/blank? other)) (conj (str/trim other)))))

(defn- question-card [k ask active? on-peek]
  (let [[st set-st] (h/use-state {}) ; question index -> {:picked [labels] :other text :focus label}
        qs (vec (:questions ask))
        {:keys [busy error]} @(rf/subscribe [:ask-state (:id ask)])
        ready? (every? #(not (str/blank? (answer-of (get st %)))) (range (count qs)))
        submit! (fn [st]
                  (when (and (not busy) (every? #(not (str/blank? (answer-of (get st %)))) (range (count qs))))
                    (rf/dispatch [:ask/answer k (:id ask)
                                  (into {} (map-indexed (fn [i q] [(:question q) (answer-of (get st i))]) qs))])))
        toggle (fn [st i label]
                 (let [cur (vec (get-in st [i :picked]))]
                   (assoc-in st [i :picked]
                             (if (:multiSelect (get qs i))
                               (if (some #{label} cur) (vec (remove #{label} cur)) (conj cur label))
                               (if (= [label] cur) [] [label])))))
        keys-ref (h/use-ref nil)]
    (set! (.-current keys-ref) {:st st :submit submit! :toggle toggle})
    ;; Esc peeks at the picture, before the window's Esc (which would stop the chat).
    (h/use-effect
     (fn []
       (when active?
         (let [esc (fn [^js e]
                     (when (= "Escape" (.-key e))
                       (.preventDefault e)
                       (.stopImmediatePropagation e)
                       (on-peek)))]
           (js/window.addEventListener "keydown" esc true)
           #(js/window.removeEventListener "keydown" esc true))))
     [active?])
    (h/use-effect
     (fn []
       (when active?
         (let [k (fn [^js e]
                   (let [t (.-target e)
                         typing? (some-> t (.closest "textarea, input:not([type=checkbox]):not([type=radio]), [contenteditable]"))
                         {:keys [st submit toggle]} (.-current keys-ref)]
                     (cond
                       (and (= "Enter" (.-key e)) (not (.-shiftKey e)) (not (.-isComposing e)))
                       (when-not (and (instance? js/HTMLTextAreaElement t) (seq (str/trim (.-value t))))
                         (.preventDefault e)
                         (submit st))

                       (and (not typing?) (not (.-ctrlKey e)) (not (.-metaKey e)) (not (.-altKey e))
                            (re-matches #"[1-9]" (.-key e)))
                       (let [n (js/Number (.-key e))
                             qi (first (keep-indexed (fn [i _] (when-not (or (seq (get-in st [i :picked]))
                                                                             (not (str/blank? (get-in st [i :other]))))
                                                                 i))
                                                     qs))
                             i (or qi 0)]
                         (when-let [o (get-in qs [i :options (dec n)])]
                           (.preventDefault e)
                           (set-st #(toggle % i (:label o))))))))]
           (js/window.addEventListener "keydown" k)
           #(js/window.removeEventListener "keydown" k))))
     [active? (:id ask)])
    [:section.panel.ask {:class (when busy "busy")}
     (for [[i q] (map-indexed vector qs)
           :let [multi? (:multiSelect q)
                 {:keys [picked other focus]} (get st i)
                 preview (:preview (some #(when (= (:label %) (or focus (first picked))) %) (:options q)))]]
       ^{:key i}
       [:div.question
        [:div.q-head [:span.chip (:header q)] [:span.muted (if multi? "pick any" "pick one")]]
        [:h3 (:question q)]
        [:div.options {:role (if multi? "group" "radiogroup")}
         (for [[n o] (map-indexed vector (:options q))
               :let [on? (boolean (some #{(:label o)} picked))]]
           ^{:key n}
           [:button.option {:type "button" :role (if multi? "checkbox" "radio") :aria-checked on?
                            :class (when on? "on") :disabled busy
                            :on-click #(set-st (fn [s] (toggle s i (:label o))))
                            :on-mouse-enter #(set-st (fn [s] (assoc-in s [i :focus] (:label o))))
                            :on-focus #(set-st (fn [s] (assoc-in s [i :focus] (:label o))))}
            [:span.mark {:class (if multi? "box" "dot")}]
            [:span.o-text
             [:b (when (< n 9) [:span.o-num (inc n)]) (:label o)]
             (when-not (str/blank? (:description o))
               [:span.md {:dangerouslySetInnerHTML (r/unsafe-html (md/html (:description o)))}])]])
         [:label.option.other {:class (when-not (str/blank? other) "on")}
          [:span.mark {:class (if multi? "box" "dot")}]
          [:input {:placeholder "Something else…"
                   :value (or other "")
                   :disabled busy
                   :on-change (fn [e]
                                (let [v (.. e -target -value)]
                                  (set-st (fn [s] (cond-> (assoc-in s [i :other] v)
                                                    (and (not multi?) (not (str/blank? v))) (assoc-in [i :picked] []))))))}]]]
        (when (seq preview) [:pre.preview preview])])
     (when error [:p.ask-error error])
     [:div.actions
      [:span.muted.small.key-hint
       (str "1–" (min 9 (apply max 1 (map #(count (:options %)) qs))) " to pick · Enter to answer · Esc to see the picture")]
      [:button {:type "button" :on-click on-peek} "See picture"]
      [:button.primary {:type "button" :disabled (or busy (not ready?)) :on-click #(submit! st)} "Answer"]]]))

(defn- secret-card [k ask]
  (let [paste (r/atom nil)] ; nil = closed; a string while the paste field is open
    (r/create-class
     {:display-name "secret-card"
      ;; The pasted text never outlives the card.
      :component-will-unmount #(reset! paste nil)
      :reagent-render
      (fn [k ask]
        (let [{:keys [busy error sent]} @(rf/subscribe [:ask-state (:id ask)])
              send-paste! (fn []
                            (let [v @paste]
                              (reset! paste nil)
                              (when-not (str/blank? v)
                                (rf/dispatch [:ask/paste k ask v]))))]
          [:section.panel.ask.secret {:class (when busy "busy")}
           [:div.q-head [:span.chip.amber "secret"] [:span.muted "Claude never sees the value"]]
           [:h3 (or (:reason ask) "Claude needs a secret")]
           [:dl.secret-facts
            [:dt "Item"] [:dd [:code (:ref ask)]]
            (when (:account ask) [:<> [:dt "1Password"] [:dd (:account ask)]])
            (when (:vault ask) [:<> [:dt "Unlocks on"] [:dd @(rf/subscribe [:server-label (:vault ask)])]])
            (when (:expires ask)
              [:<> [:dt "Until"] [:dd (.toLocaleTimeString (js/Date. (:expires ask)) js/undefined #js {:hour "2-digit" :minute "2-digit"})]])]
           (when (some? @paste)
             [:div.paste-row
              [:input {:type "password" :auto-complete "off" :auto-focus true
                       :placeholder "Paste the secret" :value @paste
                       :on-change #(reset! paste (.. % -target -value))
                       :on-key-down #(when (= "Enter" (.-key %)) (.preventDefault %) (send-paste!))}]
              [:button.primary {:disabled (str/blank? @paste) :on-click send-paste!} "Send sealed"]])
           (when error [:p.ask-error error])
           (when (and busy (not sent)) [:p.muted.small "Waiting for 1Password on the vault server…"])
           [:div.actions
            [:button {:disabled busy :on-click #(rf/dispatch [:ask/deny k (:id ask)])} "Deny"]
            (when (nil? @paste)
              [:button {:disabled busy :on-click #(reset! paste "")} "Paste"])
            (when (:vault ask)
              [:button.primary {:disabled busy :on-click #(rf/dispatch [:ask/allow k ask])} "Allow"])]]))})))

(defn- modal-view [k asks]
  (let [[peek set-peek] (h/use-state false)
        first-id (:id (first asks))
        on-peek (h/use-callback #(set-peek true) [])]
    ;; A new question shows again.
    (h/use-effect (fn [] (set-peek false) nil) [first-id])
    [:<>
     [:div.ask-modal {:hidden peek :role "dialog" :aria-modal true :aria-label "Claude asks"}
      (for [a asks]
        (case (:kind a)
          "secret" ^{:key (:id a)} [secret-card k a]
          ^{:key (:id a)} [:f> question-card k a (not peek) on-peek]))]
     (when peek
       [:button.ask-return {:on-click #(set-peek false)} "Claude asks · answer"])]))

(defn modal
  "The open chat's asks over the whole window (never in another person's read-only view)."
  []
  (let [oc @(rf/subscribe [:open-chat])
        asks (get-in oc [:state :asks])]
    (when (and (seq asks) (not @(rf/subscribe [:read-only?])))
      ^{:key (str (:ck oc))} [:f> modal-view (:ck oc) asks])))
