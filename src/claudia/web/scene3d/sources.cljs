(ns claudia.web.scene3d.sources
  "What a scene's real code needs before it is built: folders maps read their folder's files (with line
  counts) and become plain parts; code slabs get their text (inline, or a file of the scene's root
  read at its ref), Shiki's colours and a size that fits their lines; texts get theirs. All reads go
  through the chat's server (/api/chats/:id/source), which only reads inside a root this chat drew."
  (:require [claudia.web.api :as api]
            [claudia.web.scene3d.folders :as folders]
            [claudia.web.scene3d.code :as code]
            [claudia.web.scene3d.codepaint :as codepaint]
            [claudia.web.shiki :as shiki]))

(defn- render-url
  "The screenshot page's own read (claudia.slave.render serves it for the picture it shoots)."
  [query]
  (str "/api/source?" (.toString (js/URLSearchParams. (clj->js (into {} (remove (comp nil? val)) query))))))

(defn reader
  "The reads for a scene drawn in chat on server; on the screenshot page its own; nil outside a
  chat (the gallery)."
  [server chat]
  (let [url (cond (.-claudiaRender js/window) render-url
                  (and server chat) #(api/url server (str "chats/" (js/encodeURIComponent chat) "/source") %))]
    (when url
      {:tree (fn [root ref p] (api/fetch-json (url {:root root :ref ref :path p})))
       :text (fn [root ref p] (api/fetch-json (url {:root root :ref ref :path p :read 1})))})))

(defn- failed [what e] {:error (str what ": " (or (.-message e) e))})

(defn- folders-parts
  "Promise of the parts a folders part stands for (or [] and an error)."
  [rd {:keys [root ref]} p]
  (if-not (and rd root)
    (js/Promise.resolve {:parts [] :error "a folders map needs the scene's root (and a chat)"})
    (-> ((:tree rd) root ref (str (or (:path p) "")))
        (.then (fn [{:keys [files error]}] (if error {:parts [] :error error} {:parts (folders/parts p files)})))
        (.catch #(assoc (failed "folders" %) :parts [])))))

(defn- expand-folders
  "Promise of {:parts (every folders part replaced by its parts) :errors}."
  [rd d parts]
  (-> (js/Promise.all (into-array (map #(if (= "folders" (:shape %))
                                          (folders-parts rd d %)
                                          (js/Promise.resolve {:parts [%]}))
                                       parts)))
      (.then (fn [answers] {:parts (vec (mapcat :parts answers)) :errors (keep :error answers)}))))

(defn code-file
  "The file a code part shows: its file, else the file of the folders tile it floats over."
  [p by-id]
  (or (:file p) (:file (get by-id (:over p)))))

(defn- code-text [rd {:keys [root ref]} p file]
  (cond
    (string? (:code p)) (js/Promise.resolve {:text (:code p)})
    (not (and rd root file)) (js/Promise.resolve {:error "a code slab needs code, or a file and the scene's root"})
    :else (-> ((:text rd) root ref file) (.catch #(failed file %)))))

(defn- code-input
  "Promise of what painting a code part takes: {:rows :tokens :start :title :base} (or an error)."
  [rd d p by-id]
  (let [file (code-file p by-id)]
    (-> (code-text rd d p file)
        (.then (fn [{:keys [text error]}]
                 (if error
                   {:error error :rows [[1 error]] :start 1 :title (str file)}
                   (let [start (if (pos? (js/Number (:start p))) (js/Number (:start p)) 1)]
                     (-> (shiki/tokens text (code/lang-of (:lang p) file))
                         (.catch (fn [_] nil))
                         (.then (fn [toks]
                                  {:rows (code/rows (code/lines-of text) start (code/spans (:range p)))
                                   :tokens toks :start start :title (code/title-of (assoc p :file file))}))))))))))

(defn paint-code
  "A code part's canvas with these marks (a set of line numbers)."
  [host input marks]
  (codepaint/paint host (assoc input :marks marks)))

(defn- sized
  "A part with its size made a box that fits its canvas: width as given (or 6), height by the face."
  [p ^js canvas depth]
  (let [w (let [s (:size p)] (cond (number? s) s (sequential? s) (js/Number (first s)) :else 6))
        w (if (pos? w) w 6)]
    (assoc p :size [w (* w (/ (.-height canvas) (.-width canvas))) depth])))

(defn- prepare-code
  "Promise of [part (sized) {:input :canvas}] for a code part."
  [host rd d by-id p]
  (.then (code-input rd d p by-id)
         (fn [input]
           (let [canvas (paint-code host input (code/marked (:mark p)))]
             [(sized p canvas 0.12) {:input input :canvas canvas :mark (:mark p) :error (:error input)}]))))

(defn- text-size [p ^js canvas]
  (let [h (let [s (js/Number (:size p))] (if (pos? s) s 0.5))]
    (assoc p :size [(* h (/ (.-width canvas) (.-height canvas))) h 1])))

(defn prepare
  "Promise of {:parts :faces {id {:canvas :input :mark}} :errors} for the data's parts. text-canvas:
  the engine's painter of a text part (host, part -> canvas)."
  [host rd d parts text-canvas]
  (-> (expand-folders rd d parts)
      (.then (fn [{:keys [parts errors]}]
               (let [by-id (into {} (map (juxt #(str (:id %)) identity)) parts)]
                 (-> (js/Promise.all
                      (into-array (map (fn [p]
                                         (case (:shape p)
                                           "code" (prepare-code host rd d by-id p)
                                           "text" (let [c (text-canvas host p)] (js/Promise.resolve [(text-size p c) {:canvas c}]))
                                           (js/Promise.resolve [p nil])))
                                       parts)))
                     (.then (fn [pairs]
                              {:parts (mapv first pairs)
                               :faces (into {} (keep (fn [[p f]] (when f [(str (:id p)) f]))) pairs)
                               :errors (into (vec errors) (keep (comp :error second)) pairs)}))))))))
