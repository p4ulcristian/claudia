(ns claudia.web.db
  "The page's state, one map. Chats are keyed by [server chat] everywhere: two servers may in
  principle hand out the same chat id."
  (:require [claudia.shared.colours :as colours]))

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
   :tabs {}                ; other tabs of this browser: tab id -> {:open [server chat] :view} (claudia.web.tabs)
   :screen :list           ; phone only: :list | :chat | :picture
   :sidebar? false         ; desktop: the full list open as a drawer over the rail (never kept)
   :rail-search? false     ; the drawer was opened by the rail's search: Escape folds it back
   :turn-since {}          ; [server chat] -> when its running turn was first seen running (ms)
   :new-server (stored "claudia.newServer" nil) ; where a new chat starts (the last used)
   :pending-new {}         ; uuid -> {:server :text}: a new chat sent, `created` not back yet
   :outbox {}              ; [server chat] -> [text]: sent, the runner's echo not back yet
   :drafts {}              ; [server chat] or :new -> composer text
   :ask-state {}           ; ask id -> {:busy bool :error text}
   :seen-sent {}           ; [server chat] -> the row's :at we last sent `seen` for
   :pictures {}            ; [server chat] -> [{:turn :title :at}], oldest first; the last is live
   :viewing {}             ; [server chat] -> turn of the earlier picture shown (none: the live one)
   :past-scenes {}         ; [server chat turn] -> Scene of an earlier picture, fetched once
   :folded-at (if (stored "claudia.folded" false) (js/Date.now) 0) ; the right half folded since (0: shown)
   :error nil
   :talk? (stored "claudia.talk" false) ; talking mode: the open chat's replies read aloud (this device)

   ;; claudia.web.chatops
   :view (stored "claudia.view" nil)          ; another profile's chats (read only), or nil
   :hidden-servers (let [v (stored "claudia.hiddenServers" #js [])] (if (array? v) (set v) #{}))
   :server-me {}           ; server -> its GET /api/me
   :settings? false        ; Settings in the sidebar's place
   :archived? false        ; the list shows archived chats
   :project-filter nil     ; the archived list's project chip: a project root, "" = no project, nil = all
   :all-sessions? (stored "claudia.allSessions" false)
   :sessions {}            ; server -> ~/Work sessions that are not chats
   :search nil             ; {:text :found {server [Found]}}
   :attach {}              ; draft key -> [attachment]
   :projects {}            ; [server folder] -> {:name :logo}
   :explain {}             ; [server chat step] -> {:busy :text :error :open}
   :menu nil               ; {:k [server chat] :x :y}: the chat menu
   :tokens nil :github-login nil
   :new-settings {:color (rand-nth colours/chat-colours)}})  ; a new chat's picks; its colour from the start
