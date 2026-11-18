(ns claudia.hub.pictures
  "People's profile pictures (the page's profile circles, README Servers and access): an admin sets
  one per person (PUT /hub/admin/picture?email=<email>, the PNG or WebP as the body); every
  signed-in person reads them by a key made from the email (GET /hub/picture/<key>), so the email
  is not in the address."
  (:require ["node:crypto" :as crypto]
            [clojure.string :as str]
            [claudia.hub.store :as store]
            [claudia.node.http :as io]))

(def ^:private max-bytes "The largest picture." (* 512 1024))

(defn image-type
  "The picture's type from its first bytes (a Buffer): image/png, image/webp, or nil."
  [^js b]
  (cond (and (>= (.-length b) 8) (= "89504e470d0a1a0a" (.toString (.subarray b 0 8) "hex"))) "image/png"
        (and (>= (.-length b) 12) (= "RIFF" (.toString (.subarray b 0 4) "latin1"))
             (= "WEBP" (.toString (.subarray b 8 12) "latin1"))) "image/webp"
        :else nil))

(defn picture-key
  "The address key of a person's picture: 16 hex digits of the SHA-256 of their email."
  [email]
  (subs (.digest (.update (.createHash crypto "sha256") (str email)) "hex") 0 16))

(defn url-of
  "Where a person's picture is read, with its time so a new one is fetched; nil without one."
  [email at]
  (when at (str "/hub/picture/" (picture-key email) "?v=" at)))

(defn people
  "Every person with their name and picture address: [{:email :name :picture}]."
  []
  (let [times (store/picture-times)]
    (mapv (fn [{:keys [email] nm :name}] {:email email :name nm :picture (url-of email (times email))})
          (store/people))))

(defn serve
  "GET /hub/picture/<key>: the picture, or 404."
  [^js res pathname]
  (let [k (second (re-matches #"^/hub/picture/([0-9a-f]{16})$" pathname))
        email (when k (some #(when (= k (picture-key (:email %))) (:email %)) (store/people)))
        {kind :type data :data} (when email (store/picture email))]
    (if-not data
      (io/send-json res 404 {:error "no picture"})
      (do (.writeHead res 200 #js {"content-type" kind "cache-control" "private, max-age=86400"
                                   "x-content-type-options" "nosniff"})
          (.end res (js/Buffer.from data))))))

(defn put!
  "PUT /hub/admin/picture?email=<email>: the body is the person's new picture."
  [^js req ^js res email]
  (let [email (str/lower-case (str/trim (str email)))]
    (if-not (store/person email)
      (io/send-json res 400 {:error "No such person."})
      (-> (io/read-bytes req max-bytes)
          (.then (fn [b]
                   (if-let [t (image-type b)]
                     (do (store/save-picture! email t b (js/Date.now))
                         (io/send-json res 200 {:picture (url-of email (js/Date.now))}))
                     (io/send-json res 400 {:error "A PNG or WebP picture only."}))))))))
