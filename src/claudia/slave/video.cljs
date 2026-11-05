(ns claudia.slave.video
  "A 3D scene's video, made in the background (README: the 3D scene). The hub asks for it with the
  scene's data as the page showed it (POST /api/chats/:id/videos {block title data voice}), and
  when it is to have the narrator brings the clips (PUT /api/chats/:id/videos/:vid/voice {clips
  [{text instruction pcm}] error?}, pcm: base64 of 16-bit mono PCM at 24 kHz). One video at a time:
  the render page in a headless browser of its own plays the story on a stepped clock
  (claudia.web.scene3d.maker) and hands its frames over; ffmpeg makes them an H.264 MP4, then lays
  the voice in as AAC. The file is kept with the chat (<data>/videos/<chat>/<vid>.mp4, beside it
  <vid>.json). The page asks GET /api/chats/:id/videos?block=&fp= and downloads
  GET /api/chats/:id/videos/<vid>.mp4.

  A video being made lives in `jobs` (lost when the runner restarts); a finished one only on disk."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:fs/promises" :as fsp]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.node.audio :as audio]
            [claudia.node.http :as io]
            [claudia.shared.protocol :as p]
            [claudia.slave.render :as render]
            [claudia.slave.store :as store]))

(def ^:private rate 24000)
(def ^:private batch-frames 15)
(def ^:private viewport #js {:width 1280 :height 720})
(def ^:private scale 1.5)                         ; 1280 x 720 CSS pixels -> 1920 x 1080
(def ^:private max-request (* 32 1024 1024))      ; the scene's data
(def ^:private max-voice (* 256 1024 1024))       ; the clips, base64
(def ^:private load-ms 60000)

(defonce ^:private root (atom nil))
(defonce ^:private jobs (atom {}))                ; video id -> job
(defonce ^:private line (atom (js/Promise.resolve nil)))
(defonce ^:private version (atom 0))              ; moves on every change the page can see
(defonce ^:private boot (str (js/Date.now)))

(defn set-root! [data-dir] (reset! root (path/join data-dir "videos")))

(defn- chat-dir [chat] (path/join @root (str/replace (str chat) #"[^A-Za-z0-9_-]" "_")))

(defn- change! [f & args] (apply swap! jobs f args) (swap! version inc))

;; ---- pure parts ----

(defn frames-args
  "ffmpeg's arguments for JPEG frames on stdin at fps -> an H.264 MP4 at out."
  [fps out]
  ["-y" "-loglevel" "error" "-f" "image2pipe" "-framerate" (str fps) "-c:v" "mjpeg" "-i" "pipe:0"
   "-c:v" "libx264" "-preset" "medium" "-crf" "20" "-pix_fmt" "yuv420p" "-movflags" "+faststart" out])

(defn mux-args
  "ffmpeg's arguments for the video at v and the voice at wav -> out, the picture as it is and the
  voice as AAC."
  [v wav out]
  ["-y" "-loglevel" "error" "-i" v "-i" wav "-map" "0:v" "-map" "1:a" "-c:v" "copy"
   "-c:a" "aac" "-b:a" "160k" "-ar" "48000" "-movflags" "+faststart" out])

(defn placed
  "Where each said line's clip goes on the track: said [{:text :instruction :at}] (seconds), clips
  {[instruction text] Int16Array}. -> [[at samples] …], lines without a clip left out."
  [said clips]
  (vec (for [{:keys [text instruction at]} said
             :let [c (get clips [instruction text])]
             :when c]
         [at c])))

(defn listing
  "A scene's videos for the page, newest first: the finished ones (metas from disk) and the ones
  being made or failed (jobs), of block."
  [metas job-list block]
  (->> (concat (map #(assoc % :state "done") metas)
               (map #(select-keys % [:id :block :title :state :progress :error :seconds :at]) job-list))
       (filter #(= block (:block %)))
       (sort-by :at >)
       vec))

(defn progress
  "How far rendering is (0 to 0.99): frames of fps made out of seconds expected."
  [frames fps seconds]
  (min 0.99 (/ frames (max 1 (* fps seconds)))))

(defn clip-lengths
  "The clips' lengths for the page: [{:text :instruction :seconds}]."
  [clips]
  (vec (for [[[instruction text] ^js c] clips] {:text text :instruction instruction :seconds (/ (.-length c) rate)})))

;; ---- ffmpeg ----

(defn- ffmpeg-bin [] (or (not-empty (.. js/process -env -CLAUDIA_FFMPEG)) "ffmpeg"))

(defn- ffmpeg
  "Starts ffmpeg with args. -> {:proc :done (promise, rejected with what went wrong)}."
  [args]
  (let [^js proc (cp/spawn (ffmpeg-bin) (clj->js args) #js {:stdio #js ["pipe" "ignore" "pipe"]})
        err (atom "")]
    (.on (.-stderr proc) "data" #(swap! err (fn [e] (subs (str e %) (max 0 (- (count (str e %)) 2000))))))
    {:proc proc
     :done (js/Promise.
            (fn [ok fail]
              (.on proc "error" #(fail (js/Error. (if (= "ENOENT" (.-code %))
                                                    "this server has no ffmpeg (install it, or set CLAUDIA_FFMPEG)"
                                                    (.-message %)))))
              (.on proc "close" #(if (zero? %) (ok nil) (fail (js/Error. (str "ffmpeg: " (str/trim @err))))))))}))

(defn- feed!
  "Writes buf to ffmpeg's stdin. -> a promise that waits while its pipe is full."
  [^js proc ^js buf]
  (if (.write (.-stdin proc) buf)
    (js/Promise.resolve nil)
    (js/Promise. (fn [ok] (.once (.-stdin proc) "drain" ok)))))

;; ---- making one ----

;; Sent to the page as source: plain functions, not compiled ClojureScript.
(def ^:private open-fn (js/Function. "a" "return window.__video(a[0], a[1], a[2]);"))

(defn- open-page!
  "The render page in browser b, showing the scene ready for its video. -> Promise of the tab and
  what begin answered ({:seconds :fps})."
  [^js b {:keys [data color clips]}]
  (-> (js/Promise.all #js [(.newPage b #js {:viewport viewport :deviceScaleFactor scale}) (render/base-url)])
      (.then (fn [[^js tab {:keys [url]}]]
               (.setDefaultTimeout tab load-ms)
               (-> (.goto tab url #js {:waitUntil "load"})
                   (.then #(.waitForFunction tab "typeof window.__video === 'function'"))
                   (.then #(.evaluate tab open-fn #js [(p/write-json data) (p/write-json (clip-lengths clips)) color]))
                   ;; Its people's model and code slabs are loaded, its fonts there.
                   (.then #(.waitForFunction tab "!(window.__scene3dLoading > 0)"))
                   (.then #(.evaluate tab "document.fonts.ready.then(() => true)"))
                   (.then #(.evaluate tab "window.__maker.begin()"))
                   (.then (fn [info] [tab (js->clj info :keywordize-keys true)])))))))

(defn- frames!
  "Hands the page's frames to ffmpeg, batch by batch, until the story has ended. -> Promise of how
  many frames there are."
  [^js tab proc id {:keys [fps seconds]} made]
  (-> (.evaluate tab (str "window.__maker.frames(" batch-frames ")"))
      (.then (fn [^js r]
               (let [pics (array-seq (.-frames r))
                     n (+ made (count pics))]
                 (-> (reduce (fn [chain b64] (.then chain #(feed! proc (js/Buffer.from b64 "base64"))))
                             (js/Promise.resolve nil) pics)
                     (.then (fn [_]
                              (change! assoc-in [id :progress] (progress n fps seconds))
                              (if (.-done r) n (frames! tab proc id {:fps fps :seconds seconds} n))))))))))

(defn- picture!
  "The picture: the page plays the story, ffmpeg makes the frames into the video at out.
  -> Promise of {:frames :fps :said}."
  [id job out]
  (let [b (atom nil)]
    (-> (render/drawing-browser)
        (.then (fn [[browser gl]]
                 (reset! b browser)
                 (js/console.log (str "video " id ": drawn with " gl))
                 (open-page! browser job)))
        (.then (fn [[^js tab info]]
                 (change! assoc-in [id :state] "render")
                 (let [{:keys [proc done]} (ffmpeg (frames-args (:fps info) out))]
                   (-> (frames! tab proc id info 0)
                       (.then (fn [n]
                                (.end (.-stdin ^js proc))
                                (js/Promise.all #js [done (.evaluate tab "window.__maker.said()")
                                                     (js/Promise.resolve n)])))
                       (.then (fn [[_ said n]]
                                {:frames n :fps (:fps info) :said (js->clj said :keywordize-keys true)}))))))
        (.finally #(some-> ^js @b .close (.catch (fn [_])))))))

(defn- voice!
  "The voice: the said lines' clips laid on a track as long as the video, made AAC beside the
  picture at v -> out (no line said: the picture alone). -> Promise of nil."
  [{:keys [frames fps said]} clips v out]
  (let [lines (placed said clips)]
    (if (empty? lines)
      (fsp/rename v out)
      (let [wav-file (str v ".wav")]
        (-> (fsp/writeFile wav-file (audio/wav (audio/lay lines (js/Math.round (* (/ frames fps) rate)) rate) rate))
            (.then #(:done (ffmpeg (mux-args v wav-file out))))
            (.finally #(js/Promise.all #js [(.catch (fsp/rm v #js {:force true}) (fn [_]))
                                           (.catch (fsp/rm wav-file #js {:force true}) (fn [_]))])))))))

(defn- finished!
  "The video is on disk: its meta beside it, the job gone from the ones being made."
  [id {:keys [chat block title at]} {:keys [frames fps]} out]
  (let [about {:id id :block block :title title :at at :seconds (/ frames fps) :size (.-size (fs/statSync out))}]
    (-> (fsp/writeFile (path/join (chat-dir chat) (str id ".json")) (p/write-json about))
        (.then #(change! dissoc id)))))

(defn- make!
  "Makes video id from its job: the picture, then the voice; on failure the job says why."
  [id]
  (let [{:keys [chat data clips] :as job} (get @jobs id)
        dir (chat-dir chat)
        out (path/join dir (str id ".mp4"))
        v (path/join dir (str id ".picture.mp4"))]
    (render/hold! id (set (keep #(when (string? %) %) [(:root data)])))
    (-> (fsp/mkdir dir #js {:recursive true})
        (.then #(picture! id job v))
        (.then (fn [made]
                 (change! assoc-in [id :state] "encode")
                 (.then (voice! made clips v out) #(finished! id job made out))))
        (.catch (fn [e]
                  (js/console.error (str "video " id ": " (.-message e)))
                  (.catch (fsp/rm v #js {:force true}) (fn [_]))
                  (change! update id merge {:state "failed" :error (.-message e) :clips nil :data nil})))
        (.finally #(render/release! id)))))

(defn- queue!
  "Video id waits for the ones before it, then is made (one at a time)."
  [id clips]
  (change! update id assoc :state "queued" :clips clips)
  (let [run (.then @line #(make! id))]
    (reset! line (.catch run (fn [_] nil)))
    run))

;; ---- HTTP ----

(defn route
  "Which video call a request is: {:op :chat :vid} (op :create, :voice, :list or :file), or nil."
  [method url]
  (let [[_ chat rest-path] (re-matches #"/api/chats/([^/]+)/videos(/.*)?" url)
        chat (some-> chat js/decodeURIComponent)
        [_ vid] (some->> rest-path (re-matches #"/([A-Za-z0-9-]+)/voice"))
        [_ file] (some->> rest-path (re-matches #"/([A-Za-z0-9-]+)\.mp4"))]
    (cond
      (not chat) nil
      (and (nil? rest-path) (= "POST" method)) {:op :create :chat chat}
      (and (nil? rest-path) (= "GET" method)) {:op :list :chat chat}
      (and vid (= "PUT" method)) {:op :voice :chat chat :vid vid}
      (and file (= "GET" method)) {:op :file :chat chat :vid file})))

(defn- new-id [] (str (js/Date.now) "-" (subs (.toString (js/Math.random) 36) 2 8)))

(defn- create! [^js req ^js res chat]
  (-> (io/read-json req max-request)
      (.then (fn [{:keys [block title data voice]}]
               (if-not (and (string? block) (map? data))
                 (io/send-json res 400 {:error "block and data"})
                 (let [id (new-id)]
                   (change! assoc id {:id id :chat chat :block block :title (str title) :data data
                                      :color (:color (store/chat chat)) :state "voice" :at (js/Date.now)})
                   (when-not voice (queue! id {}))
                   (io/send-json res 202 {:id id})))))
      (.catch (fn [e] (io/send-json res 400 {:error (.-message e)})))))

(defn- clips-of
  "The hub's clips as {[instruction text] Int16Array}."
  [clips]
  (into {} (for [{:keys [text instruction pcm]} clips :when (and (string? text) (string? pcm))]
             [[instruction text] (audio/samples (js/Buffer.from pcm "base64"))])))

(defn- voiced! [^js req ^js res chat vid]
  (-> (io/read-json req max-voice)
      (.then (fn [{:keys [clips error]}]
               (if-not (= ["voice" chat] ((juxt :state :chat) (get @jobs vid)))
                 (io/send-json res 409 {:error "no video waiting for its voice"})
                 (do (when (seq error) (js/console.error (str "video " vid ": no voice: " error)))
                     (queue! vid (clips-of clips))
                     (io/send-json res 200 {:ok true})))))
      (.catch (fn [e] (io/send-json res 400 {:error (.-message e)})))))

(defn- metas
  "The finished videos of a chat, from disk."
  [chat]
  (-> (fsp/readdir (chat-dir chat))
      (.catch (fn [_] #js []))
      (.then (fn [names]
               (js/Promise.all
                (clj->js (for [n (array-seq names) :when (str/ends-with? n ".json")]
                           (-> (fsp/readFile (path/join (chat-dir chat) n) "utf8")
                               (.then p/read-json)
                               (.catch (fn [_] nil))))))))
      (.then #(keep identity (array-seq %)))))

(defn- list-reply [^js req ^js res chat]
  (let [q (.-searchParams (js/URL. (.-url req) "http://runner"))
        fp (str boot "-" @version)]
    (if (= fp (.get q "fp"))
      (io/send-json res 200 {:same true})
      (.then (metas chat)
             (fn [ms]
               (io/send-json res 200 {:fp fp :videos (listing ms (filter #(= chat (:chat %)) (vals @jobs)) (.get q "block"))}))))))

(defn- file-reply [^js res chat vid]
  (let [f (path/join (chat-dir chat) (str vid ".mp4"))]
    (if-not (fs/existsSync f)
      (io/send-json res 404 {:error "No such video."})
      (do (.writeHead res 200 #js {"content-type" "video/mp4" "content-length" (.-size (fs/statSync f))
                                   "content-disposition" (str "attachment; filename=\"" vid ".mp4\"")})
          (.pipe (fs/createReadStream f) res)))))

(defn handle
  "The video calls; true when this was one. who: the caller (a read-only view only lists and
  downloads)."
  [^js req ^js res url who]
  (when-let [{:keys [op chat vid]} (route (.-method req) url)]
    (if (and (:read-only who) (#{:create :voice} op))
      (io/send-json res 403 {:error "Read only."})
      (case op
        :create (create! req res chat)
        :voice (voiced! req res chat vid)
        :list (list-reply req res chat)
        :file (file-reply res chat vid)))
    true))
