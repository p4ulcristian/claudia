(ns claudia.web.views.pure.head
  "The chat header's server and project pills on plain data (README decision 90): the person's
  servers to pick from, their bookmarks as the server's projects, what each pill shows and what a
  pick does."
  (:require [clojure.string :as str]
            [claudia.shared.bookmarks :as bm]
            [claudia.web.format :as format]))

(defn- last-part [folder]
  (or (last (remove str/blank? (str/split (str folder) #"/"))) "/"))

(defn project-name
  "A bookmark's name: its label, else its folder's last part."
  [{:keys [label folder]}]
  (or (not-empty label) (last-part folder)))

(defn project-rows
  "The project popup's rows for server: one per bookmark of it, in order, {:folder :title (its
  name) :line (the path, ~ for the home) :on? (folder is the chat's)}."
  [bookmarks server folder]
  (mapv (fn [b] {:folder (:folder b) :title (project-name b) :line (format/tilde (:folder b))
                 :on? (= folder (:folder b))})
        (bm/on-server bookmarks server)))

(defn project-value
  "The project pill's value for the folder shown (nil while unknown): the name of server's bookmark
  of that folder, else the path with ~ for the home; nil when no folder is shown."
  [bookmarks server folder]
  (when (seq folder)
    (or (some #(when (= folder (:folder %)) (project-name %)) (bm/on-server bookmarks server))
        (format/tilde folder))))

(defn server-rows
  "The server popup's rows: every server of the person (servers: [{:name :label}]), {:name :title
  :on? (the chat's) :off? (its connection is :closed; states: the :servers sub, name to {:status})}."
  [servers current states]
  (mapv (fn [{n :name l :label}]
          {:name n :title (or (not-empty l) n) :on? (= n current) :off? (= :closed (get-in states [n :status]))})
        servers))

(defn server-pick
  "What picking server s does (nil: it is the chat's own): a new chat starts there; a started chat k
  opens the move card with s as where it goes (README decision 82)."
  [{:keys [new? ck server]} s]
  (when (not= s server)
    (if new? [:new/server s] [:move/ask ck s])))

(defn server-passive?
  "Whether the server pill changes nothing: a read-only header, a new chat with one server to pick,
  a started chat that may not move (can-move?)."
  [{:keys [new? ro]} servers can-move?]
  (boolean (or ro (if new? (< (count servers) 2) (not can-move?)))))
