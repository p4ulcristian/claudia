(ns claudia.web.download
  "Saving something the page made as a file: its name from a title, and the download itself. The
  picture's snapshot and the 3D scene's recording use it.")

(defn file-name
  "A title as a file name: letters and digits kept, every run of anything else one -, fallback when
  nothing is left; then .ext."
  [title fallback ext]
  (let [t (-> (str title)
              (.replace (js/RegExp. "[^\\p{L}\\p{N}]+" "gu") "-")
              (.replace (js/RegExp. "^-+|-+$" "g") ""))]
    (str (if (seq t) t fallback) "." ext)))

(defn save-blob!
  "Downloads blob as a file called file (the link's address is freed 10 s later)."
  [blob file]
  (let [href (js/URL.createObjectURL blob)
        a (js/document.createElement "a")]
    (set! (.-href a) href)
    (set! (.-download a) file)
    (.click a)
    (js/setTimeout #(js/URL.revokeObjectURL href) 10000)))
