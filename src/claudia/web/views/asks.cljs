(ns claudia.web.views.asks
  "What Claude asks the person: AskUserQuestion cards, and the Allow card for a 1Password secret."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.web.md :as md]))

(defn- answer-of
  "One question's answer: the picked labels (and the Other text), joined."
  [{:keys [picked other]}]
  (str/join ", " (cond-> (vec picked) (not (str/blank? other)) (conj (str/trim other)))))

(defn- question-card [k ask]
  (let [state (r/atom {})] ; question index -> {:picked [labels] :other text}
    (fn [k ask]
      (let [qs (vec (:questions ask))
            {:keys [busy error]} @(rf/subscribe [:ask-state (:id ask)])
            send! (fn [st]
                    (rf/dispatch [:ask/answer k (:id ask)
                                  (into {} (map-indexed (fn [i q] [(:question q) (answer-of (get st i))]) qs))]))
            complete? (every? #(not (str/blank? (answer-of (get @state %)))) (range (count qs)))
            ;; One question, one choice: a click is the answer.
            instant? (and (= 1 (count qs)) (not (:multiSelect (first qs))))]
        [:div.panel.ask {:class (when busy "busy")}
         (for [[i q] (map-indexed vector qs)
               :let [multi? (:multiSelect q)
                     {:keys [picked other]} (get @state i)]]
           ^{:key i}
           [:div.question
            [:div.q-head (when-not (str/blank? (:header q)) [:span.chip (:header q)])
             (when multi? [:span.muted.small "pick any"])]
            [:h3 (:question q)]
            [:div.options
             (for [[j o] (map-indexed vector (:options q))
                   :let [on? (some #{(:label o)} picked)]]
               ^{:key j}
               [:button.option {:class (when on? "on") :disabled busy
                                :on-click (fn []
                                            (if multi?
                                              (swap! state update-in [i :picked]
                                                     (fn [p] (if (some #{(:label o)} p)
                                                               (vec (remove #{(:label o)} p))
                                                               (conj (vec p) (:label o)))))
                                              (do (swap! state assoc i {:picked [(:label o)]})
                                                  (when instant? (send! @state)))))}
                [:span.mark {:class (if multi? "box" "dot")}]
                [:span.o-text
                 [:b (:label o)]
                 (when-not (str/blank? (:description o))
                   [:span.md {:dangerouslySetInnerHTML (r/unsafe-html (md/html (:description o)))}])]])
             [:label.option.other {:class (when-not (str/blank? other) "on")}
              [:span.mark {:class (if multi? "box" "dot")}]
              [:input {:placeholder "Other: type an answer"
                       :value (or other "")
                       :disabled busy
                       :on-change (fn [e]
                                    (let [v (.. e -target -value)]
                                      (if multi?
                                        (swap! state assoc-in [i :other] v)
                                        (swap! state assoc i {:picked [] :other v}))))
                       :on-key-down (fn [e]
                                      (when (and (= "Enter" (.-key e)) (not (str/blank? (.. e -target -value))))
                                        (.preventDefault e)
                                        (when (every? #(not (str/blank? (answer-of (get @state %)))) (range (count qs)))
                                          (send! @state))))}]]]])
         (when error [:p.ask-error error])
         [:div.actions
          [:button.primary {:disabled (or busy (not complete?)) :on-click #(send! @state)} "Send"]]]))))

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
          [:div.panel.ask.secret {:class (when busy "busy")}
           [:div.q-head [:span.chip.amber "secret"] [:span.muted.small "Claude never sees the value"]]
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

(defn asks-area
  "The open chat's asks, above the composer."
  [k asks]
  (when (seq asks)
    [:div.asks
     (for [a asks]
       ^{:key (:id a)}
       (case (:kind a)
         "question" [question-card k a]
         "secret" [secret-card k a]
         nil))]))
