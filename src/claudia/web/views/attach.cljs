(ns claudia.web.views.attach
  "Attachments in the chat column: the composer's paperclip and chips (with upload progress), the
  drop zone over the whole window, pasting files, and in the messages the file chips (download) and
  the images a message sent or a show_image step showed."
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.shared.files :as files]
            [claudia.web.chatops :as ops]))

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

(defn chips [draft-key]
  (let [items @(rf/subscribe [:attach draft-key])]
    (when (seq items)
      [:div.attach-row
       (for [{:keys [id name size url kind busy progress error video]} items]
         ^{:key id}
         [:div.attach-chip {:class [(when error "err") (when busy "busy")]}
          (if (and url (= kind "media"))
            [:img.attach-thumb {:src url :alt name}]
            [:span.attach-file-ic "📄"])
          [:span.attach-text
           [:span.attach-name name]
           [:span.attach-note
            (cond error error
                  busy (if progress (str (js/Math.round (* 100 progress)) "%") "reading…")
                  video (str "video · " (.toFixed video 1) " s")
                  :else (files/size-label size))]]
          (when (and busy progress) [:span.attach-bar {:style {:width (str (* 100 progress) "%")}}])
          [:button.attach-x {:title "Remove" :on-click #(rf/dispatch [:ops/unattach draft-key id])} "×"]])])))

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
         (let [files? (fn [^js e] (some #(= "Files" %) (array-seq (or (some-> e .-dataTransfer .-types) #js []))))
               over! (fn [^js e] (when (files? e) (.preventDefault e) (set-over true)))
               leave! (fn [^js e] (when (or (nil? (.-relatedTarget e)) (= (.-relatedTarget e) js/document.documentElement)) (set-over false)))
               drop! (fn [^js e]
                       (when (files? e)
                         (.preventDefault e)
                         (set-over false)
                         (let [items (array-seq (js/Array.from (.. e -dataTransfer -items)))
                               folder? (some #(some-> ^js % .webkitGetAsEntry .-isDirectory) items)]
                           (if folder?
                             (rf/dispatch [:ops/error "A folder can't be attached: drop the files in it."])
                             (add-files! (.-current dk) (.. e -dataTransfer -files))))))]
           (js/window.addEventListener "dragover" over!)
           (js/window.addEventListener "dragleave" leave!)
           (js/window.addEventListener "drop" drop!)
           #(do (js/window.removeEventListener "dragover" over!)
                (js/window.removeEventListener "dragleave" leave!)
                (js/window.removeEventListener "drop" drop!)))))
     [enabled?])
    (when over [:div.drop-zone [:div.drop-card "Drop to attach"]])))

;; ---- in the messages ----

(defn file-chips [server chat sent]
  (let [view @(rf/subscribe [:view])]
    [:div.file-chips
     (for [{:keys [path name size]} sent
           :let [folder (last (butlast (.split (str path) "/")))]]
       ^{:key path}
       [:a.file-chip {:href (ops/api-url server (str "chats/" folder "/uploads/" (js/encodeURIComponent name)) view)
                      :download name :title path}
        [:span.attach-file-ic "📄"] [:span.attach-name name] [:span.attach-note (files/size-label size)]])]))

(defn- retrying-img
  "An image the session file may not hold yet (it is written just after the message comes): tried
  again a few times."
  [src cls]
  (let [tries (r/atom 0)]
    (fn [src cls]
      [:img {:class cls :src (str src (when (pos? @tries) (str (if (re-find #"\?" src) "&" "?") "t=" @tries))) :alt ""
             :loading "lazy"
             :on-error #(when (< @tries 6) (js/setTimeout (fn [] (swap! tries inc)) 1200))
             :on-click #(js/window.open src "_blank")}])))

(defn sent-images [server chat msg-id n]
  (let [view @(rf/subscribe [:view])]
    [:div.sent-images
     (for [i (range n)]
       ^{:key i} [retrying-img (ops/api-url server (str "chats/" chat "/sent/" msg-id "/" i) view) "sent-image"])]))

(defn shown-image [server chat step-id]
  (let [view @(rf/subscribe [:view])]
    [retrying-img (ops/api-url server (str "chats/" chat "/shown/" step-id) view) "shown-image"]))
