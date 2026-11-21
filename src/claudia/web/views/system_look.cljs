(ns claudia.web.views.system-look
  "System's \"Needs a look\" (README decision 96): only problems and the chats' proposals, a card
  each with its one action: Log (the unit's journal opens under it), Ask a chat (the row's facts go
  into the open chat's composer, not sent), and for a proposal No or Accept (admins: the label is
  written on the hub, then the proposal taken away). \"All quiet\" when there is nothing."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.api :as api]
            [claudia.web.format :as format]
            [claudia.web.views.pure.system-look :as pure]
            [claudia.web.views.system-bits :as bits]))

(defn- take-away!
  "The proposal taken off its runner (Accept wrote its label first), then (refresh!)."
  [{:keys [runner id]} refresh!]
  (-> (api/call-json "DELETE" (api/url runner (str "system/proposals/" (js/encodeURIComponent id)) nil) nil)
      (.then (fn [_] (refresh!)))
      (.catch (fn [_] (refresh!)))))

(defn- accept!
  "A proposal accepted: its label on the hub, then the proposal taken away."
  [{:keys [server kind match project] :as p} refresh! failed]
  (-> (api/call-json "PUT" "/hub/system/labels" {:server server :kind kind :match match :project project})
      (.then (fn [_] (take-away! p refresh!)))
      (.catch #(failed (.-message %)))))

(defn- proposal-acts [{:keys [admin? refresh!]} p failed]
  [:<>
   [bits/button {:on-click #(take-away! p refresh!) :tip "Say no: nothing changes"} "No"]
   (when admin?
     [bits/button {:primary? true :on-click #(accept! p refresh! failed)
                   :tip (str "Label " (:kind p) " " (:match p) " as " (:project p))} "Accept"])])

(defn- problem-acts [{:keys [ro? tab]} row clock toggle-log log]
  (let [[asked set-asked] (h/use-state false)]
    [:<>
     (when toggle-log [bits/button {:on-click toggle-log :tip "The unit's last journal lines"} (if log "Hide log" "Log")])
     (when-not ro?
       [bits/button {:disabled asked :tip "Put these facts in the open chat's message box, not sent"
                     :on-click #(do (rf/dispatch [:draft/add (pure/ask-text (:label tab) row clock)]) (set-asked true))}
        (if asked "In the composer" "Ask a chat")])]))

(defn- card-text [row clock error]
  [:div.sy-card-main
   [:div.sy-card-line
    (when-let [c (:chat row)] [bits/chat-square (:colour c)])
    [:b (:subject row)] " " (:text row)]
   (let [facts (remove str/blank? (concat (:facts row) [(when clock (str (:at-word row "at") " " clock))]))]
     (when (seq facts) [:div.sy-card-facts (str/join " · " facts)]))
   (when error [:div.sy-err error])])

(defn- card [{:keys [tab now] :as sc} row]
  (let [[log toggle-log] (bits/use-unit-log tab (:unit row))
        [error set-error] (h/use-state nil)
        clock (format/clock-text (:at row) now)]
    [:article.sy-card {:class (:tone row)}
     [:div.sy-card-row
      [card-text row clock error]
      [:div.sy-card-acts
       (if-let [p (:proposal row)]
         [proposal-acts sc p set-error]
         [:f> problem-acts sc row clock toggle-log log])]]
     (when log [bits/log-lines log now])]))

(defn- quiet []
  [:div.sy-quiet
   [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M4 10.5l4 4 8-9"}]]
   "All quiet"])

(defn needs-a-look
  "The rows (claudia.web.views.pure.system-look/attention) as cards, or All quiet."
  [sc rows]
  (if (empty? rows)
    [quiet]
    [:section.sy-look
     [:h3.sy-h "Needs a look"]
     (for [row rows] ^{:key (:id row)} [:f> card sc row])]))
