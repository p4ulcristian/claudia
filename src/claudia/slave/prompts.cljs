(ns claudia.slave.prompts
  "Prompt profiles: named texts this server keeps (README decision 60). A chat that picks one
  (settings.prompt, the profile's name) gets its text after Claudia's part of the system prompt,
  so the picture, secrets and folder rules stay. The page's calls: GET /api/prompts, PUT and DELETE
  /api/prompts/<name>."
  (:require [clojure.string :as str]
            [claudia.node.http :as io]
            [claudia.shared.protocol :as p]
            [claudia.slave.codeforms :as codeforms]
            [claudia.slave.store :as store]))

(def max-name "The longest profile name." 60)
(def max-text "The longest profile text (characters)." 20000)

(defn clean-name
  "A profile name as kept: trimmed, at most max-name characters; nil when blank or not a string."
  [s]
  (when (string? s)
    (let [t (str/trim s)]
      (when-not (str/blank? t) (subs t 0 (min max-name (count t)))))))

(defn valid-profiles
  "The kept value as profiles: [{:name :text}], each name once (the first), sorted by name."
  [v]
  (->> (if (sequential? v) v [])
       (keep (fn [m] (when-let [n (and (map? m) (clean-name (:name m)))]
                       {:name n :text (if (string? (:text m)) (:text m) "")})))
       (reduce (fn [acc m] (if (some #(= (:name %) (:name m)) acc) acc (conj acc m))) [])
       (sort-by (comp str/lower-case :name))
       vec))

(defn with-profile
  "profiles with the profile n set to text (added when new)."
  [profiles n text]
  (valid-profiles (conj (vec (remove #(= n (:name %)) profiles)) {:name n :text text})))

(defn without-profile [profiles n] (vec (remove #(= n (:name %)) profiles)))

(defn find-profile [profiles n] (some #(when (= n (:name %)) %) profiles))

(defn prompt-part
  "What a chat on profile p adds to the system prompt (nil: none, or a blank text)."
  [p]
  (when-not (str/blank? (:text p))
    (str "# Prompt profile: " (:name p) "\n\n" (str/trim (:text p)))))

;; ---- kept on this server ----------------------------------------------------------------------

(defn profiles [] (valid-profiles (store/kv "" "promptProfiles")))

(defn profile [n] (find-profile (profiles) n))

(defn- keep! [ps] (store/kv! "" "promptProfiles" ps) ps)

(defn answer
  "What the page gets: the profiles and the built-in part every chat has (Claudia's part of the
  system prompt, after Claude Code's own; shown locked above the profiles)."
  []
  {:profiles (profiles) :builtIn (codeforms/prompt-for)})

;; ---- the page's calls -------------------------------------------------------------------------

(defn route
  "Which prompt call a request is: {:op :list|:save|:delete :name} or nil."
  [method url]
  (let [[_ enc] (re-matches #"/api/prompts/([^/]+)" url)
        n (when enc (clean-name (try (js/decodeURIComponent enc) (catch :default _ nil))))]
    (cond
      (and (= url "/api/prompts") (= method "GET")) {:op :list}
      (and n (= method "PUT")) {:op :save :name n}
      (and n (= method "DELETE")) {:op :delete :name n}
      :else nil)))

(defn- save! [^js req ^js res n changed!]
  (-> (io/read-text req (* 4 max-text))
      (.then (fn [t]
               (let [text (:text (p/read-json t))]
                 (if (and (string? text) (<= (count text) max-text))
                   (do (keep! (with-profile (profiles) n text))
                       (changed!)
                       (io/send-json res 200 (answer)))
                   (io/send-json res 400 {:error (str "text: a string of at most " max-text " characters")})))))
      (.catch #(io/send-json res 400 {:error (str (.-message %))}))))

(defn handle
  "The page's prompt calls; true when this was one. changed!: a profile's text changed or it went
  (chats on it get the new system prompt after their turn)."
  [^js req ^js res url who changed!]
  (let [{:keys [op] n :name} (route (.-method req) url)]
    (cond
      (nil? op) nil
      (= op :list) (io/send-json res 200 (answer))
      (:read-only who) (io/send-json res 403 {:error "Read only."})
      (= op :save) (save! req res n changed!)
      (= op :delete) (do (keep! (without-profile (profiles) n))
                         (changed!)
                         (io/send-json res 200 (answer))))
    (some? op)))
