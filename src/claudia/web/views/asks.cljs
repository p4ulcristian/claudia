(ns claudia.web.views.asks
  "What Claude asks the person (web/panels.tsx of the TypeScript Claudia): its questions fill the
  window as a modal, so they have room to be read; Esc or \"See picture\" hides it and the pill at the
  bottom brings it back. Keys: 1-9 pick an option of the first question still without one, Enter
  answers. The secret card (1Password) sits in the same modal."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.md :as md]
            [claudia.web.views.pure.asks :as pure]))

(defn- use-esc-peeks!
  "Esc peeks at the picture, before the window's Esc (which would stop the chat)."
  [active? on-peek]
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
   [active?]))

(defn- card-key!
  "A key on the card: Enter answers (not in a text box with text), 1-9 pick an option of the first
  question still without one. keys-ref holds the card's state and its submit."
  [^js e qs keys-ref set-st]
  (let [t (.-target e)
        typing? (some-> t (.closest "textarea, input:not([type=checkbox]):not([type=radio]), [contenteditable]"))
        {:keys [st submit]} (.-current keys-ref)]
    (cond
      (and (= "Enter" (.-key e)) (not (.-shiftKey e)) (not (.-isComposing e)))
      (when-not (and (instance? js/HTMLTextAreaElement t) (seq (str/trim (.-value t))))
        (.preventDefault e)
        (submit st))

      (and (not typing?) (not (.-ctrlKey e)) (not (.-metaKey e)) (not (.-altKey e))
           (re-matches #"[1-9]" (.-key e)))
      (let [i (pure/first-open st qs)]
        (when-let [o (get-in qs [i :options (dec (js/Number (.-key e)))])]
          (.preventDefault e)
          (set-st #(pure/toggle % qs i (:label o))))))))

(defn- use-card-keys! [active? ask qs keys-ref set-st]
  (h/use-effect
   (fn []
     (when active?
       (let [k #(card-key! % qs keys-ref set-st)]
         (js/window.addEventListener "keydown" k)
         #(js/window.removeEventListener "keydown" k))))
   [active? (:id ask)]))

(defn- option-button [{:keys [qs i multi? picked busy set-st]} n o]
  (let [on? (boolean (some #{(:label o)} picked))
        focus! #(set-st (fn [s] (assoc-in s [i :focus] (:label o))))]
    [:button.option {:type "button" :role (if multi? "checkbox" "radio") :aria-checked on?
                     :class (when on? "on") :disabled busy
                     :on-click #(set-st (fn [s] (pure/toggle s qs i (:label o))))
                     :on-mouse-enter focus!
                     :on-focus focus!}
     [:span.mark {:class (if multi? "box" "dot")}]
     [:span.o-text
      [:b (when (< n 9) [:span.o-num (inc n)]) (:label o)]
      (when-not (str/blank? (:description o))
        [:span.md {:dangerouslySetInnerHTML (r/unsafe-html (md/html (:description o)))}])]]))

(defn- other-option [{:keys [i multi? other busy set-st]}]
  [:label.option.other {:class (when-not (str/blank? other) "on")}
   [:span.mark {:class (if multi? "box" "dot")}]
   [:input {:placeholder "Something else…"
            :value (or other "")
            :disabled busy
            :on-change (fn [e]
                         (let [v (.. e -target -value)]
                           (set-st (fn [s] (pure/typed-other s i multi? v)))))}]])

(defn- question-view [qs st busy set-st i q]
  (let [multi? (:multiSelect q)
        {:keys [picked other] :as qst} (get st i)
        preview (pure/preview q qst)
        props {:qs qs :i i :multi? multi? :picked picked :other other :busy busy :set-st set-st}]
    ^{:key i}
    [:div.question
     [:div.q-head [:span.chip (:header q)] [:span.muted (if multi? "pick any" "pick one")]]
     [:h3 (:question q)]
     [:div.options {:role (if multi? "group" "radiogroup")}
      (for [[n o] (map-indexed vector (:options q))]
        ^{:key n} [option-button props n o])
      (other-option props)]
     (when (seq preview) [:pre.preview preview])]))

(defn- question-card [k ask active? on-peek]
  (let [[st set-st] (h/use-state {}) ; question index -> {:picked [labels] :other text :focus label}
        qs (vec (:questions ask))
        {:keys [busy error]} @(rf/subscribe [:ask-state (:id ask)])
        submit! (fn [st]
                  (when (and (not busy) (pure/ready? st qs))
                    (rf/dispatch [:ask/answer k (:id ask) (pure/answers st qs)])))
        keys-ref (h/use-ref nil)]
    (set! (.-current keys-ref) {:st st :submit submit!})
    (use-esc-peeks! active? on-peek)
    (use-card-keys! active? ask qs keys-ref set-st)
    [:section.panel.ask {:class (when busy "busy")}
     (for [[i q] (map-indexed vector qs)]
       (question-view qs st busy set-st i q))
     (when error [:p.ask-error error])
     [:div.actions
      [:span.muted.small.key-hint (pure/key-hint qs)]
      [:button {:type "button" :on-click on-peek} "See picture"]
      [:button.primary {:type "button" :disabled (or busy (not (pure/ready? st qs))) :on-click #(submit! st)} "Answer"]]]))

(defn- secret-facts [ask]
  [:dl.secret-facts
   [:dt "Item"] [:dd [:code (:ref ask)]]
   (when (:account ask) [:<> [:dt "1Password"] [:dd (:account ask)]])
   (when (:vault ask) [:<> [:dt "Unlocks on"] [:dd @(rf/subscribe [:server-label (:vault ask)])]])
   (when (:expires ask)
     [:<> [:dt "Until"] [:dd (.toLocaleTimeString (js/Date. (:expires ask)) js/undefined #js {:hour "2-digit" :minute "2-digit"})]])])

(defn- paste-row [paste send-paste!]
  [:div.paste-row
   [:input {:type "password" :auto-complete "off" :auto-focus true
            :placeholder "Paste the secret" :value @paste
            :on-change #(reset! paste (.. % -target -value))
            :on-key-down #(when (= "Enter" (.-key %)) (.preventDefault %) (send-paste!))}]
   [:button.primary {:disabled (str/blank? @paste) :on-click send-paste!} "Send sealed"]])

(defn- secret-actions [k ask paste busy]
  [:div.actions
   [:button {:disabled busy :on-click #(rf/dispatch [:ask/deny k (:id ask)])} "Deny"]
   (when (nil? @paste)
     [:button {:disabled busy :on-click #(reset! paste "")} "Paste"])
   (when (:vault ask)
     [:button.primary {:disabled busy :on-click #(rf/dispatch [:ask/allow k ask])} "Allow"])])

(defn- secret-render [paste k ask]
  (let [{:keys [busy error sent]} @(rf/subscribe [:ask-state (:id ask)])
        send-paste! (fn []
                      (let [v @paste]
                        (reset! paste nil)
                        (when-not (str/blank? v)
                          (rf/dispatch [:ask/paste k ask v]))))]
    [:section.panel.ask.secret {:class (when busy "busy")}
     [:div.q-head [:span.chip.amber "secret"] [:span.muted "Claude never sees the value"]]
     [:h3 (or (:reason ask) "Claude needs a secret")]
     (secret-facts ask)
     (when (some? @paste) (paste-row paste send-paste!))
     (when error [:p.ask-error error])
     (when (and busy (not sent)) [:p.muted.small "Waiting for 1Password on the vault server…"])
     (secret-actions k ask paste busy)]))

(defn- secret-card [_k _ask]
  (let [paste (r/atom nil)] ; nil = closed; a string while the paste field is open
    (r/create-class
     {:display-name "secret-card"
      ;; The pasted text never outlives the card.
      :component-will-unmount #(reset! paste nil)
      :reagent-render (fn [k ask] (secret-render paste k ask))})))

(defn- permit-card
  "A tool waiting for the person's OK (ExitPlanMode: the plan to approve), as the TypeScript
  Claudia's PermitPanel. Allow goes as an answer, Deny as a deny."
  [_k _ask]
  (let [open (r/atom false)]
    (fn [k ask]
      (let [{:keys [busy]} @(rf/subscribe [:ask-state (:id ask)])]
        [:section.panel.permit {:class (when busy "busy")}
         [:div.q-head [:span.chip.amber (:tool ask)] [:span.muted "needs your OK"]]
         [:h3 (:title ask)]
         (when-not (str/blank? (:description ask)) [:p.muted (:description ask)])
         [:button.link {:on-click #(swap! open not)} (if @open "Hide details" "Details")]
         (when @open [:pre.preview (:detail ask)])
         [:div.actions
          [:button {:disabled busy :on-click #(rf/dispatch [:ask/deny k (:id ask)])} "Deny"]
          [:button.primary {:disabled busy :on-click #(rf/dispatch [:ask/answer k (:id ask) {}])} "Allow"]]]))))

(defn- modal-view [k asks]
  (let [[peeking set-peek] (h/use-state false)
        first-id (:id (first asks))
        on-peek (h/use-callback #(set-peek true) [])]
    ;; A new question shows again.
    (h/use-effect (fn [] (set-peek false) nil) [first-id])
    [:<>
     [:div.ask-modal {:hidden peeking :role "dialog" :aria-modal true :aria-label "Claude asks"}
      (for [a asks]
        (case (:kind a)
          "secret" ^{:key (:id a)} [secret-card k a]
          "permit" ^{:key (:id a)} [permit-card k a]
          ^{:key (:id a)} [:f> question-card k a (not peeking) on-peek]))]
     (when peeking
       [:button.ask-return {:on-click #(set-peek false)} "Claude asks · answer"])]))

(defn modal
  "The open chat's asks over the whole window (never in another person's read-only view)."
  []
  (let [oc @(rf/subscribe [:open-chat])
        asks (get-in oc [:state :asks])]
    (when (and (seq asks) (not @(rf/subscribe [:read-only?])))
      ^{:key (str (:ck oc))} [:f> modal-view (:ck oc) asks])))
