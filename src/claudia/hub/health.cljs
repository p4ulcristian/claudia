(ns claudia.hub.health
  "Rolling back a release that broke people's pages (README decision 35). Each page load reports
  once (POST /hub/health, claudia.web.health): it worked (its chat list came) or why not. In the
  hub's first minutes on a commit, broken loads with no working one mean that release is broken: the
  hub asks the gate's helper and every server to go back one release (\"rollback <commit>\"), once.
  Each machine checks for itself that it runs that commit and keeps the release before it, and never
  installs that commit again; main moving past it moves everyone forward. A page that opened no
  socket counts only when one of the person's servers answers: a server that is off is not a broken
  release."
  (:require [clojure.string :as str]
            [claudia.node.env :refer [env]]
            [claudia.node.http :as io]
            [claudia.hub.announce :as announce]
            [claudia.hub.config :as config]
            [claudia.hub.pass :as pass]))

(def window-ms "How long after the hub starts on a commit its pages can roll it back." (* 15 60 1000))

(def broken-needed "Broken page loads (and no working one) that roll a release back." 2)

(def reasons #{"no-chats" "script-error" "no-socket"})

(defonce ^:private state (atom {:started (js/Date.now) :ok #{} :broken {} :rolled false}))

(defn commit
  "The commit the hub runs, as the gate names it (HUB_COMMIT, 40 hex), or nil."
  []
  (re-matches #"^[0-9a-f]{40}$" (str (env "HUB_COMMIT" ""))))

(defn report
  "A page's report read from its body: {:load :commit :ok :reason :detail}, or nil when it is not one."
  [{:keys [load ok reason detail] :as body}]
  (let [c (:commit body)]
    (when (and (string? load) (re-matches #"^[A-Za-z0-9-]{8,64}$" load)
               (string? c) (re-matches #"^[0-9a-f]{7,40}$" c)
               (boolean? ok) (or ok (contains? reasons reason)))
      {:load load :commit c :ok ok :reason (when-not ok reason)
       :detail (when (string? detail) (subs detail 0 (min 300 (count detail))))})))

(defn counts?
  "Whether a report is about the release running now (the page loaded this commit), within window."
  [{:keys [started]} hub-commit rep now]
  (boolean (and hub-commit rep
                (str/starts-with? hub-commit (:commit rep))
                (< (- now started) window-ms))))

(defn with-report
  "The tally with one page load's report (a load counts once, its first word kept)."
  [tally {:keys [load ok] :as rep}]
  (cond
    (or (contains? (:ok tally) load) (contains? (:broken tally) load)) tally
    ok (update tally :ok conj load)
    :else (assoc-in tally [:broken load] (select-keys rep [:reason :detail]))))

(defn roll-back?
  "Whether the tally says the release is broken: enough broken loads, none working, not rolled yet."
  [{:keys [ok broken rolled]}]
  (and (not rolled) (empty? ok) (>= (count broken) broken-needed)))

(defn- roll-back! [hub-commit tally]
  (js/console.log (str "health: rolling back " (subs hub-commit 0 7) ": " (count (:broken tally))
                       " broken page loads, none working: " (pr-str (vals (:broken tally)))))
  (announce/ask-gate! (str "rollback " hub-commit))
  (announce/poke-all! {:rollback hub-commit}))

(defn- take-report!
  "One report counted; the release rolled back when it tips the tally."
  [hub-commit rep]
  (let [[before after] (swap-vals! state (fn [t]
                                           (let [t2 (with-report t rep)]
                                             (cond-> t2 (roll-back? t2) (assoc :rolled true)))))]
    (when-not (= (:broken before) (:broken after))
      (js/console.log (str "health: a broken page load on " (subs hub-commit 0 7) ": " (:reason rep)
                           (when (:detail rep) (str " (" (:detail rep) ")")))))
    (when (and (:rolled after) (not (:rolled before)))
      (roll-back! hub-commit after))))

(defn- any-up?
  "Calls (done true) when one of the person's servers answers, else (done false)."
  [person email done]
  (let [servers (config/servers-of person)
        left (atom (count servers))
        said (atom false)]
    (if (empty? servers)
      (done false)
      (doseq [s servers]
        (pass/up? s email (fn [up]
                            (swap! left dec)
                            (cond (and up (not @said)) (do (reset! said true) (done true))
                                  (and (zero? @left) (not @said)) (done false))))))))

(defn call
  "POST /hub/health: a page load's report (200; 400 when it is not one)."
  [^js req ^js res person email]
  (if (not= "POST" (.-method req))
    (do (.setHeader res "allow" "POST") (io/send-json res 405 {:error "POST only"}))
    (.then (io/read-json req 4096)
           (fn [body]
             (let [rep (report body)
                   hub-commit (commit)]
               (if-not rep
                 (io/send-json res 400 {:error "not a page report"})
                 (do (io/send-json res 200 {:ok true})
                     (when (counts? @state hub-commit rep (js/Date.now))
                       (if (= "no-socket" (:reason rep))
                         (any-up? person email #(when % (take-report! hub-commit rep)))
                         (take-report! hub-commit rep))))))))))
