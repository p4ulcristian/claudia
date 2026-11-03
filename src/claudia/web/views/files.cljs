(ns claudia.web.views.files
  "The files form: a folder as a tree, read live through the runner (claudia.slave.files); folders
  open on click, a file opens in place as code. Marks point files out; git status shows as a
  letter; sketched (planned) files are labelled as not on disk."
  (:require [clojure.string :as str]
            [reagent.hooks :as h]
            ["react-dom" :as react-dom]
            [reagent.core :as r]
            [claudia.web.api :as api]
            [claudia.web.views.code :as code]))

(def ^:private poll-ms 2500)
(def ^:private image-re #"(?i)\.(png|jpe?g|gif|webp|avif|svg|bmp|ico)$")
(def ^:private status-tip {"M" "changed" "A" "added" "U" "new, not in git yet" "D" "deleted"})

(def ^:private ext-color
  {"ts" "blue" "tsx" "blue" "mts" "blue" "js" "yellow" "jsx" "yellow" "mjs" "yellow" "cjs" "yellow"
   "clj" "green" "cljs" "green" "cljc" "green" "edn" "teal" "bb" "green"
   "md" "grey" "txt" "grey" "json" "amber" "yaml" "amber" "yml" "amber" "toml" "amber" "lock" "grey"
   "css" "violet" "scss" "violet" "html" "orange" "svg" "orange" "sql" "cyan" "sh" "lime" "py" "sky"
   "rs" "orange" "go" "cyan" "swift" "orange" "service" "pink" "png" "magenta" "jpg" "magenta" "webp" "magenta"})

(defn folder-icon [open?]
  [:svg.fo {:class (when open? "open") :viewBox "0 0 22 16" :aria-hidden true}
   [:path {:d (if open?
                "M2 4.2c0-.8.6-1.4 1.4-1.4h4.4l1.6 1.8h7c.8 0 1.4.6 1.4 1.4v1.2H6.2L3.4 13.6H3.2c-.7 0-1.2-.5-1.2-1.2z M6.2 7.2h13.6l-2.6 6.4H3.4z"
                "M2 4.2c0-.8.6-1.4 1.4-1.4h4.4l1.6 1.8h8.4c.8 0 1.4.6 1.4 1.4v7.2c0 .8-.6 1.4-1.4 1.4H3.4c-.8 0-1.4-.6-1.4-1.4z")}]])

(defn ext-tag [nm]
  (let [dot (str/last-index-of nm ".")
        ext (if (and dot (pos? dot)) (str/lower-case (subs nm (inc dot))) "")
        c (ext-color ext)]
    [:span.ft {:style {"--ft" (cond (nil? c) "var(--muted)" (= c "grey") "var(--muted)" :else (str "var(--c-" c ", var(--" c "))"))}}
     (if (seq ext) (subs ext 0 (min 5 (count ext))) "·")]))

(defn- crumbs [root go]
  (let [home (re-find #"^/home/[^/]+" root)
        rest-parts (filterv seq (str/split (if home (subs root (count home)) root) #"/"))
        parts (into (if home [{:label "~" :path home}] [])
                    (map-indexed (fn [i p] {:label p :path (str home "/" (str/join "/" (subvec rest-parts 0 (inc i))))}) rest-parts))]
    (into [:span.f-crumbs]
          (map-indexed (fn [i p]
                         ^{:key (:path p)}
                         [:<>
                          (when (pos? i) [:span.sep "/"])
                          (if (= i (dec (count parts)))
                            [:b (:label p)]
                            [:button.crumb {:type "button" :on-click #(go (:path p))} (:label p)])])
                       parts))))

(defn image-big
  "An image over the whole window; a click or Escape closes it."
  [src close]
  (h/use-effect
   (fn []
     (let [k (fn [e] (when (= "Escape" (.-key e)) (.stopImmediatePropagation e) (close)))]
       (js/window.addEventListener "keydown" k true)
       #(js/window.removeEventListener "keydown" k true)))
   [])
  (react-dom/createPortal
   (r/as-element [:div.img-big {:on-click close :role "dialog" :aria-label "Image"} [:img {:src src :alt ""}]])
   js/document.body))

(defn- ancestors-of [p]
  (let [parts (str/split p #"/")]
    (for [i (range 1 (count parts))] (str/join "/" (take i parts)))))

(defn- clean [p] (-> (str p) (str/replace #"^\.?/" "") (str/replace #"/$" "")))

(defn- hide-dot-stored [] (try (= "1" (js/localStorage.getItem "files.hideDot")) (catch :default _ false)))

(defn files-form
  "ctx: {:server :chat} of the picture's chat (nil in the gallery)."
  [data {:keys [server chat]}]
  (let [drawn (if (string? (:root data)) (:root data) "")
        [root set-root] (h/use-state drawn)
        _ (h/use-effect (fn [] (set-root drawn) nil) [drawn])
        rebase (fn [p] (if (= root drawn) p
                           (let [abs (str/replace (str drawn "/" p) #"/$" "")]
                             (when (str/starts-with? abs (str root "/")) (subs abs (inc (count root)))))))
        sketch (when (sequential? (:sketch data)) (:sketch data))
        demo (when (sequential? (:demo data)) (:demo data))
        live (and (seq root) (not demo))
        [real set-real] (h/use-state false)
        planned (if (and live sketch real) [] (or sketch demo []))
        made (vec (keep (fn [e] (when-let [p (rebase (:path e))] (assoc e :path p))) planned))
        marks (into {} (keep (fn [m] (when-let [p (rebase (clean (:path m)))] [p m]))) (code/items (:marks data)))
        first-marked (some #(when (re-find #"\.[^/]+$" %) %) (keys marks))
        opened0 (if (string? (:file data)) (rebase (clean (:file data))) first-marked)
        [open set-open] (h/use-state (fn [] (into #{""} (when opened0 (ancestors-of opened0)))))
        [dirs set-dirs] (h/use-state {})
        [files set-files] (h/use-state {})
        [mine set-mine] (h/use-state #{})
        [tick set-tick] (h/use-state 0)
        [hide-dot set-hide-dot] (h/use-state hide-dot-stored)
        [big set-big] (h/use-state nil)
        tree-ref (h/use-ref nil)
        url (fn [p q] (api/url server (str "chats/" (js/encodeURIComponent chat) "/files") (merge {:root root :path p} q)))
        read-dir (fn [d gone?]
                   (-> (api/fetch-json (url d nil))
                       (.then (fn [j] (:entries j)) (fn [e] {:error (.-message e)}))
                       (.then (fn [next] (when-not (gone?) (set-dirs #(if (= (get % d) next) % (assoc % d next))))))))
        fill (fn [f] (set-files #(if (contains? % (:path f)) (assoc % (:path f) f) %)))
        open-ref (h/use-ref open)
        files-ref (h/use-ref files)
        _ (set! (.-current open-ref) open)
        _ (set! (.-current files-ref) files)]
    ;; Every open folder is read when it opens (and again after refresh).
    (h/use-effect
     (fn []
       (when (and live chat)
         (let [gone (atom false)]
           (doseq [d open] (read-dir d #(deref gone)))
           #(reset! gone true))))
     [(str/join "|" (sort open)) root chat tick])
    ;; Live from disk: open folders and files read again every poll-ms, in place.
    (h/use-effect
     (fn []
       (when (and live chat)
         (let [gone (atom false)
               timer (js/setInterval
                      (fn []
                        (when-not js/document.hidden
                          (doseq [d (.-current open-ref)] (read-dir d #(deref gone)))
                          (doseq [f (vals (.-current files-ref)) :when (:mtime f)]
                            (-> (api/fetch-json (url (:path f) {:read 1 :since (:mtime f)}))
                                (.then (fn [j] (when-not (or @gone (:same j)) (fill (assoc j :path (:path f))))))
                                (.catch (fn [_]))))))
                      poll-ms)]
           #(do (reset! gone true) (js/clearInterval timer)))))
     [live root chat])
    (let [entries-of
          (fn [d]
            (let [pre (if (seq d) (str d "/") "")
                  disk (if live (get dirs d) [])]
              (when (some? disk)
                (let [disk (if (map? disk) (if (some #(str/starts-with? (:path %) pre) made) [] disk) disk)]
                  (if (map? disk)
                    disk
                    (let [base (into {} (map (juxt :name identity)) disk)
                          all (reduce (fn [out e]
                                        (let [p (str/replace (:path e) #"/$" "")]
                                          (if-not (str/starts-with? p pre)
                                            out
                                            (let [rest-p (subs p (count pre))
                                                  slash (str/index-of rest-p "/")
                                                  nm (if slash (subs rest-p 0 slash) rest-p)
                                                  on-disk (get out nm)]
                                              (if (or (not (seq nm)) (and on-disk live))
                                                out
                                                (assoc out nm (cond-> (merge on-disk {:name nm :dir (or (some? slash) (boolean (:dir e))) :planned (boolean sketch)})
                                                                (and (not slash) (string? (:code e))) (assoc :lines (count (str/split (str/replace (:code e) #"\n$" "") #"\n")))
                                                                (and (not slash) (:status e)) (assoc :status (:status e)))))))))
                                      base made)]
                      (sort-by (juxt #(if (:dir %) 0 1) :name) (vals all))))))))
          load (fn [p]
                 (set-files #(if (contains? % p) % (assoc % p {:path p})))
                 (let [m (some #(when (= (str/replace (:path %) #"/$" "") p) %) made)
                       cut (str/last-index-of p "/")
                       listed (get dirs (if cut (subs p 0 cut) ""))
                       on-disk (and live (sequential? listed) (some #(= (:name %) (subs p (inc (or cut -1)))) listed))]
                   (cond
                     (and live (re-find image-re p) (not (and m (not on-disk)))) (fill {:path p :image (url p {:image 1})})
                     (and m (not on-disk)) (fill {:path p :text (or (:code m) (if demo "// a file opens here, coloured like VS Code" "(no code given for this file)"))})
                     (not live) (fill {:path p :error "not given"})
                     :else (-> (api/fetch-json (url p {:read 1}))
                               (.then #(fill (assoc % :path p)))
                               (.catch (fn [_] (fill {:path p :error "can't read it"})))))))
          close (fn [p] (set-files #(dissoc % p)) (set-mine #(disj % p)))
          close-all (fn [] (set-files {}) (set-mine #{}))
          open-file (fn [p] (if (contains? files p) (close p) (do (set-mine #(conj % p)) (load p))))
          toggle (fn [d] (set-open #(if (% d) (disj % d) (conj % d))))
          pointed (set (mapcat #(cons % (ancestors-of %)) (concat (keys marks) (when opened0 [opened0]))))
          rows (atom [])
          walk (fn walk [d depth]
                 (let [es (entries-of d)]
                   (cond
                     (nil? es) (swap! rows conj ^{:key (str "l" d)} [:div.f-tree-row.muted {:style {"--depth" depth}} "…"])
                     (map? es) (swap! rows conj ^{:key (str "e" d)} [:div.f-tree-row.err {:style {"--depth" depth}} (:error es)])
                     :else
                     (doseq [e es
                             :let [p (if (seq d) (str d "/" (:name e)) (:name e))]
                             :when (not (and hide-dot (str/starts-with? (:name e) ".") (not (pointed p))))]
                       (let [m (get marks p)
                             f (when-not (:dir e) (get files p))
                             open? (if (:dir e) (contains? open p) (some? f))
                             can-go (and (:dir e) live (not (:planned e)))]
                         (swap! rows conj
                                ^{:key p}
                                [:button.f-tree-row {:type "button"
                                                     :class [(if (:dir e) "dir" "file") (when open? "open") (when m "marked")]
                                                     :style {"--depth" depth "--mark" (or (code/color-var (:color m)) "var(--amber)")}
                                                     :data-item (:id m)
                                                     :on-click #(if (:dir e) (toggle p) (open-file p))
                                                     :on-double-click #(when can-go (set-root (str root "/" p)))
                                                     :title (when can-go "Double-click to go into this folder")}
                                 (if (:dir e) [folder-icon open?] [ext-tag (:name e)])
                                 [:span.nm (:name e)]
                                 (when (and (not (:dir e)) (:lines e)) [:span.f-lines-n (:lines e)])
                                 (when (:planned e) [:span.f-planned {:title "Not on disk: planned, as Claude sketched it"} "planned"])
                                 (when (:status e) [:span.st {:class (str "st-" (:status e)) :title (status-tip (:status e))} (:status e)])
                                 (when (and (:dir e) (pos? (or (:changed e) 0))) [:span.st.st-M {:title (str (:changed e) " changed inside")} "•"])
                                 (when (:note m) [:span.mk (:note m)])])
                         (when (and (:dir e) open?) (walk p (inc depth)))
                         (when (and (not (:dir e)) f)
                           (swap! rows conj
                                  ^{:key (str "f" p)}
                                  [:div.f-tree-file {:style {"--depth" (inc depth)}}
                                   (cond
                                     (:image f) [:div.f-image {:on-double-click #(set-big (:image f)) :title "Double-click to see it big"}
                                                 [:img {:src (:image f) :alt p}]]
                                     (:error f) [:p.f-note (:error f)]
                                     (nil? (:text f)) [:p.f-note "…"]
                                     :else [:f> code/code-view (merge {:file p :code (:text f) :whole true}
                                                                      (when (= p opened0) {:mark (:lines data) :notes (:notes data)}))])])))))))]
      ;; Another folder: start from its top.
      (let [first-root (h/use-ref root)]
        (h/use-effect
         (fn []
           (when (not= (.-current first-root) root)
             (set! (.-current first-root) root)
             (set-dirs {}) (close-all) (set-open #{""}))
           nil)
         [root]))
      ;; Claude names a file: it opens, now and whenever it names another; the one before closes
      ;; unless the user opened it too.
      (let [named (h/use-ref nil)]
        (h/use-effect
         (fn []
           (let [before (.-current named)]
             (set! (.-current named) opened0)
             (when (and before (not= before opened0) (not (mine before))) (close before))
             (when opened0
               (set-open #(into % (ancestors-of opened0)))
               (load opened0)))
           nil)
         [opened0 root (count dirs)]))
      ;; The file Claude opened: the picture scrolls to its first highlighted line or note.
      (let [lines-key (pr-str [(:lines data) (map :line (code/items (:notes data)))])
            text (:text (get files opened0))]
        (h/use-effect
         (fn []
           (when text
             (let [raf (js/requestAnimationFrame
                        (fn []
                          (let [hit (some-> (.-current tree-ref) (.querySelector ".f-ln.hot, .f-pin"))
                                pane (some-> hit (.closest ".picture-scroll"))]
                            (when (and hit pane)
                              (set! (.-scrollTop pane) (+ (.-scrollTop pane) (- (.-top (.getBoundingClientRect hit)) (.-top (.getBoundingClientRect pane)) (/ (.-clientHeight pane) 3))))))))]
               #(js/cancelAnimationFrame raf))))
         [opened0 (some? text) lines-key]))
      (walk "" 0)
      [:div.f-files
       [:div.f-code-head.f-files-head
        [:span.f-file {:title root}
         (cond (and (not live) sketch) [:b (if (string? (:title data)) (:title data) "planned files")]
               demo [:<> [:span.dir "~/Work/apps/"] [:b "shop"]]
               :else [crumbs root set-root])]
        (if (and live sketch)
          [:span.f-kinds {:role "radiogroup" :aria-label "Sketch or real"}
           [:button.f-kind.made {:type "button" :role "radio" :aria-checked (not real) :class (when-not real "on") :on-click #(set-real false)
                                 :title "The sketch: the folder on disk plus the files Claude planned"}
            (str "sketch · + " (count sketch) " planned")]
           [:button.f-kind.real {:type "button" :role "radio" :aria-checked real :class (when real "on") :on-click #(set-real true)
                                 :title "Only what is on disk, read live"} "real"]]
          [:<>
           (when live [:span.f-kind.real {:title "Read live from disk"} "on disk"])
           (when sketch [:span.f-kind.made {:title "Files Claude sketched; they are not on disk"} "sketch · not on disk"])])
        (when demo [:span.f-kind.made "example"])
        [:span.f-tools
         [:label.f-tool.f-check {:title "Hide files and folders whose name starts with a dot"}
          [:input {:type "checkbox" :checked hide-dot
                   :on-change (fn [e] (let [on (.. e -target -checked)]
                                        (set-hide-dot on)
                                        (try (js/localStorage.setItem "files.hideDot" (if on "1" "0")) (catch :default _ nil))))}]
          [:span.box {:aria-hidden true}] "hide hidden"]
         [:span.f-tools-sep {:aria-hidden true}]
         (when (and live (not= root drawn))
           [:button.f-tool {:type "button" :on-click #(set-root drawn) :title (str "Back to " (str/replace drawn #"^/home/[^/]+" "~"))} "↺ back"])
         [:button.f-tool {:type "button" :disabled (not (or (> (count open) 1) (seq files))) :on-click #(do (set-open #{""}) (close-all))} "collapse all"]
         (when live
           [:button.f-tool {:type "button" :on-click #(do (set-tick inc) (doseq [f (vals files) :when (:mtime f)] (load (:path f))))} "refresh"])]]
       (into [:div.f-tree {:ref #(set! (.-current tree-ref) %)}] (if (and live (not chat)) [[:p.f-note "Open this chat to browse its files."]] @rows))
       (when big [:f> image-big big #(set-big nil)])])))
