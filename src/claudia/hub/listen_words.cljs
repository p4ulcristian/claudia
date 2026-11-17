(ns claudia.hub.listen-words
  "The caller's own words for the listen service (names and terms it should hear right): the hub
  passes them to <HUB_LISTEN_URL>/v1/vocabulary/<email> with the listen token. The speaker is
  always the signed-in caller (lower case, from the sign-in), never a name in the request, so a
  person reads and writes only their own list.

  GET /hub/listen/words -> {words: [string]}; PUT /hub/listen/words {words: [string]} -> {words}
  as the service keeps them. 400 when words is not a list of strings or is too long, 501 without a
  listen service, 502 when the service fails."
  (:require [claudia.hub.listen :as listen]
            [claudia.node.http :as io]
            [clojure.string :as str]))

(def max-words "The most words one person's list holds." 500)
(def max-chars "The longest word or phrase in a list, in characters." 60)

(defn- one-spaced [s] (str/replace (str/trim s) #"\s+" " "))

(defn- without-repeats
  "ws without a word met before (case ignored), the first kept."
  [ws]
  (:kept (reduce (fn [{:keys [seen] :as acc} w]
                   (let [k (str/lower-case w)]
                     (if (seen k) acc (-> acc (update :seen conj k) (update :kept conj w)))))
                 {:seen #{} :kept []} ws)))

(defn checked-words
  "{:words [string]} from a PUT body's words: each trimmed (inner runs of white space made one
  space), blanks dropped, repeats dropped (case ignored, the first kept); or {:error text} when
  words is not a list of strings, a word is over max-chars or there are over max-words."
  [words]
  (if-not (and (sequential? words) (every? string? words))
    {:error "words: a list of strings"}
    (let [kept (->> words (map one-spaced) (remove str/blank?) without-repeats)]
      (cond
        (some #(> (count %) max-chars) kept) {:error (str "a word is over " max-chars " characters")}
        (> (count kept) max-words) {:error (str "over " max-words " words")}
        :else {:words kept}))))

(defn words-of
  "The words from the service's answer: its strings only ([] when there are none)."
  [answer]
  (let [ws (:words answer)]
    (if (sequential? ws) (filterv string? ws) [])))

(defn- vocabulary [{:keys [url token]} email method words]
  (-> (js/fetch (str url "/v1/vocabulary/" (js/encodeURIComponent email))
                (clj->js (cond-> {:method method :signal (js/AbortSignal.timeout 15000)
                                  :headers {:authorization (str "Bearer " token) :accept "application/json"}}
                           words (-> (assoc-in [:headers :content-type] "application/json")
                                     (assoc :body (js/JSON.stringify (clj->js {:words words})))))))
      (.then (fn [^js r]
               (when-not (.-ok r) (throw (js/Error. (str "listen answered " (.-status r)))))
               (.json r)))
      (.then #(hash-map :words (words-of (js->clj % :keywordize-keys true))))))

(defn- put-words [^js req ^js res email c]
  (-> (io/read-json req 65536)
      (.then #(checked-words (:words %)) (fn [e] {:error (.-message e)}))
      (.then (fn [{:keys [words error]}]
               (if error
                 (io/send-json res 400 {:error error})
                 (.then (vocabulary c email "PUT" words) #(io/send-json res 200 %)))))))

(defn- answer [^js req ^js res email c]
  (cond
    (not c) (io/send-json res 501 {:error "no listen service on this hub (HUB_LISTEN_URL, HUB_LISTEN_TOKEN_FILE)"})
    (= "GET" (.-method req)) (.then (vocabulary c email "GET" nil) #(io/send-json res 200 %))
    :else (put-words req res email c)))

(defn call
  "GET or PUT /hub/listen/words for a signed-in caller (email: lower case, from the sign-in): the
  caller's own list."
  [^js req ^js res email]
  (if-not (#{"GET" "PUT"} (.-method req))
    (do (.setHeader res "allow" "GET, PUT")
        (io/send-json res 405 {:error "GET or PUT"}))
    (-> (listen/config)
        (.then #(answer req res email %))
        (.catch (fn [e] (when-not (.-headersSent res) (io/send-json res 502 {:error (.-message e)})))))))
