(ns claudia.web.views.pure.system-feed
  "Where the System sheet asks (README decision 96, PROTOCOL.md), pure: the servers it has a tab
  for, each one's hub routes, the tab it opens on, and the chats' proposals about one server.")

(def gate-name "The gate's tab (admins only); its labels name it so too." "gate")

(defn tabs
  "One tab per server the person may use ({:name :label}), then the gate for admins."
  [me]
  (cond-> (mapv (fn [s] {:name (:name s) :label (or (:label s) (:name s))}) (:servers me))
    (:admin me) (conj {:name gate-name :label gate-name :gate? true})))

(defn opening-tab
  "The tab System opens on: the open chat's server when it has one, else the first."
  [all server]
  (or (some #(when (= server (:name %)) server) all) (:name (first all))))

(defn base-url
  "A tab's Sample route on the hub; details, history and unit-log hang under it."
  [{server :name :keys [gate?]}]
  (if gate? "/hub/gate/system" (str "/hub/servers/" (js/encodeURIComponent server) "/system")))

(defn details-url [tab] (str (base-url tab) "/details"))

(defn history-url "The last hours of minute rows." [tab hours]
  (str (base-url tab) "/history?hours=" hours))

(defn unit-log-url
  "A unit's last n journal lines; nil for the gate (the hub reads no journal there)."
  [tab {:keys [unit scope user]} n]
  (when-not (:gate? tab)
    (str (base-url tab) "/unit-log"
         "?unit=" (js/encodeURIComponent unit) "&scope=" (or scope "system")
         (when user (str "&user=" (js/encodeURIComponent user))) "&n=" n)))

(defn proposals-for
  "The proposals about server out of every runner's answer ({runner [proposal]}; a proposal without
  :server is about its runner's own), each with :runner (where it is taken away), oldest first."
  [by-runner server]
  (->> (for [[runner ps] by-runner
             p ps
             :when (= server (or (:server p) runner))]
         (assoc p :runner runner))
       (sort-by :at)
       vec))
