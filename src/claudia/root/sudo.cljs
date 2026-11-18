(ns claudia.root.sudo
  "The sudo shim (README decision 52; deploy/sudo-shim runs it): first on the chats' PATH, so Claude
  keeps typing sudo. Sends the command, its folder, the chat's key (CLAUDIA_CHAT_KEY) and a few
  variables to the root helper's socket of this user, pipes stdin once the command runs, writes its
  output and exits with its code."
  (:require ["node:fs" :as fs]
            ["node:net" :as net]
            ["node:os" :as os]
            [claudia.root.args :as args]
            [claudia.root.frames :as frames]
            [claudia.root.rules :as rules]))

(defn- socket-path []
  (or (some-> (.. js/process -env -CLAUDIA_ROOT_SOCKET) not-empty)
      (str "/run/claudia-root/" (.-username (os/userInfo)) "/root.sock")))

(defn- fail! [text]
  (.write js/process.stderr (str text "\n"))
  (js/process.exit 1))

(defn- request [argv]
  (let [env (.-env js/process)]
    {:argv argv
     :cwd (js/process.cwd)
     :chatKey (some-> (.-CLAUDIA_CHAT_KEY env) not-empty)
     :env (into {} (for [k rules/passed-env :let [v (aget env k)] :when (string? v)] [k v]))}))

(defn- send-stdin!
  "The command runs: stdin goes to it, then its end."
  [^js sock]
  (let [stdin js/process.stdin]
    (.on stdin "data" (fn [buf] (doseq [f (frames/data-frames :in buf)] (.write sock (frames/line f)))))
    (.on stdin "end" #(.write sock (frames/line {:eof true})))
    (.on stdin "error" (fn [_]))))

(defn- finish!
  "The command ended: the shim exits with its code once the output is written."
  [^js sock code]
  (set! (.-exitCode js/process) code)
  (.destroy js/process.stdin)
  (.end sock))

(defn- asking!
  "The runner is asked: with the root switch on it answers at once; when no answer came in a moment,
  the person is asked, and the shim says so."
  [waiting]
  (reset! waiting (js/setTimeout #(.write js/process.stderr "sudo: waiting for the person to allow this in the chat (up to 10 minutes)\n")
                                 1500)))

(defn- on-frame [^js sock {:keys [done waiting]} frame]
  (when-not (:asking frame) (some-> @waiting js/clearTimeout))
  (cond
    (:asking frame) (asking! waiting)
    (:running frame) (send-stdin! sock)
    (:out frame) (.write js/process.stdout (frames/frame-bytes frame :out))
    (:err frame) (.write js/process.stderr (frames/frame-bytes frame :err))
    (contains? frame :exit) (do (reset! done true) (finish! sock (:exit frame)))
    (contains? frame :refused) (do (reset! done true) (.write js/process.stderr (str "sudo: refused: " (:refused frame) "\n"))
                                   (finish! sock 1))))

(defn main [& argv]
  (let [{:keys [error] cmd :argv} (args/sudo-args argv)
        path (socket-path)]
    (cond
      error (fail! error)
      (not (fs/existsSync path)) (fail! "no root helper on this server")
      :else
      (let [sock (net/createConnection path)
            done (atom false)
            waiting (atom nil)]
        (.on sock "error" #(fail! (str "sudo: the root helper: " (.-message %))))
        (.on sock "close" #(when-not @done (fail! "sudo: the root helper went away")))
        (frames/on-frames sock #(when % (on-frame sock {:done done :waiting waiting} %)))
        (.write sock (frames/line (request cmd)))))))
