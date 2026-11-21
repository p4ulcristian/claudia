(ns claudia.hub.listen
  "Speech to text for the composer's mic. The page posts a clip (16 kHz WAV from its warm
  microphone, claudia.web.mic) as the body, its type as content-type; the hub passes it to
  the listen service (HUB_LISTEN_URL, its token in HUB_LISTEN_TOKEN_FILE or HUB_LISTEN_TOKEN):
  <url>/v1/audio/transcriptions, OpenAI's shape (a multipart `file`) plus `speaker`: the signed-in
  caller's email, so the service hears with that person's words (claudia.hub.listen-words), and
  `context`: the chat's last words the page sends in x-listen-context, so names in them are heard
  right ->
  {text, raw}: text cleaned up, raw as heard (a service without cleanup answers {text} only: raw is
  then the same text). No language is given, so the service writes down whatever language was spoken.

  GET /hub/listen -> {listen: bool}; POST /hub/listen <clip> -> {text, raw}, or {error} (501 without
  a listen service)."
  (:require [claudia.node.env :as env]
            [claudia.node.http :as io]
            [clojure.string :as str]))

(def ^:private max-bytes
  "The largest clip: 16 kHz 16-bit WAV is 32 kB a second, so about 12 minutes (the service takes 25 MB)."
  (* 24 1024 1024))

(defn config "A promise of {:url :token} or nil." [] (env/service "HUB_LISTEN"))

(defn clip-name
  "A file name for the clip by its content type (the service reads the clip itself; the name only
  helps its logs)."
  [content-type]
  (let [t (str/lower-case (str content-type))]
    (cond
      (str/includes? t "webm") "clip.webm"
      (or (str/includes? t "mp4") (str/includes? t "m4a") (str/includes? t "aac")) "clip.m4a"
      (str/includes? t "ogg") "clip.ogg"
      (str/includes? t "wav") "clip.wav"
      :else "clip")))

(defn heard
  "{:text :raw} from the service's JSON answer, each trimmed (\"\" when there is none); raw is the
  text when the service sent none (a service without cleanup)."
  [^js answer]
  (let [text (str/trim (str (some-> answer .-text)))
        raw (some-> answer .-raw)]
    {:text text :raw (if (string? raw) (str/trim raw) text)}))

(def ^:private max-context-chars
  "The most of a page's context passed on (the page sends at most 2000; the service keeps 200 words)."
  4000)

(defn context-of
  "The page's context for a clip (x-listen-context, URI-encoded: the chat's last words), decoded,
  trimmed and cut to max-context-chars; \"\" when there is none or it does not decode."
  [header]
  (let [text (try (str/trim (js/decodeURIComponent (str header))) (catch :default _ ""))]
    (subs text 0 (min max-context-chars (count text)))))

(defn- transcribe [{:keys [url token]} ^js clip content-type speaker context]
  (let [form (js/FormData.)]
    (.set form "file" (js/Blob. #js [clip] #js {:type (or content-type "application/octet-stream")})
          (clip-name content-type))
    (.set form "speaker" speaker)
    (when (seq context) (.set form "context" context))
    (-> (js/fetch (str url "/v1/audio/transcriptions")
                  #js {:method "POST" :headers #js {:authorization (str "Bearer " token)} :body form
                       :signal (js/AbortSignal.timeout 90000)})
        (.then (fn [^js r]
                 (when-not (.-ok r) (throw (js/Error. (str "listen answered " (.-status r)))))
                 (.json r)))
        (.then heard))))

(defn- hear [^js req ^js res email c ^js clip]
  (cond
    (not c) (io/send-json res 501 {:error "no listen service on this hub (HUB_LISTEN_URL, HUB_LISTEN_TOKEN_FILE)"})
    (zero? (.-length clip)) (io/send-json res 400 {:error "an empty clip"})
    :else (-> (transcribe c clip (.. req -headers -content-type) email
                          (context-of (aget (.-headers req) "x-listen-context")))
              (.then #(io/send-json res 200 %))
              (.catch (fn [e] (io/send-json res 502 {:error (.-message e)}))))))

(def ^:private log-keys [:event :ms :warm :steps :bytes :chars :error :ua :app :angle :edge :turns])

(defn log-line
  "One line for the hub's log from a page's mic report (POST /hub/listen/log): the caller and the
  known fields only, each cut short. No sound and no words ever come in it."
  [email body]
  (let [cut (fn [v] (cond (string? v) (subs v 0 (min 200 (count v)))
                          (map? v) (into {} (for [[k x] (take 6 v) :when (number? x)] [k x]))
                          (or (number? v) (boolean? v)) v
                          :else nil))
        ;; The microphone iOS records from: its own names, short text only.
        mic (into {} (for [k [:port :name :source :location :orientation]
                           :let [x (get-in body [:mic k])] :when (string? x)]
                       [k (subs x 0 (min 60 (count x)))]))
        fields (cond-> (into {} (for [k log-keys :let [v (cut (get body k))] :when (some? v)] [k v]))
                 (seq mic) (assoc :mic mic))]
    (str "mic: " email " " (js/JSON.stringify (clj->js fields)))))

(defn log
  "POST /hub/listen/log: a page's mic report, written to the hub's log."
  [^js req ^js res email]
  (if (not= "POST" (.-method req))
    (io/send-json res 405 {:error "POST only"})
    (-> (io/read-json req 8192)
        (.then (fn [body] (js/console.log (log-line email body)) (io/send-json res 200 {:ok true})))
        (.catch (fn [e] (io/send-json res 400 {:error (.-message e)}))))))

(defn call
  "GET or POST /hub/listen for a signed-in caller (email: lower case, from the sign-in)."
  [^js req ^js res email]
  (case (.-method req)
    "GET" (.then (config) #(io/send-json res 200 {:listen (boolean %)}))
    "POST" (-> (js/Promise.all #js [(config) (io/read-bytes req max-bytes)])
               (.then (fn [[c clip]] (hear req res email c clip)))
               (.catch (fn [e] (when-not (.-headersSent res) (io/send-json res 400 {:error (.-message e)})))))
    (do (.setHeader res "allow" "GET, POST")
        (io/send-json res 405 {:error "GET or POST"}))))
