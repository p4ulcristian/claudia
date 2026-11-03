(ns claudia.slave.front
  "This server's front: believes the hub's X-Claudia-Email (only the hub can reach it: firewall), picks
  the caller's profile, and passes HTTP and WebSocket on to that profile's runner socket. No page."
  (:require ["node:http" :as http]
            ["node:net" :as net]
            ["node:fs" :as fs]
            [clojure.string :as str]
            [claudia.slave.http :as h]))

(def ^:private env (.-env js/process))

(defn- read-profiles
  "The profiles file: `<profile> <email> <runner socket>` per line; # starts a comment."
  [file]
  (vec (for [line (str/split-lines (fs/readFileSync file "utf8"))
             :let [[profile email socket] (str/split (str/trim (str/replace line #"#.*" "")) #"\s+")]
             :when (and (seq profile) (seq email) (seq socket))]
         {:profile profile :email (str/lower-case email) :socket socket})))

(def ^:private profiles (atom []))

(defn- route
  "Whose runner the call goes to: {:own :target :read-only}, or {:status :error}."
  [^js headers]
  (let [email (some-> (aget headers "x-claudia-email") str/trim str/lower-case)
        view (some-> (aget headers "x-claudia-view") str/trim)
        own (some #(when (= (:email %) email) %) @profiles)
        target (when (seq view) (some #(when (= (:profile %) view) %) @profiles))]
    (cond (not (seq email)) {:status 401 :error "No caller."}
          (not own) {:status 403 :error "No profile on this server for this person."}
          (and (seq view) (not target)) {:status 404 :error (str "No profile " view " on this server.")}
          :else {:own own
                 :target (or target own)
                 :read-only (boolean (and target (not= (:profile target) (:profile own))))})))

(defn- runner-headers
  "The request's headers for the runner: every X-Claudia-* the caller sent replaced by the front's own."
  [^js raw {:keys [target read-only]}]
  (let [pairs (->> (partition 2 raw)
                   (remove #(str/starts-with? (str/lower-case (first %)) "x-claudia-")))]
    (concat pairs
            [["X-Claudia-Profile" (:profile target)]]
            (when read-only [["X-Claudia-Read-Only" "1"]]))))

(defn- me [res {:keys [own target read-only]}]
  (h/json-reply res 200 {:server (.-FRONT_NAME env)
                         :profile (:profile own)
                         :profiles (mapv :profile @profiles)
                         :viewing (when read-only (:profile target))
                         :readOnly read-only
                         :version h/version
                         :commit h/commit}))

(defn- on-request [^js req ^js res]
  (let [{:keys [status error target read-only] :as r} (route (.-headers req))
        url (first (str/split (.-url req) #"\?"))]
    (cond
      status (h/json-reply res status {:error error})
      (and (= url "/api/me") (= "GET" (.-method req))) (me res r)
      (and read-only (not= "GET" (.-method req))) (h/json-reply res 403 {:error "Read only."})
      :else
      (let [headers (reduce (fn [o [k v]] (doto o (aset k v))) #js {} (runner-headers (.-rawHeaders req) r))
            up (http/request #js {:socketPath (:socket target) :method (.-method req) :path (.-url req) :headers headers}
                             (fn [^js up-res]
                               (.writeHead res (.-statusCode up-res) (.-rawHeaders up-res))
                               (.pipe up-res res)))]
        (.on up "error" (fn [_]
                          (if (.-headersSent res)
                            (.destroy res)
                            (h/json-reply res 502 {:error "The runner is not answering."}))))
        (.pipe req up)))))

(defn- on-upgrade
  "A WebSocket: the upgrade request is written to the runner socket as it came (with the front's
  headers), then bytes flow both ways untouched. Read-only frames are enforced by the runner."
  [^js req ^js sock ^js head]
  (let [{:keys [status error target] :as r} (route (.-headers req))]
    (if status
      (do (.end sock (str "HTTP/1.1 " status " " error "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")))
      (let [up (net/connect (:socket target))]
        (.on up "connect"
             (fn []
               (.write up (str (.-method req) " " (.-url req) " HTTP/1.1\r\n"
                               (apply str (for [[k v] (runner-headers (.-rawHeaders req) r)] (str k ": " v "\r\n")))
                               "\r\n"))
               (when (pos? (.-length head)) (.write up head))
               (.pipe up sock)
               (.pipe sock up)))
        (.on up "error" (fn [_] (.destroy sock)))
        (.on sock "error" (fn [_] (.destroy up)))))))

(defn main [& _]
  (let [file (.-FRONT_PROFILES env)]
    (when-not (seq file) (throw (js/Error. "FRONT_PROFILES is not set.")))
    (reset! profiles (read-profiles file))
    (let [server (doto (http/createServer on-request)
                   ;; Uploads take up to 100 MB: no 5-minute cap on a request.
                   (aset "requestTimeout" 0))
          port (js/parseInt (or (.-FRONT_PORT env) "4540") 10)
          host (or (.-FRONT_HOST env) "127.0.0.1")]
      (.on server "upgrade" on-upgrade)
      (.listen server port host
               #(println "claudia front" (.-FRONT_NAME env) "on" (str host ":" port)
                         "for" (count @profiles) "profiles")))))
