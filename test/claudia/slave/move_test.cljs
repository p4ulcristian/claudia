(ns claudia.slave.move-test
  "Moving a chat to another server, the runner's pure parts (claudia.slave.move.rules), the note
  Claude gets and the chat shows, and the rows a copy carries through two stores."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.test :refer [deftest is]]
            [claudia.shared.protocol :as p]
            [claudia.slave.chats :as chats]
            [claudia.slave.chats.answers :as answers]
            [claudia.slave.history :as history]
            [claudia.slave.move.rules :as rules]
            [claudia.slave.store :as store]))

(def ^:private id "0b5e7f3a-1c2d-4e5f-8a9b-0c1d2e3f4a5b")

(deftest target-file-uses-the-targets-own-slug
  (let [src (history/projects-dir "/home/paul-claudia-agent/Work")
        dst (history/projects-dir "/Users/paul/Work")]
    (is (= "-home-paul-claudia-agent-Work" (path/basename src)))
    (is (= "-Users-paul-Work" (path/basename dst)))
    (is (= (path/join dst (str id ".jsonl")) (rules/target-file dst id (str id ".jsonl"))))
    (is (= (path/join dst id "subagents" "agent-1.jsonl") (rules/target-file dst id (str id "/subagents/agent-1.jsonl"))))
    (is (nil? (rules/target-file dst id (str id "/../../x"))) "nothing outside the chat's files")))

(deftest import-row-keeps-the-targets-columns-in-the-new-folder
  (is (= {:id id :title "T" :color "gold" :folder "/w/app" :archived 0 :root 0 :chat_key nil}
         (rules/import-row {:id id :title "T" :color "gold" :folder "/old" :archived 1 :root 1
                            :chat_key "k" :evil "x"}
                           #{:id :title :color :folder :archived :root :chat_key}
                           "/w/app"))))

(deftest split-lines-keeps-the-unfinished-line
  (is (= [[] "ab"] (rules/split-lines "" "ab")))
  (is (= [["ab" "cd"] "e"] (rules/split-lines "a" "b\ncd\ne")))
  (is (= [["x" ""] ""] (rules/split-lines "" "x\n\n"))))

(deftest record-problem-checks-each-line
  (is (nil? (rules/record-problem id {:type "chat" :id id :row {} :kv {}})))
  (is (some? (rules/record-problem id {:type "chat" :id "other" :row {} :kv {}})))
  (is (nil? (rules/record-problem id {:type "file" :path (str id ".jsonl") :data ""})))
  (is (= "not a file of this chat: /etc/passwd" (rules/record-problem id {:type "file" :path "/etc/passwd" :data ""})))
  (is (nil? (rules/record-problem id {:type "picture" :turn "t" :scene "{}" :at 1})))
  (is (some? (rules/record-problem id {:type "picture" :turn "t" :scene {} :at 1})))
  (is (nil? (rules/record-problem id {:type "change" :turn "t" :path "a" :status "added"})))
  (is (nil? (rules/record-problem id {:type "end"})))
  (is (= "unknown record x" (rules/record-problem id {:type "x"}))))

(deftest copy-problem-needs-one-chat-and-the-end
  (is (nil? (rules/copy-problem {"chat" 1 "file" 3 "end" 1})))
  (is (= "the copy was cut off before its end" (rules/copy-problem {"chat" 1 "file" 3})))
  (is (= "the copy has no chat record" (rules/copy-problem {"end" 1}))))

(deftest kv-of-takes-the-values-that-go-along
  (is (= {"settings" "{}" "turn" "\"u\"" "context" "{}" "tasks" "[]"}
         (rules/kv-of [["settings" "{}"] ["turn" "\"u\""] ["context" "{}"] ["tasks" "[]"]
                       ["session" "{}"] ["formsSeen" "{}"] ["moved" "{}"] ["movedTo" "{}"]]))))

(deftest note-from-names-where-claude-last-was
  (is (= "home" (rules/note-from nil "home")))
  (is (= "mac" (rules/note-from {:from "mac" :to "home"} "home")) "an earlier move Claude has not heard of"))

(deftest resolve-folder-on-the-target
  (is (nil? (rules/resolve-folder "/h/Work" "/h" "  ")))
  (is (= "/h/Work/app" (rules/resolve-folder "/h/Work" "/h" "~/Work/app")))
  (is (= "/h/Work/app" (rules/resolve-folder "/h/Work" "/h" "app")))
  (is (= "/srv/x" (rules/resolve-folder "/h/Work" "/h" "/srv/x"))))

(deftest heads-read-each-entrys-type-and-uuid
  (is (= [{:type "user" :uuid "u1"} {:type "assistant" :uuid "a1"}]
         (rules/heads "{\"type\":\"user\",\"uuid\":\"u1\"}\nnot json\n{\"type\":\"assistant\",\"uuid\":\"a1\"}\n")))
  (is (= {"picture" 2 "chat" 1} (rules/counts [{:type "chat"} {:type "picture"} {:type "picture"}]))))

;; The note

(def ^:private note (answers/server-moved-note "home" "work" "/w/app" "/w"))

(deftest server-moved-note-says-where-and-what-stayed
  (is (= (str "This chat moved from the server home to the server work. Its folder is now /w/app on work: "
              "run commands there (cd /w/app && …) and call set_folder with it before you work. Paths and "
              "files in the earlier history are home's: the working folder's files, running helpers and "
              "background work stayed on home.")
         note))
  (is (= (str "This chat moved from the server home to the server work. It has no project folder on work: "
              "it works in /w until you call set_folder with a project. Paths and files in the earlier "
              "history are home's: the working folder's files, running helpers and background work stayed on home.")
         (answers/server-moved-note "home" "work" nil "/w")))
  (is (= (answers/moved-note "/w/app" "/w") (answers/server-moved-note "home" "home" "/w/app" "/w"))
      "moved back before Claude heard of it: only where it works"))

(deftest the-chat-shows-the-note-claude-got
  (let [sent (history/with-moved-note "hello" note)]
    (is (= [note] (history/moved-notes sent)))
    (is (= "hello" (history/strip-moved sent)))
    (is (= [{:id "m1:moved" :role "note" :text note :at 5}] (history/moved-note-msgs "m1" sent 5))))
  (is (= [] (history/moved-note-msgs "m1" "plain" 5))))

(deftest session-msgs-show-the-note-before-the-message
  (let [text (str (history/with-moved-note "hello" note) "\n\n<system-reminder>hidden</system-reminder>")]
    (is (= [{:id "m1:moved" :role "note" :text note :at 0} {:id "m1" :role "user" :text "hello" :at 0}]
           (history/session-msgs [{:type "user" :uuid "m1" :message {:content text}}] #js [] nil)))
    (is (= [{:id "m2:moved" :role "note" :text note :at 0} {:id "m2" :role "user" :text "hello" :at 0 :images 1}]
           (history/session-msgs [{:type "user" :uuid "m2" :message {:content [{:type "image"} {:type "text" :text text}]}}]
                                 #js [] nil)))))

(deftest pending-note-shows-at-the-end-until-sent
  (is (= [{:id "a"} {:id chats/pending-note-id :role "note" :text "n" :at 3}]
         (chats/with-pending-note [{:id "a"}] {:text "n" :at 3})))
  (is (= [{:id "a"}] (chats/with-pending-note [{:id "a"}] nil))))

;; Rows through two stores

(defn- fill! [dir]
  (store/open! dir)
  (store/add! {:id id :title "T" :folder "/old" :color "gold" :at 5})
  (store/update! id {:root true :chat_key "k" :talk true})
  (store/save-scene! id {:title "pic" :blocks []})
  (store/kv! id "settings" {:model "opus"})
  (store/kv! id "session" {:account "a"})
  (store/save-picture! id "t1" {:title "one"} 1)
  (store/add-change! id {:turn "t1" :path "/old/a" :before nil :after "x" :added 1 :removed 0 :status "added" :at 2})
  (store/export-rows id))

(deftest rows-go-through-json-into-another-store
  (let [a (fs/mkdtempSync (path/join (os/tmpdir) "claudia-move-a-"))
        b (fs/mkdtempSync (path/join (os/tmpdir) "claudia-move-b-"))
        out (p/read-json (p/write-json (fill! a)))]
    (store/open! b)
    (store/import-chat! {:row (assoc (rules/import-row (:row out) (set store/chat-columns) "/new") :id id)
                         :kv (update-keys (rules/kv-of (:kv out)) name)
                         :pictures (:pictures out) :changes (:changes out)})
    (let [r (store/chat id)]
      (is (= ["T" "/new" "gold" false false nil true] ((juxt :title :folder :color :archived :root :chat-key :talk) r))))
    (is (= {:title "pic" :blocks []} (store/scene id)))
    (is (= {:model "opus"} (store/kv id "settings")))
    (is (nil? (store/kv id "session")) "the session's versions stay behind")
    (is (= [{:turn "t1" :title "one" :at 1}] (store/pictures id)))
    (is (= ["/old/a" "x"] [(:path (first (store/changes id))) (:after (store/change-texts id (:id (first (store/changes id)))))]))
    (store/import-chat! {:row {:id id :title "T2" :created 1 :at 1} :kv {} :pictures [] :changes []})
    (is (= ["T2" []] [(:title (store/chat id)) (store/pictures id)]) "a copy left behind gives way")
    (doseq [d [a b]] (fs/rmSync d #js {:recursive true :force true}))))
