(ns claudia.slave.chats.answers
  "What Claudia's own tools and asks answer Claude: tool results, refusals, the asks the page gets."
  (:require [clojure.string :as str]
            [claudia.shared.text :as text]
            [claudia.slave.history :as history]
            [claudia.slave.secret :as secret]))

(defn text-result [s] {:content [{:type "text" :text s}]})

(defn refusal [s] {:content [{:type "text" :text s}] :isError true})

(defn dropped-answer
  "What an ask that ends unanswered resolves with: a question or permit is denied (the turn
  interrupted), a tool call is refused (why: the reason)."
  [kind why]
  (if (#{"question" "permit"} kind)
    {:behavior "deny" :message why :interrupt true}
    (refusal (str why " Nothing ran."))))

(defn secret-refusal
  "run_with_secret's refusal for a call that can't run, or nil."
  [{:keys [env_name command]}]
  (cond
    (not (secret/env-name? env_name)) (refusal (str "env_name must be a shell variable name, not \"" env_name "\"."))
    (not (seq command)) (refusal "No command given.")
    :else nil))

(defn secret-ask
  "A secret ask as the page gets it: account and vault say where the value comes from, pubkey what
  to seal it to, expires when it ends."
  [ask-id {:keys [ref reason]} pubkey account vault expires]
  {:id ask-id :kind "secret" :ref ref :reason reason :account account :vault vault
   :pubkey pubkey :expires expires})

(defn permit-ask
  "An ExitPlanMode call as a permit ask: the plan to OK."
  [ask-id tool input title description]
  (let [label (history/tool-label tool)]
    {:id ask-id :kind "permit" :tool label
     :title (or (not-empty title) (str "Claude wants to use " label))
     :description (or (not-empty description) (history/tool-title tool input))
     :detail (text/fit (js/JSON.stringify (clj->js input) nil 2) 2000)}))

(def questions-off
  "The answer to AskUserQuestion in a chat with questions switched off."
  {:behavior "deny" :message "The user switched questions off in Claudia for this chat. Don't ask with buttons: decide with your best judgment and say in one line what you chose and why."})

(defn draw-answer
  "The draw tool's answer: what changed, and a screenshot of the picture as it looks now, so Claude
  sees its own drawing; one that failed is said in the text, never the draw's failure."
  [summary {:keys [^js png error]}]
  (if png
    {:content [{:type "text" :text summary}
               {:type "image" :data (.toString png "base64") :mimeType "image/png"}]}
    (text-result (str summary "\n\n(No screenshot of the picture: " error ")"))))

(defn home-path
  "A path with a leading ~ as the home folder."
  [p home]
  (str/replace (str p) #"^~(?=/|$)" home))

(defn folder-answer
  "set_folder's answer (src/chat.ts folderTool): the folder, its CLAUDE.md (nil: none) and its git
  status (nil: not a repository; the first 40 lines)."
  [folder md status]
  (text-result
   (str/join "\n"
             [(str "Working folder is now " folder ". Run commands there (cd " folder " && …).")
              (if md
                (str "\n<claude-md path=\"" folder "/CLAUDE.md\">\n" md "\n</claude-md>")
                "\n(no CLAUDE.md in this folder)")
              (str "\n<git-status>\n"
                   (if status (str/join "\n" (take 40 (.split status "\n"))) "(not a git repository)")
                   "\n</git-status>")])))

(def image-types {".png" "image/png" ".jpg" "image/jpeg" ".jpeg" "image/jpeg" ".gif" "image/gif" ".webp" "image/webp"})
(def shown-max 5000000)

(defn image-refusal
  "show_image's refusal for the file at file-path, or nil to show it. mime: its type (nil: no picture);
  stat: {:file? :size}, nil when there is nothing there."
  [file-path mime stat]
  (cond (not mime) (refusal (str file-path " is not a png, jpeg, gif or webp picture."))
        (not (:file? stat)) (refusal (str "No file at " file-path "."))
        (> (:size stat) shown-max) (refusal (str file-path " is " (js/Math.round (/ (:size stat) 1e6)) " MB; the chat shows pictures up to "
                                                 (/ shown-max 1e6) " MB. Make it smaller first."))
        :else nil))

(defn image-shown
  "show_image's answer: the picture (base64 data) and a line saying it is shown."
  [file-name data mime caption]
  {:content [{:type "image" :data data :mimeType mime}
             {:type "text" :text (str "Shown in the chat: " file-name
                                      (when (seq caption) (str " (" caption ")")) ".")}]})
