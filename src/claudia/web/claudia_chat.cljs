(ns claudia.web.claudia-chat
  "Claudia's own chat from the page (README decision 48): her face asks the server that has her
  (its meta frame says claudia) for her chat, which the runner makes the first time; the answer
  opens it like any chat. Your own chats only: while looking at another person's, she opens nothing."
  (:require [re-frame.core :as rf]
            [claudia.web.events :as events]))

(defn her-server
  "The first of your servers whose runner has her (meta :claudia), or nil."
  [db]
  (when-not (:view db)
    (first (filter #(get-in db [:meta % :claudia]) (events/server-names db)))))

(rf/reg-event-fx
 :claudia/open
 (fn [{:keys [db]} _]
   (if-let [s (her-server db)]
     {:ws/send [s {:type "claudia"}]}
     {})))

(defmethod events/on-frame "claudia" [db server {:keys [chat]}]
  {:db db :fx [[:dispatch [:nav/open [server chat]]]]})

(rf/reg-sub :claudia/available? (fn [db _] (some? (her-server db))))
