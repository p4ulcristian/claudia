(ns claudia.web.views.dot
  "A chat's circle, the same on the rail and in the chat list (web/chatdot.tsx of the TypeScript
  Claudia): the project's face (its logo, else its short tag, else a small dot for a chat without a
  project) and its state (? waiting on you, working dots, the agents ring after the turn, ✓ done
  since you looked), with a chalk moon per agent while the turn runs."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.web.chatops :as ops]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.pure.sidebar :as sidebar-pure]
            [claudia.web.views.agents :as agents]
            [claudia.web.views.voice-ring :as voice-ring]))

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
  "Short tags for projects, unique among the ones listed: initials of a dashed name (iron-rainbow
  -> ir), else the first letters, three when two projects would share two."
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
  The chat's circle draws it; with no-logo (the Folders modal's
  bookmark cards) that is drawn instead of the tag and the dot."
  [_server _folder & _]
  (let [broken (r/atom false)]
    (fn [server folder & [no-logo]]
      (let [project (when (seq folder) @(rf/subscribe [:project server folder]))
            view @(rf/subscribe [:view])
            tags @(rf/subscribe [:project-tags])]
        (when (and (seq folder) (nil? project)) (rf/dispatch [:ops/project server folder]))
        (cond
          (and (:logo project) (not @broken))
          [:img.rail-logo {:src (ops/logo-url view server folder) :alt "" :on-error #(reset! broken true)}]
          no-logo no-logo
          (:name project) [:span.rail-tag (get tags (:name project) "")]
          :else [:span.rail-blank {:aria-hidden true}])))))

(defn- work-ring
  "Twelve green dots round a working chat's circle, a seconds dial: the dot of the clock's seconds
  under the name is lit (5 seconds a dot), so it steps every 5 seconds (2026-11-18: no spinning).
  since: when the shown clock started (ms), nil for the wall clock."
  [since]
  (let [on (sidebar-pure/second-dot since (hooks/use-now))]
    [:svg.work-ring {:viewBox "0 0 50 50" :aria-hidden true}
     (for [i (range 12) :let [a (* i (/ js/Math.PI 6))]]
       ^{:key i} [:circle {:class (when (= i on) "on") :cx (+ 25 (* 24.5 (js/Math.sin a))) :cy (- 25 (* 24.5 (js/Math.cos a)))
                           :r (if (= i on) 3 2)}])]))

(def ^:private root-badge
  "A red # on the circle while the chat's root switch is on (README decision 52)."
  [:span.root-badge {:title "Root on" :aria-label "Root on"} "#"])

(defn chat-dot
  "row: a list row with :server. done?: finished since you looked (never in a read-only view, never
  the open chat). plain?: the rail's circle (README decision 46): no badges, only the ticking
  dotted ring while the chat works; its line under the name says the rest. Both show the voice's
  bars round the circle while its reply is read aloud (claudia.web.views.voice-ring). badge: drawn on the
  circle too (the server's mark, README decision 81)."
  [{:keys [server id folder waiting running agents runningSince agentsSince root]} done? & [plain? badge]]
  (let [agents (or agents 0)
        state (cond waiting :waiting running :thinking done? :done)
        talking? (= [server id] (:k @(rf/subscribe [:speaking])))]
    (if plain?
      [:span.rail-dot
       (when talking? [:f> voice-ring/ring])
       [project-face server folder]
       (when (or running (pos? agents)) [:f> work-ring (if (and running runningSince) runningSince agentsSince)])
       (when root root-badge)
       badge]
      [:span.rail-dot
       (when talking? [:f> voice-ring/ring])
       [project-face server folder]
       (if (and (not waiting) (not running) (pos? agents))
         [agents/agents-ring agents]
         [state-badge state])
       [moons (if running agents 0)]
       (when root root-badge)
       badge])))
