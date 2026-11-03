(ns claudia.slave.meta
  "What every chat of the profile shares: the plan's usage limits (5-hour, week) from the SDK's
  rate_limit_event, kept per token (a hash of it, never the token); the models with their effort
  levels and the slash commands, read once by a short Claude Code process that sends no message."
  (:require ["@anthropic-ai/claude-agent-sdk" :as sdk]
            ["node:crypto" :as crypto]
            [claudia.slave.store :as store]))

(def ^:private labels
  {"five_hour" "5-hour" "seven_day" "Week" "seven_day_opus" "Week, Opus" "seven_day_sonnet" "Week, Sonnet"})

;; {:limits [{:key :label :percent :resetsAt}] :models [{:value :name :efforts}]
;;  :commands [{:name :description :hint}] :account (token hash)}
(defonce state (atom {:limits [] :models [] :commands [] :account nil}))
;; Set by the runner: (fn [meta]) when it changed.
(defonce on-change (atom (fn [_])))

(defn token-key
  "The token's name in the store: the start of its SHA-256, never the token."
  [token]
  (-> (crypto/createHash "sha256") (.update (str token)) (.digest "hex") (subs 0 16)))

(defn- publish! [m]
  (let [before @state
        after (swap! state merge m)]
    (when (not= before after) (@on-change after))))

(defn use-token!
  "The token chats run on now: its last limits show until the next rate_limit_event."
  [token]
  (let [k (token-key token)]
    (when (not= k (:account @state))
      (publish! {:account k :limits (or (store/kv "" (str "limits:" k)) [])}))))

(defn- percent [u] (js/Math.round (if (<= u 1) (* 100 u) u)))

(defn note-limits!
  "A rate_limit_event's info: every window it carries (unifiedWindows), else its one window."
  [token info]
  (let [k (token-key token)
        windows (:unifiedWindows info)
        fresh (if (map? windows)
                (vec (for [[wk label] labels
                           :let [w (get windows (keyword wk))]
                           :when (number? (:utilization w))]
                       {:key wk :label label :percent (percent (:utilization w))
                        :resetsAt (when (:resetsAt w) (* 1000 (:resetsAt w)))}))
                (when (and (labels (:rateLimitType info)) (number? (:utilization info)))
                  [{:key (:rateLimitType info) :label (labels (:rateLimitType info))
                    :percent (percent (:utilization info))
                    :resetsAt (when (:resetsAt info) (* 1000 (:resetsAt info)))}]))]
    (when (seq fresh)
      (let [old (or (store/kv "" (str "limits:" k)) [])
            merged (vec (sort-by #(.indexOf (to-array (keys labels)) (:key %))
                                 (vals (merge (into {} (map (juxt :key identity)) old)
                                              (into {} (map (juxt :key identity)) fresh)))))]
        (store/kv! "" (str "limits:" k) merged)
        (when (= k (:account @state)) (publish! {:limits merged}))
        (when (nil? (:account @state)) (publish! {:account k :limits merged}))))))

(defonce ^:private probing (atom false))

(defn- limits-from
  "The plan usage call's rate_limits as Limits (src/meta.ts limitsFrom)."
  [rl]
  (vec (concat
        (for [[k label] labels
              :let [v (get rl (keyword k))]
              :when (number? (:utilization v))]
          {:key k :label label :percent (percent (:utilization v))
           :resetsAt (some-> (:resets_at v) js/Date.parse)})
        (for [m (:model_scoped rl)
              :when (and (number? (:utilization m)) (pos? (:utilization m)))]
          {:key (str "model:" (:display_name m)) :label (str "Week, " (:display_name m))
           :percent (percent (:utilization m)) :resetsAt (some-> (:resets_at m) js/Date.parse)}))))

(defn probe!
  "Models, slash commands and the plan's usage (src/meta.ts probe), from a Claude Code process that
  never gets a message. token: the one in use (its usage, when the plan reports it)."
  [cwd env]
  (when-not @probing
    (reset! probing true)
    (let [never #js {}
          _ (aset never js/Symbol.asyncIterator
                  (fn [] #js {:next (fn [] (js/Promise. (fn [_])))}))
          ^js q (sdk/query #js {:prompt never :options #js {:cwd cwd :persistSession false :env (clj->js env)}})
          k (some-> (get env "CLAUDE_CODE_OAUTH_TOKEN") token-key)]
      (-> (js/Promise.all #js [(.supportedModels q) (.supportedCommands q)])
          (.then (fn [[models commands]]
                   (publish!
                    {:models (vec (for [^js m models]
                                    {:value (.-value m) :name (.-displayName m)
                                     :efforts (vec (or (.-supportedEffortLevels m) []))}))
                     :commands (vec (sort-by :name (for [^js c commands]
                                                     {:name (.-name c) :description (.-description c)
                                                      :hint (.-argumentHint c)})))})
                   ;; Marked experimental by the SDK; the rate_limit_event path keeps working without it.
                   (-> (js/Promise.resolve (.usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET q #js {:skipBehaviors true}))
                       (.then (fn [u]
                                (let [u (js->clj u :keywordize-keys true)]
                                  (when (and k (:rate_limits_available u))
                                    (let [limits (limits-from (:rate_limits u))]
                                      (store/kv! "" (str "limits:" k) limits)
                                      (when (= k (:account @state)) (publish! {:limits limits})))))))
                       (.catch (fn [_] nil)))))
          (.catch (fn [e] (js/console.error "meta probe:" (str (or (.-message e) e)))))
          (.finally (fn [] (.close q) (reset! probing false)))))))

(defn frame [] (assoc (select-keys @state [:limits :models :commands]) :type "meta"))
