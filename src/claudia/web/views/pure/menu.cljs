(ns claudia.web.views.pure.menu
  "Where a menu opened at the mouse sits, when a finger's press is still a long press, the profile
  circle, and token counts, as plain data."
  (:require [clojure.string :as str]))

(defn menu-pos
  "A menu opened at x y kept 6 px inside the page's visible layer (l: its left top width height),
  the menu being w by h."
  [x y {:keys [left top width height]} w h]
  {:left (max 6 (min (- x left) (- width w 6)))
   :top (max 6 (min (- y top) (- height h 6)))})

(defn moved-far?
  "Whether a finger that came down at [x0 y0] and is now at [x y] moved more than 10 px: no long
  press any more, a scroll or a drag."
  [[x0 y0] [x y]]
  (let [dx (- x x0) dy (- y y0)]
    (> (+ (* dx dx) (* dy dy)) 100)))

(defn fork-state
  "What the fork asked last (fork: {:k :busy :error}) means for chat k: {:forking? :error}."
  [fork k]
  (if (= k (:k fork))
    {:forking? (boolean (:busy fork)) :error (:error fork)}
    {:forking? false :error nil}))

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

