(ns claudia.web.db
  "The page's state, one map. Chats are keyed by [server chat] everywhere: two servers may in
  principle hand out the same chat id.")

(def phone-width
  "Under this many CSS px wide the page is three full screens (list, chat, picture)."
  680)

(defn phone? [] (< js/window.innerWidth phone-width))

(defn- stored [k default]
  (try (or (some-> (js/localStorage.getItem k) js/JSON.parse) default)
       (catch :default _ default)))

(defn store! [k v]
  (try (js/localStorage.setItem k (js/JSON.stringify v)) (catch :default _ nil)))

(def initial
  {:me nil                 ; GET /hub/me: {:email :name :admin :servers [{:name :label}]}
   :me-error nil
   :servers {}             ; name -> {:status :connecting|:open|:closed, :rows {chat-id Row}, :hello? bool}
   :chats {}               ; [server chat] -> {:title :folder :messages [Msg] :scene :running :asks [Ask] :error}
   :open nil               ; [server chat] of the open chat, or nil (a new chat)
   :screen :list           ; phone only: :list | :chat | :picture
   :sidebar? (stored "claudia.sidebar" true)
   :new-server (stored "claudia.newServer" nil) ; where a new chat starts (the last used)
   :pending-new {}         ; uuid -> {:server :text}: a new chat sent, `created` not back yet
   :outbox {}              ; [server chat] -> [text]: sent, the runner's echo not back yet
   :drafts {}              ; [server chat] or :new -> composer text
   :ask-state {}           ; ask id -> {:busy bool :error text}
   :seen-sent {}           ; [server chat] -> the row's :at we last sent `seen` for
   :error nil})
