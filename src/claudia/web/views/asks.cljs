(ns claudia.web.views.asks
  "What Claude asks the person (web/panels.tsx of the TypeScript Claudia), in the chat (README
  decision 76): its cards follow the last message, the picture staying in view beside them. Keys:
  1-9 pick an option of the first question still without one, Enter answers. The secret card
  (1Password through the key broker, approved with a passkey) and the OK cards sit there too."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.md :as md]
            [claudia.web.passkey :as passkey]
            [claudia.web.views.pure.asks :as pure]))

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

(defn- use-card-keys! [ask qs keys-ref set-st]
  (h/use-effect
   (fn []
     (let [k #(card-key! % qs keys-ref set-st)]
       (js/window.addEventListener "keydown" k)
       #(js/window.removeEventListener "keydown" k)))
   [(:id ask)]))

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

(defn- question-card [k ask]
  (let [[st set-st] (h/use-state {}) ; question index -> {:picked [labels] :other text :focus label}
        qs (vec (:questions ask))
        {:keys [busy error]} @(rf/subscribe [:ask-state (:id ask)])
        submit! (fn [st]
                  (when (and (not busy) (pure/ready? st qs))
                    (rf/dispatch [:ask/answer k (:id ask) (pure/answers st qs)])))
        keys-ref (h/use-ref nil)]
    (set! (.-current keys-ref) {:st st :submit submit!})
    (use-card-keys! ask qs keys-ref set-st)
    [:section.panel.ask {:class (when busy "busy")}
     (for [[i q] (map-indexed vector qs)]
       (question-view qs st busy set-st i q))
     (when error [:p.ask-error error])
     [:div.actions
      [:span.muted.small.key-hint (pure/key-hint qs)]
      [:button.primary {:type "button" :disabled (or busy (not (pure/ready? st qs))) :on-click #(submit! st)} "Answer"]]]))

(defn- secret-facts [ask]
  [:dl.secret-facts
   [:dt "Item"] [:dd [:code (:ref ask)]]
   [:dt "From"] [:dd "1Password, through the key broker"]
   (when (:expires ask)
     [:<> [:dt "Until"] [:dd (.toLocaleTimeString (js/Date. (:expires ask)) js/undefined #js {:hour "2-digit" :minute "2-digit"})]])])

(defn- paste-row [paste send-paste!]
  [:div.paste-row
   [:input {:type "password" :auto-complete "off" :auto-focus true
            :placeholder "Paste the secret" :value @paste
            :on-change #(reset! paste (.. % -target -value))
            :on-key-down #(when (= "Enter" (.-key %)) (.preventDefault %) (send-paste!))}]
   [:button.primary {:disabled (str/blank? @paste) :on-click send-paste!} "Send sealed"]])

(defn- prepare-read!
  "Fetches the broker's challenge for this ask before any tap (claudia.web.passkey): prepared
  holds it, or the error."
  [prepared k ask]
  (reset! prepared nil)
  (-> (passkey/prepare-approval (passkey/read-ask-purpose ask k))
      (.then #(reset! prepared %))
      (.catch #(reset! prepared {:error (passkey/problem %)}))))

(defn- allow!
  "The tap: the passkey opens straight away with the prepared challenge; the sealed value goes on
  to the chat's server. A fresh challenge is fetched after a failure."
  [prepared k ask]
  (let [p @prepared]
    (rf/dispatch [:ask/state (:id ask) {:busy true}])
    (-> (passkey/read-sealed! p)
        (.then #(rf/dispatch [:ask/sealed k ask %]))
        (.catch #(do (rf/dispatch [:ask/state (:id ask) {:error (passkey/problem %)}])
                     (prepare-read! prepared k ask))))))

(defn- start-read!
  "A secret card appears: within this chat's hour for this key the value comes without a tap;
  otherwise the passkey challenge is fetched for the tap."
  [prepared k ask]
  (-> (passkey/read-standing ask k)
      (.then #(rf/dispatch [:ask/sealed k ask %]))
      (.catch #(prepare-read! prepared k ask))))

(defn- secret-actions [k ask paste busy prepared]
  [:div.actions
   [:button {:disabled busy :on-click #(rf/dispatch [:ask/deny k (:id ask)])} "Deny"]
   (when (nil? @paste)
     [:button {:disabled busy :on-click #(reset! paste "")} "Paste"])
   ;; Allow needs the hub's key broker and a passkey (README decision 66); paste works without.
   (when (:vault @(rf/subscribe [:me]))
     [:button.primary {:disabled (or busy (not (:challengeId @prepared))) :on-click #(allow! prepared k ask)}
      (if (:challengeId @prepared) "Allow with passkey" "Getting ready…")])])

(defn- secret-render [paste prepared k ask]
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
     (when-let [e (or error (:error @prepared))] [:p.ask-error e])
     (when (and busy (not sent)) [:p.muted.small "Waiting for your passkey, then 1Password…"])
     (secret-actions k ask paste busy prepared)]))

(def ^:private challenge-refresh-ms
  "A prepared challenge is fetched again before the broker's 5 minutes end."
  (* 4 60 1000))

(defn- secret-card [k ask]
  (let [paste (r/atom nil) ; nil = closed; a string while the paste field is open
        prepared (r/atom nil) ; the broker's challenge for this ask, fetched before the tap
        timer (atom nil)]
    (r/create-class
     {:display-name "secret-card"
      :component-did-mount
      (fn []
        (when (:vault @(rf/subscribe [:me]))
          (start-read! prepared k ask)
          (reset! timer (js/setInterval #(prepare-read! prepared k ask) challenge-refresh-ms))))
      ;; The pasted text never outlives the card.
      :component-will-unmount #(do (reset! paste nil) (some-> @timer js/clearInterval))
      :reagent-render (fn [k ask] (secret-render paste prepared k ask))})))

(defn- permit-actions
  "Deny and Allow: a \"Run as root\" card's go to the server's front (README decision 52), the
  others' to the runner."
  [k ask busy]
  (let [root? (:root ask)]
    [:div.actions
     [:button {:disabled busy :on-click #(rf/dispatch (if root? [:root/answer k ask false] [:ask/deny k (:id ask)]))} "Deny"]
     [:button.primary {:disabled busy :on-click #(rf/dispatch (if root? [:root/answer k ask true] [:ask/answer k (:id ask) {}]))}
      "Allow"]]))

(defn- permit-card
  "A tool waiting for the person's OK (ExitPlanMode: the plan to approve; a root command), as the
  TypeScript Claudia's PermitPanel."
  [_k _ask]
  (let [open (r/atom false)]
    (fn [k ask]
      (let [{:keys [busy error]} @(rf/subscribe [:ask-state (:id ask)])]
        [:section.panel.permit {:class (when busy "busy")}
         [:div.q-head [:span.chip.amber (:tool ask)] [:span.muted "needs your OK"]]
         [:h3 (:title ask)]
         (when-not (str/blank? (:description ask)) [:p.muted (:description ask)])
         [:button.link {:on-click #(swap! open not)} (if @open "Hide details" "Details")]
         (when @open [:pre.preview (:detail ask)])
         (when error [:p.ask-error error])
         (permit-actions k ask busy)]))))

(defn in-chat
  "Chat k's asks, after its last message (never in another person's read-only view)."
  [k asks]
  (when (and (seq asks) (not @(rf/subscribe [:read-only?])))
    [:div.asks-inline {:role "region" :aria-label "Claude asks"}
     (for [a asks]
       (case (:kind a)
         "secret" ^{:key (:id a)} [secret-card k a]
         "permit" ^{:key (:id a)} [permit-card k a]
         ^{:key (:id a)} [:f> question-card k a]))]))
