(ns claudia.web.views.media-save
  "The viewer's Save button (README decision 81): the photo or video is fetched as a file (the same
  address and login cookie its img or video uses) and handed to claudia.web.download/hand-over!:
  the share sheet on an iPhone or iPad (Save Image, Save Video, Save to Files), a download
  elsewhere. The share sheet opens only right after a tap, so on those devices a photo is fetched
  as soon as it shows and the tap hands it over at once; a video (up to 1 GB) is fetched on the
  first tap, with how far it is on the button, then handed over, or, when iOS found the tap too
  long ago, the button says to tap again."
  (:require [reagent.hooks :as h]
            [claudia.web.api :as api]
            [claudia.web.download :as download]))

(defn- fetch!
  "Promise of the item's File (nil on failure), the state following it: {:busy pct} while it comes,
  {:file} once in, {:note} on failure."
  [{:keys [src] file-name :name} set-st]
  (set-st {:busy true})
  (-> (api/fetch-blob src #(set-st {:busy (or % true)}))
      (.then (fn [^js blob]
               (let [f (js/File. #js [blob] (download/save-name file-name (.-type blob) "file") #js {:type (.-type blob)})]
                 (set-st {:file f})
                 f)))
      (.catch (fn [e] (set-st {:note (str "Couldn't fetch it: " (.-message e))}) nil))))

(defn- outcome-note
  "What the button says after a hand-over ended in how (:shared, :downloaded) or with error e."
  [how ^js e]
  (cond (= how :downloaded) "Downloaded"
        (= how :shared) nil
        (= "AbortError" (some-> e .-name)) nil
        (= "NotAllowedError" (some-> e .-name)) "Ready: tap Save again"
        :else (str "Couldn't save: " (some-> e .-message))))

(defn- hand! [^js file set-st]
  (-> (download/hand-over! file)
      (.then #(set-st {:file file :note (outcome-note % nil)}))
      (.catch #(set-st {:file file :note (outcome-note nil %)}))))

(defn- pressed! [item {:keys [file busy]} set-st]
  (cond file (hand! file set-st)
        busy nil
        :else (.then (fetch! item set-st) #(when % (hand! % set-st)))))

(defn- label [{:keys [busy]} shares?]
  (cond busy (str "Fetching" (when (number? busy) (str " " busy "%")) "…")
        shares? "Save"
        :else "Download"))

(defn- save-icon []
  [:svg.viewer-ic {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M10 3.5v9M6 8.5l4 4 4-4M4 16h12"}]])

(defn save-button
  "Save for item {:src :name :kind}: its file is named after :name, with the extension of its type.
  Keyed by the item's src (its state is that item's)."
  [item]
  (let [[st set-st] (h/use-state {})
        shares? (download/shares?)]
    (h/use-effect (fn []
                    (when (and shares? (= :image (:kind item))) (fetch! item set-st))
                    js/undefined)
                  [(:src item)])
    [:<>
     (when (:note st) [:span.viewer-note {:role "status"} (:note st)])
     [:button.viewer-btn {:type "button" :disabled (some? (:busy st))
                          :title (if shares? "Save to Photos or Files" "Download the file")
                          :on-click #(pressed! item st set-st)}
      [save-icon] (label st shares?)]]))
