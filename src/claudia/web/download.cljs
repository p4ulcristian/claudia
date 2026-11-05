(ns claudia.web.download
  "Saving something the page made as a file (the picture's snapshot). Its name comes from
  claudia.shared.text/file-name.")

(defn save-blob!
  "Downloads blob as a file called file (the link's address is freed 10 s later)."
  [blob file]
  (let [href (js/URL.createObjectURL blob)
        a (js/document.createElement "a")]
    (set! (.-href a) href)
    (set! (.-download a) file)
    (.click a)
    (js/setTimeout #(js/URL.revokeObjectURL href) 10000)))
