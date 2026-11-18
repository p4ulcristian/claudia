(ns claudia.web.views.pure.menu
  "Where a menu opened at the mouse sits, the chat menu's Move to project (the person's bookmarks on
  top, README decision 61), and the rail's subscription button, as plain data."
  (:require [clojure.string :as str]
            [claudia.shared.bookmarks :as bookmarks]
            [claudia.web.views.pure.sidebar :as sidebar]))

(defn menu-pos
  "A menu opened at x y kept 6 px inside the page's visible layer (l: its left top width height),
  the menu being w by h."
  [x y {:keys [left top width height]} w h]
  {:left (max 6 (min (- x left) (- width w 6)))
   :top (max 6 (min (- y top) (- height h 6)))})

(defn initials
  "\"Paul\" -> \"P\", \"Ananda Mishung\" -> \"AM\"."
  [person]
  (str/upper-case (apply str (take 2 (map first (str/split (str/trim (str person)) #"\s+"))))))

(defn picture-of
  "The picture address of profile's owner (nil profile: yours): me is /hub/me ({:email :people}),
  owners {profile email} from the servers' /api/me."
  [me owners profile]
  (let [email (if profile (get owners profile) (:email me))]
    (some #(when (= email (:email %)) (:picture %)) (:people me))))

(defn profile-face
  "The profile circle at the bottom left: whose chats show. me: /hub/me ({:name :email :people});
  view: the profile looked at (nil: your own); owners {profile email}. -> {:initials :name :other?
  :picture}."
  [me view owners]
  (let [nm (if view (str (str/upper-case (subs view 0 1)) (subs view 1)) (or (:name me) "Me"))]
    {:initials (initials nm) :name nm :other? (some? view) :picture (picture-of me owners view)}))

(defn k-tokens
  "Tokens in thousands: 155k (0 for none)."
  [n]
  (str (js/Math.round (/ (or n 0) 1000)) "k"))

(defn move-targets
  "The projects a chat on server can move to: the ones that server's chats (rows of every server)
  are in, [{:key (its root) :label :count}], most chats first; only those whose name holds text."
  [rows server text]
  (let [q (sidebar/search-key text)]
    (->> (sidebar/project-chips (filter #(= server (:server %)) rows))
         (remove #(= "" (:key %)))
         (filter #(or (empty? q) (str/includes? (str/lower-case (:label %)) q)))
         vec)))

(defn with-bookmarks
  "The move targets with the person's bookmarks on server first ({:key folder :label :bookmark
  true}, those whose label or folder holds text), each folder once."
  [targets items server text]
  (let [q (sidebar/search-key text)
        marks (for [b (bookmarks/on-server items server)
                    :let [label (bookmarks/label-of b)]
                    :when (or (empty? q) (str/includes? (str/lower-case (str label " " (:folder b))) q))]
                {:key (:folder b) :label label :bookmark true})
        marked (set (map :key marks))]
    (into (vec marks) (remove #(marked (:key %))) targets)))

(defn typed-folder
  "What Enter in the move box sends: the one project left by the text (targets: move-targets for it)
  when the text is no path, else the text as a folder; nil for no text."
  [targets text]
  (let [t (str/trim (str text))]
    (cond (str/blank? t) nil
          (and (not (re-find #"[/~]" t)) (= 1 (count targets))) (:key (first targets))
          :else t)))
