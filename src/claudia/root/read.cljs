(ns claudia.root.read
  "The root helper's read socket (README decision 96): <ROOT_READ_SOCKET_DIR>/read.sock, in a folder
  only root and the front's group may enter, so only the front asks. One frame in, {out} or {error}
  out, then the end. Only the fixed reads of claudia.node.reads run, as root (claudia.node.collect);
  nothing of the caller's reaches a command but a checked unit name, user name and line count. Each
  kind is logged at most once per 10 minutes, every unit-log (decision 59)."
  (:require ["node:fs" :as fs]
            ["node:net" :as net]
            ["node:path" :as path]
            [claudia.node.collect :as collect]
            [claudia.node.env :as env]
            [claudia.node.reads :as reads]
            [claudia.root.frames :as frames]
            [claudia.shared.activity :as activity]))

(def ^:private socket-dir (env/env "ROOT_READ_SOCKET_DIR" "/run/claudia-root-read"))

;; {kind ms}: when each kind was last logged.
(defonce ^:private logged (atom {}))

(defn- note! [frame]
  (let [now (js/Date.now)]
    (when (reads/log-read? @logged (:read frame) now)
      (swap! logged assoc (:read frame) now)
      (js/console.log (activity/line (reads/read-entry frame))))))

(defn- answer! [^js sock frame]
  (when-not (.-destroyed sock) (.end sock (frames/line frame))))

(defn- on-frame! [^js sock frame]
  (if-let [problem (reads/read-problem frame)]
    (answer! sock {:error problem})
    (do (note! frame)
        (-> (collect/read! :root frame)
            (.then #(answer! sock %))
            (.catch #(answer! sock {:error (.-message %)}))))))

(defn- on-connection [^js sock]
  (let [got (atom false)]
    (.on sock "error" (fn [_]))
    (frames/on-frames sock (fn [frame]
                             (when-not @got
                               (reset! got true)
                               (on-frame! sock frame))))))

(defn listen!
  "Listens on read.sock in the read folder when it is there (host/ makes it: 0750 root:<the front's
  group>); the socket takes the folder's group, mode 0660. No folder: no read socket, logged."
  []
  (let [sock-path (path/join socket-dir "read.sock")
        ^js st (try (fs/statSync socket-dir) (catch :default _ nil))]
    (if-not (and st (.isDirectory st) (zero? (bit-and (.-mode st) 7)))
      (js/console.error (str "root: no read socket: " socket-dir " is missing, or others may enter it"))
      (let [server (net/createServer on-connection)]
        (try (fs/unlinkSync sock-path) (catch :default _))
        (.on server "error" #(js/console.error (str "root: " sock-path ": " (.-message %))))
        (.listen server sock-path
                 (fn []
                   (fs/chownSync sock-path (js/process.getuid) (.-gid st))
                   (fs/chmodSync sock-path (js/parseInt "660" 8))
                   (js/console.log (str "claudia-root reads for the front on " sock-path))))))))
