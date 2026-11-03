(ns claudia.slave.meta
  "What every chat of the profile shares: the plan's usage limits (5-hour, week) from the SDK's
  rate_limit_event, kept per token (a hash of it, never the token); the models with their effort
  levels and the slash commands, read once by a short Claude Code process that sends no message."
  (:require ["@anthropic-ai/claude-agent-sdk" :as sdk]
            [claudia.node.crypto :refer [sha256-hex]]
            ["node:fs" :as fs]
            [claudia.node.env :as env]
            [claudia.shared.text :as text]
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
  (subs (sha256-hex (str token)) 0 16))

(defn- publish! [m]
  (let [before @state
        after (swap! state merge m)]
    (when (not= before after) (@on-change after))))

(defn- limits-key
  "Where a token's last limits are kept (k: its key)."
  [k]
  (str "limits:" k))

(defn use-token!
  "The token chats run on now: its last limits show until the next rate_limit_event."
  [token]
  (let [k (token-key token)]
    (when (not= k (:account @state))
      (publish! {:account k :limits (or (store/kv "" (limits-key k)) [])}))))

(defn percent
  "A utilization as a whole percent (a fraction up to 1, else already a percent)."
  [u]
  (js/Math.round (if (<= u 1) (* 100 u) u)))

(defn- event-limit
  "One window of a rate_limit_event as a Limit."
  [k label w]
  {:key k :label label :percent (percent (:utilization w))
   :resetsAt (when (:resetsAt w) (* 1000 (:resetsAt w)))})

(defn event-limits
  "A rate_limit_event's info as Limits: every window it carries (unifiedWindows), else its one window."
  [info]
  (let [windows (:unifiedWindows info)]
    (if (map? windows)
      (vec (for [[wk label] labels
                 :let [w (get windows (keyword wk))]
                 :when (number? (:utilization w))]
             (event-limit wk label w)))
      (when (and (labels (:rateLimitType info)) (number? (:utilization info)))
        [(event-limit (:rateLimitType info) (labels (:rateLimitType info)) info)]))))

(defn merge-limits
  "The kept Limits with the fresh ones laid over them (by key), in the labels' order."
  [old fresh]
  (vec (sort-by #(.indexOf (to-array (keys labels)) (:key %))
                (vals (merge (into {} (map (juxt :key identity)) old)
                             (into {} (map (juxt :key identity)) fresh))))))

(defn note-limits!
  "A rate_limit_event's info: every window it carries (unifiedWindows), else its one window."
  [token info]
  (let [k (token-key token)
        fresh (event-limits info)]
    (when (seq fresh)
      (let [merged (merge-limits (or (store/kv "" (limits-key k)) []) fresh)]
        (store/kv! "" (limits-key k) merged)
        (when (= k (:account @state)) (publish! {:limits merged}))
        (when (nil? (:account @state)) (publish! {:account k :limits merged}))))))

(defonce ^:private probing (atom false))

(defn limits-from
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

(defn models-of
  "The SDK's supported models (JS) as {:value :name :efforts}."
  [models]
  (vec (for [^js m models]
         {:value (.-value m) :name (.-displayName m)
          :efforts (vec (or (.-supportedEffortLevels m) []))})))

(defn commands-of
  "The SDK's supported slash commands (JS) as {:name :description :hint}, by name."
  [commands]
  (vec (sort-by :name (for [^js c commands]
                        {:name (.-name c) :description (.-description c) :hint (.-argumentHint c)}))))

(defn- never-prompt
  "A prompt stream that never yields: the probe's Claude Code gets no message."
  []
  (doto #js {}
    (aset js/Symbol.asyncIterator (fn [] #js {:next (fn [] (js/Promise. (fn [_])))}))))

(defn- read-default-model!
  "The model a new chat gets when none is picked (the user's settings), e.g. claude-opus-5-5[1m]:
  the picker's label for \"default\"."
  [^js q]
  (-> (js/Promise.resolve (.getContextUsage q #js {:detail "summary"}))
      (.then (fn [^js c] (when (seq (some-> c .-model)) (publish! {:defaultModel (.-model c)}))))
      (.catch (fn [_] nil))))

(defn- read-usage!
  "The plan's usage for token key k. Marked experimental by the SDK; the rate_limit_event path keeps
  working without it."
  [^js q k]
  (-> (js/Promise.resolve (.usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET q #js {:skipBehaviors true}))
      (.then (fn [u]
               (let [u (js->clj u :keywordize-keys true)]
                 (when (and k (:rate_limits_available u))
                   (let [limits (limits-from (:rate_limits u))]
                     (store/kv! "" (limits-key k) limits)
                     (when (= k (:account @state)) (publish! {:limits limits})))))))
      (.catch (fn [_] nil))))

(defn probe!
  "Models, slash commands and the plan's usage (src/meta.ts probe), from a Claude Code process that
  never gets a message. token: the one in use (its usage, when the plan reports it)."
  [cwd env]
  (when-not @probing
    (reset! probing true)
    (let [^js q (sdk/query #js {:prompt (never-prompt) :options #js {:cwd cwd :persistSession false :env (clj->js env)}})
          k (some-> (get env "CLAUDE_CODE_OAUTH_TOKEN") token-key)]
      (-> (js/Promise.all #js [(.supportedModels q) (.supportedCommands q)])
          (.then (fn [[models commands]]
                   (publish! {:models (models-of models) :commands (commands-of commands)})
                   (js/Promise.all #js [(read-default-model! q) (read-usage! q k)])))
          (.catch (fn [e] (js/console.error "meta probe:" (text/error-text e))))
          (.finally (fn [] (.close q) (reset! probing false)))))))

(defn- account-label
  "Whose token is in use (the saved token's name from Settings, src/account.ts label), or nil."
  []
  (when-let [f (env/env "CLAUDIA_TOKEN_FILE")]
    (try (not-empty (:name (js->clj (js/JSON.parse (fs/readFileSync (str f ".json") "utf8")) :keywordize-keys true)))
         (catch :default _ nil))))

(defn frame
  "The meta frame (src/meta.ts Meta): limits, models, commands, the default model, and the token
  in use (its name as a hash, and whose it is)."
  []
  (assoc (select-keys @state [:limits :models :commands :defaultModel])
         :type "meta"
         :account (some->> (:account @state) (str "token-"))
         :accountLabel (account-label)))
