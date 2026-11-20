(ns claudia.web.views.folder-preview
  "The Folders modal's right pane (README decision 68): the file picked in the contents pane,
  previewed (an image, a video or a sound to play, a PDF, Markdown rendered, code and text coloured
  as the files form colours them), with its size and when it changed. With nothing picked, the
  folder's README; a picked folder, or no README, says to pick a file (README decision 74)."
  (:require [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.folders :as folders]
            [claudia.web.format :as format]
            [claudia.web.md :as md]
            [claudia.web.views.code :as code]
            [claudia.web.views.viewer :as viewer]
            [claudia.web.views.pure.folders :as pure]))

(defn- use-text
  "The text of the file at p ({:text} or {:error}; nil while asked, or when p is nil)."
  [server p]
  (let [[answer set-answer] (h/use-state nil)]
    (h/use-effect
     (fn []
       (set-answer nil)
       (when p
         (let [gone (atom false)]
           (-> (folders/file-text! server p)
               (.then #(when-not @gone (set-answer %)))
               (.catch #(when-not @gone (set-answer {:error (.-message %)}))))
           #(reset! gone true))))
     [server p])
    answer))

(defn- text-body [way item answer]
  (cond (nil? answer) [:p.muted "…"]
        (:error answer) [:p.muted (str "No preview: " (:error answer) ".")]
        (= way :markdown) [:div.md.fd-pv-md {:dangerouslySetInnerHTML (r/unsafe-html (md/html (:text answer)))}]
        :else [:f> code/code-view {:file (:name item) :code (:text answer) :whole true}]))

(defn- body [server item way answer open]
  (let [src (folders/file-url server (:path item))]
    (case way
      :image [:button.fd-pv-open {:type "button" :title "See it big" :on-click open}
              [:img.fd-pv-img {:src src :alt (:name item)}]]
      :video [:video.fd-pv-media {:src src :controls true :preload "metadata"}]
      :audio [:audio.fd-pv-audio {:src src :controls true :preload "metadata"}]
      :pdf [:iframe.fd-pv-pdf {:src src :title (:name item)}]
      :none [:p.muted (str "Too big to preview (" (format/bytes-text (:size item)) ").")]
      (text-body way item answer))))

(defn- file-view [{:keys [server item]}]
  (let [way (pure/preview-way item)
        answer (use-text server (when (#{:markdown :text} way) (:path item)))
        [big set-big] (h/use-state false)]
    [:div.fd-pv
     [:header.fd-pv-head
      [:span.fd-pv-name (:name item)]
      [:span.fd-pv-meta (str (some-> (:size item) format/bytes-text) " · " (format/when-text (:mtime item)))]
      (when (#{:image :video} way)
        [:button.fd-pv-big {:type "button" :title "See it big, and save it" :on-click #(set-big true)} "See it big"])]
     [:div.fd-pv-body (body server item way answer #(set-big true))]
     (when big
       [:f> viewer/viewer {:items [{:src (folders/file-url server (:path item)) :kind way :save true
                                    :name (:name item) :title (:name item)}]
                           :on-close #(set-big false)}])]))

(defn- readme-view [{:keys [server item]}]
  (let [answer (use-text server (:path item))]
    (when (:text answer)
      [:div.fd-readme
       [:div.fd-head (:name item)]
       [:div.md.fd-pv-md {:dangerouslySetInnerHTML (r/unsafe-html (md/html (:text answer)))}]])))

(defn pane
  "The right pane. opts: :server, :item (the picked item, nil for none), :readme (the folder's
  README item, or nil)."
  [{:keys [server item readme]}]
  (cond
    (and item (not (:dir item))) ^{:key (:path item)} [:f> file-view {:server server :item item}]
    (and (nil? item) readme) ^{:key (:path readme)} [:f> readme-view {:server server :item readme}]
    :else [:p.muted.fd-detail "Pick a file to preview it."]))
