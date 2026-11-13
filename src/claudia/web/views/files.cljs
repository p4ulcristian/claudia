(ns claudia.web.views.files
  "The files form: a folder as a tree, read live through the runner (claudia.slave.files); folders
  open on click, a file opens in place as code. Marks point files out; git status shows as a
  letter; sketched (planned) files are labelled as not on disk."
  (:require [clojure.string :as str]
            [claudia.web.views.zoom :as zoom]
            [reagent.hooks :as h]
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

(defn ext-of-name
  "A file name's extension in lower case, \"\" when it has none (a leading dot is not one)."
  [nm]
  (let [dot (str/last-index-of nm ".")]
    (if (and dot (pos? dot)) (str/lower-case (subs nm (inc dot))) "")))

(defn ext-colour
  "The tag's colour for an extension: its chalk colour, muted for grey or an unknown one."
  [ext]
  (let [c (ext-color ext)]
    (cond (nil? c) "var(--muted)" (= c "grey") "var(--muted)" :else (str "var(--c-" c ", var(--" c "))"))))

(defn ext-tag [nm]
  (let [ext (ext-of-name nm)]
    [:span.ft {:style {"--ft" (ext-colour ext)}}
     (if (seq ext) (subs ext 0 (min 5 (count ext))) "·")]))

(defn crumb-parts
  "The breadcrumbs of a path: [{:label :path}], the home folder as ~."
  [root]
  (let [home (re-find #"^/home/[^/]+" root)
        rest-parts (filterv seq (str/split (if home (subs root (count home)) root) #"/"))]
    (into (if home [{:label "~" :path home}] [])
          (map-indexed (fn [i p] {:label p :path (str home "/" (str/join "/" (subvec rest-parts 0 (inc i))))}) rest-parts))))

(defn- crumbs [root go]
  (let [parts (crumb-parts root)]
    (into [:span.f-crumbs]
          (map-indexed (fn [i p]
                         ^{:key (:path p)}
                         [:<>
                          (when (pos? i) [:span.sep "/"])
                          (if (= i (dec (count parts)))
                            [:b (:label p)]
                            [:button.crumb {:type "button" :on-click #(go (:path p))} (:label p)])])
                       parts))))

(defn ancestors-of [p]
  (let [parts (str/split p #"/")]
    (for [i (range 1 (count parts))] (str/join "/" (take i parts)))))

(defn clean [p] (-> (str p) (str/replace #"^\.?/" "") (str/replace #"/$" "")))

(defn- hide-dot-stored [] (try (= "1" (js/localStorage.getItem "files.hideDot")) (catch :default _ false)))

;; ---- what the form shows: pure, from its data ----

(defn rebase
  "A path under the drawn root as a path under root (nil when it is not under root)."
  [root drawn p]
  (if (= root drawn)
    p
    (let [whole (str/replace (str drawn "/" p) #"/$" "")]
      (when (str/starts-with? whole (str root "/")) (subs whole (inc (count root)))))))

(defn plan
  "The form's data seen from root: {:sketch :demo :live :made (planned files, rebased) :marks (by
  path) :opened0 (the file Claude opened, else the first file marked)}. real: only what is on disk."
  [data root drawn real]
  (let [sketch (when (sequential? (:sketch data)) (:sketch data))
        demo (when (sequential? (:demo data)) (:demo data))
        live (and (seq root) (not demo))
        planned (if (and live sketch real) [] (or sketch demo []))
        marks (into {} (keep (fn [m] (when-let [p (rebase root drawn (clean (:path m)))] [p m]))) (code/items (:marks data)))]
    {:sketch sketch :demo demo :live live :marks marks
     :made (vec (keep (fn [e] (when-let [p (rebase root drawn (:path e))] (assoc e :path p))) planned))
     :opened0 (if (string? (:file data))
                (rebase root drawn (clean (:file data)))
                (some #(when (re-find #"\.[^/]+$" %) %) (keys marks)))}))

(defn- line-count [code-text] (count (str/split (str/replace code-text #"\n$" "") #"\n")))

(defn- add-planned
  "out (entries by name) with planned entry e added when it lies in the folder pre; a name on disk
  stays as it is when the tree is live."
  [pre live sketch out e]
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
                          (and (not slash) (string? (:code e))) (assoc :lines (line-count (:code e)))
                          (and (not slash) (:status e)) (assoc :status (:status e)))))))))

(defn- dir-order [a b]
  (if (= (boolean (:dir a)) (boolean (:dir b))) (.localeCompare (:name a) (:name b)) (if (:dir a) -1 1)))

(defn dir-entries
  "Folder d's entries, folders first: what is on disk with the planned files added. nil while it is
  read; {:error} when it can't be (unless planned files fill it)."
  [{:keys [dirs made live sketch]} d]
  (let [pre (if (seq d) (str d "/") "")
        disk (if live (get dirs d) [])]
    (when (some? disk)
      (let [disk (if (map? disk) (if (some #(str/starts-with? (:path %) pre) made) [] disk) disk)]
        (if (map? disk)
          disk
          (sort dir-order (vals (reduce #(add-planned pre live sketch %1 %2) (into {} (map (juxt :name identity)) disk) made))))))))

(defn load-way
  "How file p opens: :image, :read (from disk), or the file itself ({:text} planned, {:error})."
  [{:keys [made dirs live demo]} p]
  (let [m (some #(when (= (str/replace (:path %) #"/$" "") p) %) made)
        cut (str/last-index-of p "/")
        listed (get dirs (if cut (subs p 0 cut) ""))
        on-disk (and live (sequential? listed) (some #(= (:name %) (subs p (inc (or cut -1)))) listed))]
    (cond
      (and live (re-find image-re p)) :image
      (and m (not on-disk)) {:text (or (:code m) (if demo "// a file opens here, coloured like VS Code" "(no code given for this file)"))}
      (not live) {:error "not given"}
      :else :read)))

(defn- hidden-entry? [{:keys [hide-dot pointed]} e p]
  (and hide-dot (str/starts-with? (:name e) ".") (not (pointed p))))

(declare tree-rows)

(defn- entry-rows [{:keys [open files marks] :as tree} d depth e]
  (let [p (if (seq d) (str d "/" (:name e)) (:name e))
        f (when-not (:dir e) (get files p))
        open? (if (:dir e) (contains? open p) (some? f))]
    (when-not (hidden-entry? tree e p)
      (concat [{:row :entry :p p :e e :m (get marks p) :depth depth :open? open?}]
              (when (and (:dir e) open?) (tree-rows tree p (inc depth)))
              (when (and (not (:dir e)) f) [{:row :file :p p :f f :depth (inc depth)}])))))

(defn tree-rows
  "The tree's rows from folder d down, as data: {:row :loading|:error|:entry|:file …}. tree: {:dirs
  :made :live :sketch :open :files :marks :hide-dot :pointed}."
  [tree d depth]
  (let [es (dir-entries tree d)]
    (cond
      (nil? es) [{:row :loading :d d :depth depth}]
      (map? es) [{:row :error :d d :depth depth :error (:error es)}]
      :else (vec (mapcat #(entry-rows tree d depth %) es)))))

(defn not-readable-note
  "What the tree says when the files are live but this page can't read them."
  [root opened0 data]
  (str "Shown to the user live from disk (not readable here): " root
       (when opened0 (str "; open: " opened0))
       (when (seq (code/items (:lines data))) (str ", lines " (str/join ", " (code/items (:lines data)))))
       (when (seq (code/items (:notes data))) (str ", " (count (code/items (:notes data))) " notes"))))

;; ---- the tree's rows ----

(defn- entry-row [{:keys [p e m depth open?]} {:keys [live root toggle open-file set-root]}]
  (let [can-go (and (:dir e) live (not (:planned e)))]
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
     (when (:note m) [:span.mk (:note m)])]))

(defn- file-row [{:keys [p f depth]} {:keys [opened0 data set-big]}]
  ^{:key (str "f" p)}
  [:div.f-tree-file {:style {"--depth" depth}}
   (cond
     (:image f) [:div.f-image {:on-double-click #(set-big (:image f)) :title "Double-click to see it big"}
                 [:img {:src (:image f) :alt p}]]
     (:error f) [:p.f-note (:error f)]
     (nil? (:text f)) [:p.f-note "…"]
     :else [:f> code/code-view (merge {:file p :code (:text f) :whole true}
                                      (when (= p opened0) {:mark (:lines data) :notes (:notes data)}))])])

(defn- row-view [row ctx]
  (case (:row row)
    :loading ^{:key (str "l" (:d row))} [:div.f-tree-row.muted {:style {"--depth" (:depth row)}} "…"]
    :error ^{:key (str "e" (:d row))} [:div.f-tree-row.err {:style {"--depth" (:depth row)}} (:error row)]
    :entry (entry-row row ctx)
    :file (file-row row ctx)))

;; ---- reading from disk ----

(defn- use-refresh
  "While on?, calls (f gone?) every ms milliseconds the page is seen; gone? tells a late answer
  the effect has ended. Not hooks/use-poll: each tick reads every open folder and file, one request
  each, not one URL with one fingerprint."
  [on? ms f deps]
  (h/use-effect
   (fn []
     (when on?
       (let [gone (atom false)
             timer (js/setInterval #(when-not js/document.hidden (f (fn [] @gone))) ms)]
         #(do (reset! gone true) (js/clearInterval timer)))))
   deps))

(defn- reader
  "The form's reads: url, read-dir (a folder read again replaces its entries only when they changed,
  so nothing redraws for nothing) and fill (a file's answer, kept only while it is open)."
  [server chat root set-dirs set-files]
  (let [url (fn [p q] (api/url server (str "chats/" (js/encodeURIComponent chat) "/files") (merge {:root root :path p} q)))]
    {:url url
     :read-dir (fn [d gone?]
                 (-> (api/fetch-json (url d nil))
                     (.then (fn [j] (:entries j)) (fn [e] (if (instance? js/TypeError e) ::offline {:error (or (.-message e) "can't read")})))
                     (.then (fn [entries] (when-not (or (gone?) (= entries ::offline)) (set-dirs #(if (= (get % d) entries) % (assoc % d entries))))))))
     :fill (fn [f] (set-files #(if (contains? % (:path f)) (assoc % (:path f) f) %)))}))

(defn- refresh-open!
  "One tick: every open folder and every open file read again, in place."
  [{:keys [url read-dir fill]} open-ref files-ref gone?]
  (doseq [d (.-current open-ref)] (read-dir d gone?))
  (doseq [f (vals (.-current files-ref)) :when (:mtime f)]
    (-> (api/fetch-json (url (:path f) {:read 1 :since (:mtime f)}))
        (.then (fn [j] (when-not (or (gone?) (:same j)) (fill (assoc j :path (:path f))))))
        (.catch (fn [_])))))

(defn- use-disk
  "Every open folder read when it opens (and again after refresh); live from disk, open folders and
  files read again every poll-ms."
  [{:keys [read-dir] :as rd} {:keys [live open files root chat tick]}]
  (let [open-ref (h/use-ref open)
        files-ref (h/use-ref files)]
    (set! (.-current open-ref) open)
    (set! (.-current files-ref) files)
    (h/use-effect
     (fn []
       (when (and live chat)
         (let [gone (atom false)]
           (doseq [d open] (read-dir d #(deref gone)))
           #(reset! gone true))))
     [(str/join "|" (sort open)) root chat tick])
    (use-refresh (and live chat) poll-ms #(refresh-open! rd open-ref files-ref %) [live root chat])))

(defn- load!
  "Opens file p: read from disk, or shown from the plan."
  [{:keys [url fill]} set-files tree p]
  (set-files #(if (contains? % p) % (assoc % p {:path p})))
  (let [way (load-way tree p)]
    (case way
      :image (fill {:path p :image (url p {:image 1})})
      :read (-> (api/fetch-json (url p {:read 1}))
                (.then #(fill (assoc % :path p)))
                (.catch (fn [_] (fill {:path p :error "can't read it"}))))
      (fill (assoc way :path p)))))

;; ---- following Claude ----

(defn- use-root-reset
  "Another folder: start from its top."
  [root start-over]
  (let [first-root (h/use-ref root)]
    (h/use-effect
     (fn []
       (when (not= (.-current first-root) root)
         (set! (.-current first-root) root)
         (start-over))
       nil)
     [root])))

(defn- use-claude-file
  "Claude names a file: it opens, now and whenever it names another; the one before closes unless
  the user opened it too."
  [opened0 root dirs mine close load set-open]
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
     [opened0 root (count dirs)])))

(defn- scroll-to-hit! [tree-ref]
  (let [hit (some-> (.-current tree-ref) (.querySelector ".f-ln.hot, .f-pin"))
        pane (some-> hit (.closest ".picture-scroll"))]
    (when (and hit pane)
      (set! (.-scrollTop pane) (+ (.-scrollTop pane) (- (.-top (.getBoundingClientRect hit)) (.-top (.getBoundingClientRect pane)) (/ (.-clientHeight pane) 3)))))))

(defn- use-scroll-to-mark
  "The file Claude opened: the picture scrolls to its first highlighted line or note."
  [data files opened0 tree-ref]
  (let [lines-key (pr-str [(:lines data) (map :line (code/items (:notes data)))])
        text (:text (get files opened0))]
    (h/use-effect
     (fn []
       (when text
         (let [raf (js/requestAnimationFrame #(scroll-to-hit! tree-ref))]
           #(js/cancelAnimationFrame raf))))
     [opened0 (some? text) lines-key])))

;; ---- the head ----

(defn- source-label [{:keys [live sketch demo]} data root set-root]
  [:span.f-file {:title root}
   (cond (and (not live) sketch) [:b (if (string? (:title data)) (:title data) "planned files")]
         demo [:<> [:span.dir "~/Work/apps/"] [:b "shop"]]
         :else [crumbs root set-root])])

(defn- kinds [{:keys [live sketch demo]} real set-real]
  [:<>
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
   (when demo [:span.f-kind.made "example"])])

(defn- hide-dot-box [hide-dot set-hide-dot]
  [:label.f-tool.f-check {:title "Hide files and folders whose name starts with a dot"}
   [:input {:type "checkbox" :checked hide-dot
            :on-change (fn [e] (let [on (.. e -target -checked)]
                                 (set-hide-dot on)
                                 (try (js/localStorage.setItem "files.hideDot" (if on "1" "0")) (catch :default _ nil))))}]
   [:span.box {:aria-hidden true}] "hide hidden"])

(defn- tools [{:keys [live]} {:keys [root drawn set-root open files set-open close-all set-tick load hide-dot set-hide-dot]}]
  [:span.f-tools
   (hide-dot-box hide-dot set-hide-dot)
   [:span.f-tools-sep {:aria-hidden true}]
   (when (and live (not= root drawn))
     [:button.f-tool {:type "button" :on-click #(set-root drawn) :title (str "Back to " (str/replace drawn #"^/home/[^/]+" "~"))} "↺ back"])
   [:button.f-tool {:type "button" :disabled (not (or (> (count open) 1) (seq files))) :on-click #(do (set-open #{""}) (close-all))} "collapse all"]
   (when live
     [:button.f-tool {:type "button" :on-click #(do (set-tick inc) (doseq [f (vals files) :when (:mtime f)] (load (:path f))))} "refresh"])])

;; ---- the form ----

(defn- use-tree-state
  "The tree's state: the open folders, the folders read, the open files (and which the user opened),
  a refresh count, the hide-dot switch and the image shown big."
  [opened0]
  (let [[open set-open] (h/use-state (fn [] (into #{""} (when opened0 (ancestors-of opened0)))))
        [dirs set-dirs] (h/use-state {})
        [files set-files] (h/use-state {})
        [mine set-mine] (h/use-state #{})
        [tick set-tick] (h/use-state 0)
        [hide-dot set-hide-dot] (h/use-state hide-dot-stored)
        [big set-big] (h/use-state nil)]
    {:open open :set-open set-open :dirs dirs :set-dirs set-dirs :files files :set-files set-files
     :mine mine :set-mine set-mine :tick tick :set-tick set-tick :hide-dot hide-dot :set-hide-dot set-hide-dot
     :big big :set-big set-big}))

(defn- actions
  "What the rows and tools do, on top of the state."
  [{:keys [files set-files set-mine set-open] :as st} rd tree]
  (let [close (fn [p] (set-files #(dissoc % p)) (set-mine #(disj % p)))
        load (fn [p] (load! rd set-files tree p))]
    (assoc st :close close :load load
           :close-all (fn [] (set-files {}) (set-mine #{}))
           :open-file (fn [p] (if (contains? files p) (close p) (do (set-mine #(conj % p)) (load p))))
           :toggle (fn [d] (set-open #(if (% d) (disj % d) (conj % d)))))))

(defn- form-view [pl data {:keys [root set-root real set-real tree-ref big set-big] :as ctx} rows]
  [:div.f-files
   [:div.f-code-head.f-files-head
    (source-label pl data root set-root)
    (kinds pl real set-real)
    (tools pl ctx)]
   (into [:div.f-tree {:ref #(set! (.-current tree-ref) %)}]
         (if (and (:live pl) (not (:chat ctx)))
           [[:p.f-note (not-readable-note root (:opened0 pl) data)]]
           (map #(row-view % ctx) rows)))
   (when big [:f> zoom/image-big big #(set-big nil)])])

(defn files-form
  "ctx: {:server :chat} of the picture's chat (nil in the gallery)."
  [data {:keys [server chat]}]
  (let [drawn (if (string? (:root data)) (:root data) "")
        [root set-root] (h/use-state drawn)
        _ (h/use-effect (fn [] (set-root drawn) nil) [drawn])
        [real set-real] (h/use-state false)
        pl (plan data root drawn real)
        st (use-tree-state (:opened0 pl))
        tree-ref (h/use-ref nil)
        rd (reader server chat root (:set-dirs st) (:set-files st))
        tree (merge pl (select-keys st [:dirs :open :files :hide-dot])
                    {:pointed (set (mapcat #(cons % (ancestors-of %)) (concat (keys (:marks pl)) (when (:opened0 pl) [(:opened0 pl)]))))})
        ctx (merge (actions st rd tree) pl
                   {:root root :set-root set-root :drawn drawn :real real :set-real set-real :tree-ref tree-ref :data data :chat chat})]
    (use-disk rd (assoc st :live (:live pl) :root root :chat chat))
    (use-root-reset root #(do ((:set-dirs st) {}) ((:close-all ctx)) ((:set-open st) #{""})))
    (use-claude-file (:opened0 pl) root (:dirs st) (:mine st) (:close ctx) (:load ctx) (:set-open st))
    (use-scroll-to-mark data (:files st) (:opened0 pl) tree-ref)
    (form-view pl data ctx (tree-rows tree "" 0))))
