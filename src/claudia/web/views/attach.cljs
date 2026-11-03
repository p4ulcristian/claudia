(ns claudia.web.views.attach
  "Attachments in the chat column: the composer's paperclip and chips (with upload progress), the
  drop zone over the whole window, pasting files, and in the messages the file chips (download) and
  the images a message sent or a show_image step showed."
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.shared.files :as files]
            [claudia.web.chatops :as ops]
            [claudia.web.views.pure.attach :as pure]))

(defn- clip-icon []
  [:svg.ic {:viewBox "0 0 24 24" :aria-hidden true}
   [:path {:d "M20 11.5l-7.8 7.8a5 5 0 0 1-7.1-7.1l8.5-8.5a3.3 3.3 0 0 1 4.7 4.7l-8.5 8.5a1.7 1.7 0 0 1-2.4-2.4l7.8-7.8"}]])

(defn add-files!
  "Files (a FileList or array) into the open composer."
  [draft-key file-list]
  (let [fs (vec (array-seq (js/Array.from file-list)))]
    (when (seq fs) (rf/dispatch [:ops/attach draft-key fs]))))

(defn paperclip [draft-key]
  (let [input (h/use-ref nil)]
    [:<>
     [:input {:type "file" :multiple true :style {:display "none"}
              :ref #(set! (.-current input) %)
              :on-change (fn [e]
                           (add-files! draft-key (.. e -target -files))
                           (set! (.. e -target -value) ""))}]
     [:button.icon.clip {:title "Attach files" :on-click #(some-> (.-current input) .click)} [clip-icon]]]))

(defn on-paste
  "Pasted files go in as attachments (text pastes as usual)."
  [draft-key ^js e]
  (let [fs (some-> e .-clipboardData .-files)]
    (when (and fs (pos? (.-length fs)))
      (.preventDefault e)
      (add-files! draft-key fs))))

(defn file-icon []
  [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M5 2.5h7l3.5 3.5v11.5h-10.5z M12 2.5v3.5h3.5"}]])

(defn- upload-chip [draft-key {:keys [id size busy progress error] file-name :name :as item}]
  (let [pct (js/Math.round (* 100 (or progress 0)))]
    [:span.file-chip {:class (cond error "failed" busy "sending")
                      :title (pure/upload-title item)}
     (when (and busy (not error)) [:span.file-chip-bar {:style {:width (str pct "%")}}])
     [file-icon]
     [:span.file-chip-name file-name]
     [:span.file-chip-size (or error (if busy (str pct "%") (files/size-label size)))]
     [:button {:type "button" :title "Remove" :aria-label (str "Remove " file-name)
               :on-click #(rf/dispatch [:ops/unattach draft-key id])} "×"]]))

(defn- thumbs [draft-key media reading?]
  [:div.thumbs
   (for [{:keys [id url video images]} media]
     ^{:key id}
     [:div.thumb
      [:img {:src url :alt ""}]
      (when video [:span.thumb-video (str "▶ " (js/Math.round video) " s · " (count images) " frames")])
      [:button {:title "Remove" :on-click #(rf/dispatch [:ops/unattach draft-key id])} "×"]])
   (when reading? [:div.thumb.reading {:aria-label "Reading the file…"}])])

(defn chips
  "What the composer holds: files as chips (sent to the chat's server at once, the share sent as a
  bar), photos and videos as thumbnails."
  [draft-key]
  (let [{:keys [media reading? uploads]} (pure/groups @(rf/subscribe [:attach draft-key]))]
    [:<>
     (when (seq uploads)
       [:div.file-chips
        (for [item uploads]
          ^{:key (:id item)} [upload-chip draft-key item])])
     (when (or (seq media) reading?)
       (thumbs draft-key media reading?))]))

(defn- files-dragged? [^js e]
  (some #(= "Files" %) (array-seq (or (some-> e .-dataTransfer .-types) #js []))))

(defn- dropped!
  "Files dropped: to the composer of draft-key (dk holds it); a folder is refused. A code editor
  block takes a dropped text file itself (the event comes prevented)."
  [^js e dk]
  (when-not (.-defaultPrevented e)
    (.preventDefault e)
    (let [items (array-seq (js/Array.from (.. e -dataTransfer -items)))
          folder? (some #(some-> ^js % .webkitGetAsEntry .-isDirectory) items)]
      (if folder?
        (rf/dispatch [:ops/error "A folder can't be attached: drop the files in it."])
        (add-files! (.-current dk) (.. e -dataTransfer -files))))))

(defn- drag-listeners
  "The window's drag listeners: the veil shows while files are dragged over the page."
  [dk set-over]
  (let [depth (atom 0)]
    {"dragenter" (fn [^js e] (when (files-dragged? e) (swap! depth inc) (set-over true)))
     "dragleave" (fn [^js e] (when (and (files-dragged? e) (<= (swap! depth dec) 0)) (reset! depth 0) (set-over false)))
     "dragover" (fn [^js e] (when (files-dragged? e) (.preventDefault e) (set! (.. e -dataTransfer -dropEffect) "copy")))
     "drop" (fn [^js e]
              (when (files-dragged? e)
                (reset! depth 0)
                (set-over false)
                (dropped! e dk)))}))

(defn drop-zone
  "Files dropped anywhere in the window go to the open composer; a folder is refused."
  [draft-key enabled?]
  (let [[over set-over] (h/use-state false)
        dk (h/use-ref draft-key)]
    (set! (.-current dk) draft-key)
    (h/use-effect
     (fn []
       (if-not enabled?
         js/undefined
         (let [ls (drag-listeners dk set-over)]
           (doseq [[t f] ls] (js/window.addEventListener t f))
           #(doseq [[t f] ls] (js/window.removeEventListener t f)))))
     [enabled?])
    (when over [:div.drop-veil {:aria-hidden true} [:span "Drop to attach"]])))

;; ---- in the messages ----

(defn img-big
  "A picture shown big over the page; a click (or Escape) closes it."
  [src close]
  (h/use-effect
   (fn []
     (let [k (fn [e] (when (= "Escape" (.-key e)) (close)))]
       (js/window.addEventListener "keydown" k)
       #(js/window.removeEventListener "keydown" k)))
   [])
  [:div.img-big {:on-click close} [:img {:src src :alt ""}]])

(defn file-chips
  "A sent message's files: name and size; a click downloads it from the chat's server."
  [server _chat sent]
  (let [view @(rf/subscribe [:view])]
    [:div.file-chips
     (for [{:keys [path size] file-name :name} sent
           :let [{:keys [folder saved]} (pure/upload-path path file-name)]]
       ^{:key path}
       [:a.file-chip {:href (ops/api-url server (str "chats/" folder "/uploads/" (js/encodeURIComponent saved)) view)
                      :download file-name :title (str "Saved for Claude at " path)}
        [file-icon] [:span.file-chip-name file-name] [:span.file-chip-size (files/size-label size)]])]))

(defn- retrying-img
  "An image the session file may not hold yet (it is written just after the message comes): tried
  again a few times."
  [_src _attrs]
  (let [tries (r/atom 0)]
    (fn [src attrs]
      [:img (merge {:src (str src (when (pos? @tries) (str (if (re-find #"\?" src) "&" "?") "t=" @tries))) :alt ""
                    :loading "lazy"
                    :on-error #(when (< @tries 6) (js/setTimeout (fn [] (swap! tries inc)) 1200))}
                   attrs)])))

(defn- sent-images-view [server chat msg-id n]
  (let [view @(rf/subscribe [:view])
        [big set-big] (h/use-state nil)
        src #(ops/api-url server (str "chats/" chat "/sent/" msg-id "/" %) view)]
    [:div.sent-media
     (for [i (range n)]
       ^{:key i}
       [:button.sent-thumb {:title "Photo" :on-click #(set-big (src i))} [retrying-img (src i) {}]])
     (when big [:f> img-big big #(set-big nil)])]))

(defn sent-images
  "The photos (and video frames) a message sent, small; a click shows one big."
  [server chat msg-id n]
  [:f> sent-images-view server chat msg-id n])

(defn- shown-image-view [server chat step-id]
  (let [view @(rf/subscribe [:view])
        [big set-big] (h/use-state nil)
        src (ops/api-url server (str "chats/" chat "/shown/" step-id) view)]
    [:<>
     [retrying-img src {:on-click #(set-big src)}]
     (when big [:f> img-big big #(set-big nil)])]))

(defn shown-image
  "A picture Claude showed in the chat (show_image); a click shows it big."
  [server chat step-id]
  [:f> shown-image-view server chat step-id])
