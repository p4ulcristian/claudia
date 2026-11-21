(ns claudia.web.views.pure.head
  "The chat header's where pill on plain data (README decisions 90, 117, 119): the person's servers
  to pick from, the server's projects (their bookmarks, then the folders their chats there use),
  each named by its bookmark label, else the project the server found in it, else its last folder;
  what the pill shows and what a pick does."
  (:require [clojure.string :as str]
            [claudia.shared.bookmarks :as bm]
            [claudia.web.format :as format]))

(defn- last-part [folder]
  (or (last (remove str/blank? (str/split (str folder) #"/"))) "/"))

(defn project-name
  "A bookmark's name: its label, else its folder's last part."
  [{:keys [label folder]}]
  (or (not-empty label) (last-part folder)))

(defn- named
  "An entry's name: its bookmark label, else the project the server found in the folder (projects:
  [server folder] -> {:name}), else the folder's last part."
  [projects server {:keys [label folder]}]
  (or (not-empty label) (not-empty (:name (get projects [server folder]))) (last-part folder)))

(def used-max "How many folders the chats use the project popup lists at most." 12)

(defn chat-folders
  "The folders the chats on server use (rows: [{:server :folder :at}]), the latest used first, each
  once, none of skip, at most used-max."
  [rows server skip]
  (->> rows
       (filter #(= server (:server %)))
       (sort-by #(- (or (:at %) 0)))
       (map :folder)
       (remove str/blank?)
       distinct
       (remove skip)
       (take used-max)
       vec))

(defn project-rows
  "The project popup's rows for server: its bookmarks in order, then the folders the chats there use
  (chat-folders), then the chat's own folder when neither has it. Each {:folder :title (named) :line
  (the path, ~ for the home) :on? (the chat's folder) :pinned? (a bookmark)}."
  [bookmarks rows projects server folder]
  (let [marks (bm/on-server bookmarks server)
        marked (set (map :folder marks))
        used (chat-folders rows server marked)
        own (when (and (seq folder) (not (marked folder)) (not (some #{folder} used))) [folder])]
    (mapv (fn [b] {:folder (:folder b) :title (named projects server b) :line (format/tilde (:folder b))
                   :on? (= folder (:folder b)) :pinned? (contains? marked (:folder b))})
          (concat marks (map (fn [f] {:folder f}) (concat used own))))))

(defn project-value
  "The where pill's project for the folder shown (nil while unknown): named as in project-rows; nil
  when no folder is shown."
  [bookmarks projects server folder]
  (when (seq folder)
    (named projects server (or (some #(when (= folder (:folder %)) %) (bm/on-server bookmarks server))
                               {:folder folder}))))

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
