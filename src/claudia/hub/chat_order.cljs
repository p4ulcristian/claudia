(ns claudia.hub.chat-order
  "A person's chat order and groups in the hub's store (README decisions 38 and 88): read and kept
  for the page (GET/PUT /hub/chat-order, claudia.hub.ops) and for Claudia's own chat (POST
  /hub/server/claudia ops chats and organize, claudia.hub.claudia), always cleaned by
  claudia.shared.order."
  (:require [claudia.hub.store :as store]
            [claudia.shared.order :as order]
            [claudia.shared.protocol :as protocol]))

(defn of
  "A person's chat order as the page gets it: {:items :at} ({:items [] :at 0} before any)."
  [email]
  (if-let [{:keys [items at]} (store/chat-order email)]
    {:items (order/clean-items (protocol/read-json items)) :at at}
    {:items [] :at 0}))

(defn save!
  "Keeps items as the person's whole list, as far as they are well formed; the list as kept ({:items :at})."
  [email items]
  (store/save-chat-order! email (protocol/write-json (order/clean-items items)))
  (of email))
