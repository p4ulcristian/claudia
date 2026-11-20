(ns claudia.web.download
  "Saving a file: the picture's snapshot, and a photo or video from the viewer (README decision 81).
  On an iPhone or iPad (Safari, the home-screen web app and the iPhone app alike) a download goes
  nowhere the person can find (a web-app window shows no downloads of its own, the app's web view
  takes none), so there the file goes to the share sheet (navigator.share with the file), which
  offers Save Image or Save Video (Photos) and Save to Files; elsewhere it downloads. Names come
  from save-name (and claudia.shared.text/file-name for titles)."
  (:require [clojure.string :as str]))

(def ^:private type-exts
  {"image/jpeg" "jpg" "image/png" "png" "image/gif" "gif" "image/webp" "webp" "image/avif" "avif"
   "image/svg+xml" "svg" "image/bmp" "bmp" "video/mp4" "mp4" "video/webm" "webm" "video/quicktime" "mov"})

(defn ext-of-type
  "The file extension for a content type (parameters ignored), or nil for one not known here."
  [mime]
  (get type-exts (str/trim (first (str/split (str/lower-case (str mime)) #";")))))

(defn save-name
  "A file name to save as: nm's last part (a path's file), its extension the one mime calls for
  (nm's own when mime names none); fallback when nm has no name before its extension."
  [nm mime fallback]
  (let [base (str/replace (str nm) #"^.*[/\\]" "")
        own (second (re-find #"\.([A-Za-z0-9]{1,5})$" base))
        stem (str/replace base #"\.[A-Za-z0-9]{1,5}$" "")
        ext (or (ext-of-type mime) own)]
    (str (if (str/blank? stem) fallback stem) (when ext (str "." ext)))))

(defn apple-touch?
  "Whether the browser is an iPhone's or iPad's (WebKit there; an iPad says Macintosh but has touch)."
  [user-agent platform touch-points]
  (boolean (or (re-find #"iPhone|iPad|iPod" (str user-agent))
               (and (= "MacIntel" platform) (> (or touch-points 0) 1)))))

(defn shares?
  "Whether saving goes through the share sheet here (apple-touch? with the Web Share API)."
  []
  (and (apple-touch? (.-userAgent js/navigator) (.-platform js/navigator) (.-maxTouchPoints js/navigator))
       (fn? (.-canShare js/navigator))))

(defn save-blob!
  "Downloads blob as a file called file (the link's address is freed 10 s later)."
  [blob file]
  (let [href (js/URL.createObjectURL blob)
        a (js/document.createElement "a")]
    (set! (.-href a) href)
    (set! (.-download a) file)
    (.click a)
    (js/setTimeout #(js/URL.revokeObjectURL href) 10000)))

(defn hand-over!
  "Promise of :shared (the share sheet took the File and the person picked what to do) or
  :downloaded. The share sheet needs a tap just before: call it from the tap, with the file in hand.
  Rejects with the share's error (AbortError: closed without picking; NotAllowedError: the tap was
  too long ago)."
  [^js file]
  (if (and (shares?) (.canShare js/navigator #js {:files #js [file]}))
    (.then (.share js/navigator #js {:files #js [file]}) (constantly :shared))
    (do (save-blob! file (.-name file))
        (js/Promise.resolve :downloaded))))
