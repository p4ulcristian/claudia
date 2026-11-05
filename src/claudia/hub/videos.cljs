(ns claudia.hub.videos
  "A 3D scene's video, asked for from the page: POST /hub/videos {server chat block title data
  voice}. The hub hands it to that person's runner on that server (claudia.slave.video), answers
  at once (202 {id}), then, when the video is to have the narrator (voice asked for, a voice set up,
  lines to say), says the story's lines (claudia.hub.narrate) and brings the clips to the runner,
  which waits for them. The page can close as soon as it has asked."
  (:require [claudia.hub.config :as config]
            [claudia.hub.front :as front]
            [claudia.hub.narrate :as narrate]
            [claudia.node.http :as io]
            [claudia.shared.narration :as narration]))

(def ^:private max-body (* 32 1024 1024))
(def ^:private ask-ms 30000)
(def ^:private voice-ms (* 10 60000))

(defn runner-path
  "The runner's path for a chat's videos (more: what follows, like /<id>/voice)."
  [chat & more]
  (apply str "/api/chats/" (js/encodeURIComponent chat) "/videos" more))

(defn- bring-voice!
  "Says the lines and hands the clips to video id (on failure: none, and why)."
  [server email chat id lines]
  (-> (narrate/clips lines)
      (.then (fn [clips] {:clips (mapv #(update % :pcm (fn [^js b] (.toString b "base64"))) clips)}))
      (.catch (fn [e] {:clips [] :error (.-message e)}))
      (.then #(front/call-as server email "PUT" (runner-path chat "/" id "/voice") % voice-ms))
      (.then (fn [[status body]]
               (when-not (= 200 status)
                 (js/console.error (str "video " id ": the runner took no voice: " (:error body))))))))

(defn- ask!
  "Hands the video to the runner; answers the page; brings the voice when there is to be one."
  [^js res server email {:keys [chat block title data]} voiced]
  (-> (front/call-as server email "POST" (runner-path chat)
                     {:block block :title title :data data :voice voiced} ask-ms)
      (.then (fn [[status body]]
               (if (= 202 status)
                 (do (io/send-json res 202 {:id (:id body)})
                     (when voiced (bring-voice! server email chat (:id body) (narration/lines data))))
                 (io/send-json res (if (pos? status) status 502) {:error (or (:error body) "the server did not answer")}))))))

(defn call
  "POST /hub/videos for a signed-in person (email)."
  [^js req ^js res person email]
  (if (not= "POST" (.-method req))
    (do (.setHeader res "allow" "POST") (io/send-json res 405 {:error "POST only"}))
    (-> (io/read-json req max-body)
        (.then (fn [{:keys [server chat block data voice] :as body}]
                 (let [s (config/server-of person (str server))]
                   (cond
                     (not (and (string? chat) (string? block) (map? data))) (io/send-json res 400 {:error "chat, block and data"})
                     (not s) (io/send-json res 403 {:error (str "no access to " server)})
                     :else (.then (narrate/voice?)
                                  #(ask! res s email body (boolean (and voice % (seq (narration/lines data))))))))))
        (.catch (fn [e] (when-not (.-headersSent res) (io/send-json res 400 {:error (.-message e)})))))))
