(ns claudia.hub.listen
  "Speech to text for the composer's mic. The page records a clip as the browser makes it (WebM in
  Chrome, MP4 in Safari) and posts it as the body, its type as content-type; the hub passes it to
  the listen service (HUB_LISTEN_URL, its token in HUB_LISTEN_TOKEN_FILE or HUB_LISTEN_TOKEN):
  <url>/v1/audio/transcriptions, OpenAI's shape (a multipart `file`) -> {text}. No language is
  given, so the service writes down whatever language was spoken.

  GET /hub/listen -> {listen: bool}; POST /hub/listen <clip> -> {text}, or {error} (501 without a
  listen service)."
  (:require [claudia.node.env :as env]
            [claudia.node.http :as io]
            [clojure.string :as str]))

(def ^:private max-bytes (* 8 1024 1024))

(defn- config "A promise of {:url :token} or nil." [] (env/service "HUB_LISTEN"))

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

(defn heard-text
  "The text from the service's JSON answer, trimmed (\"\" when there is none)."
  [^js answer]
  (str/trim (str (some-> answer .-text))))

(defn- transcribe [{:keys [url token]} ^js clip content-type]
  (let [form (js/FormData.)]
    (.set form "file" (js/Blob. #js [clip] #js {:type (or content-type "application/octet-stream")})
          (clip-name content-type))
    (-> (js/fetch (str url "/v1/audio/transcriptions")
                  #js {:method "POST" :headers #js {:authorization (str "Bearer " token)} :body form
                       :signal (js/AbortSignal.timeout 90000)})
        (.then (fn [^js r]
                 (when-not (.-ok r) (throw (js/Error. (str "listen answered " (.-status r)))))
                 (.json r)))
        (.then heard-text))))

(defn- hear [^js req ^js res c ^js clip]
  (cond
    (not c) (io/send-json res 501 {:error "no listen service on this hub (HUB_LISTEN_URL, HUB_LISTEN_TOKEN_FILE)"})
    (zero? (.-length clip)) (io/send-json res 400 {:error "an empty clip"})
    :else (-> (transcribe c clip (.. req -headers -content-type))
              (.then #(io/send-json res 200 {:text %}))
              (.catch (fn [e] (io/send-json res 502 {:error (.-message e)}))))))

(defn call
  "GET or POST /hub/listen for a signed-in caller."
  [^js req ^js res]
  (case (.-method req)
    "GET" (.then (config) #(io/send-json res 200 {:listen (boolean %)}))
    "POST" (-> (js/Promise.all #js [(config) (io/read-bytes req max-bytes)])
               (.then (fn [[c clip]] (hear req res c clip)))
               (.catch (fn [e] (when-not (.-headersSent res) (io/send-json res 400 {:error (.-message e)})))))
    (do (.setHeader res "allow" "GET, POST")
        (io/send-json res 405 {:error "GET or POST"}))))
