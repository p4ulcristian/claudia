(ns claudia.web.views.dot
  "A chat's circle, the same on the rail and in the chat list (web/chatdot.tsx of the TypeScript
  Claudia): the project's face (its logo, else its short tag, else a small dot for a chat without a
  project) and its state (? waiting on you, working dots, the agents ring after the turn, ✓ done
  since you looked), with a chalk moon per agent while the turn runs."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.web.chatops :as ops]
            [claudia.web.views.agents :as agents]))

(defn- state-badge [state]
  (case state
    :waiting [:span.state-badge.waiting {:title "Waiting for you"} "?"]
    :thinking [:span.state-badge.thinking {:title "Working"} [:i] [:i] [:i]]
    :done [:span.state-badge.done {:title "Done"} "✓"]
    nil))

(defn- moons
  "Agents still working while Claude's turn runs: small chalk circles around the circle's left and
  bottom, one each (up to 5)."
  [n]
  (when (pos? n)
    [:span.moons {:title (str n " agent" (when (> n 1) "s") " working")}
     (for [i (range (min n 5))
           :let [a (/ (* (+ 160 (* i 32)) js/Math.PI) 180)]]
       ^{:key i}
       [:i {:style {:left (str "calc(50% + " (* (js/Math.cos a) 21) "px)")
                    :top (str "calc(50% - " (* (js/Math.sin a) 21) "px)")
                    :animation-delay (str (* i 0.25) "s")}}])]))

(defn project-tags
  "Short tags for projects, unique among the ones listed: initials of a dashed name (eye-on-rainbow
  -> eor), else the first letters, three when two projects would share two."
  [names]
  (let [all (distinct (filter seq names))
        tag (fn [n len]
              (let [parts (remove str/blank? (str/split n #"[-_. ]+"))]
                (str/lower-case (if (> (count parts) 1)
                                  (subs (apply str (map first parts)) 0 (min 3 (count parts)))
                                  (subs n 0 (min len (count n)))))))]
    (into {} (for [n all
                   :let [clash (some #(and (not= % n) (= (tag % 2) (tag n 2))) all)]]
               [n (tag n (if clash 3 2))]))))

(rf/reg-sub :projects (fn [db] (:projects db)))

(rf/reg-sub
 :project-tags
 ;; Every listed chat's project name -> its short tag.
 (fn [db]
   (project-tags (keep (fn [[_ p]] (:name p)) (:projects db)))))

(defn project-face
  "A project's face: its logo, else its short tag, else a small dot for a folder without a project.
  The chat's circle and the chat header's project picker draw it."
  [_server _folder]
  (let [broken (r/atom false)]
    (fn [server folder]
      (let [project (when (seq folder) @(rf/subscribe [:project server folder]))
            view @(rf/subscribe [:view])
            tags @(rf/subscribe [:project-tags])]
        (when (and (seq folder) (nil? project)) (rf/dispatch [:ops/project server folder]))
        (cond
          (and (:logo project) (not @broken))
          [:img.rail-logo {:src (ops/logo-url view server folder) :alt "" :on-error #(reset! broken true)}]
          (:name project) [:span.rail-tag (get tags (:name project) "")]
          :else [:span.rail-blank {:aria-hidden true}])))))

(defn chat-dot
  "row: a list row with :server. done?: finished since you looked (never in a read-only view, never
  the open chat)."
  [{:keys [server folder waiting running agents]} done?]
  (let [agents (or agents 0)
        state (cond waiting :waiting running :thinking done? :done)]
    [:span.rail-dot
     [project-face server folder]
     (if (and (not waiting) (not running) (pos? agents))
       [agents/agents-ring agents]
       [state-badge state])
     [moons (if running agents 0)]]))
