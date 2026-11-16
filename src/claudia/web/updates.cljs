(ns claudia.web.updates
  "The page reloads itself when a new version is live (README decision 27). The hub names the
  commit it runs in /hub/me; the page keeps the one it loaded with and asks again each time a
  socket opens (a hub that restarted on a new version drops every socket, so they all open again).
  Another commit: the page first fetches the new version's files (index.html and every script and
  style it names) into the browser's cache, then reloads at once, keeping every composer's text
  across the reload (sessionStorage): the reload revalidates instead of downloading, so it takes
  about as long as opening the page. It never reloads while a new chat is on its way, a file waits
  to be sent, or the mic is held or being heard: then it asks again in a few seconds. A message
  already sent does not hold it (it is with the runner; one whose echo never matched once kept a
  page from ever reloading)."
  (:require [re-frame.core :as rf]
            [claudia.shared.protocol :as protocol]))

(def ^:private kept-key "claudia.reloadDrafts")

(def ^:private retry-ms
  "How soon a reload that had to wait looks again."
  3000)

(defn newer?
  "Whether the hub runs another commit than the one the page loaded with (both known)."
  [loaded heard]
  (boolean (and (seq loaded) (seq heard) (not= loaded heard))))

(defn reload-blocked?
  "Whether a reload now would lose something: a new chat on its way, or files waiting to go with a
  message."
  [db]
  (boolean (or (seq (:pending-new db))
               (some seq (vals (:attach db))))))

(defn drafts-json
  "The composers' texts as JSON for the reload ([server chat] or :new -> text; empty ones left out)."
  [drafts]
  (protocol/write-json (vec (for [[k text] drafts :when (seq text)]
                              {:k (if (= :new k) "new" k) :text text}))))

(defn json-drafts
  "drafts-json read back: [server chat] or :new -> text (nil or bad JSON: none)."
  [s]
  (let [items (try (protocol/read-json (or s "[]")) (catch :default _ []))]
    (into {} (for [{:keys [k text]} (when (sequential? items) items)
                   :when (and (string? text) (or (= "new" k) (and (sequential? k) (= 2 (count k)))))]
               [(if (= "new" k) :new (vec k)) text]))))

(defn kept-drafts!
  "The texts kept across the last reload, once (they are dropped as they are read)."
  []
  (let [s (js/sessionStorage.getItem kept-key)]
    (js/sessionStorage.removeItem kept-key)
    (json-drafts s)))

(def ^:private prepare-ms "How long fetching the new files may take before the page reloads anyway." 20000)

(defn asset-urls
  "The page's own scripts and styles an index.html names (src= and href= on this origin: a path
  starting with one slash), each once, in order."
  [html]
  (->> (re-seq #"(?:src|href)=\"(/[^/\"][^\"]*\.(?:js|css))\"" (str html))
       (map second)
       distinct
       vec))

(defn- fetch-text [url] (.then (js/fetch url #js {:credentials "same-origin"}) #(.text ^js %)))

(defn- prepare!
  "Promise, settled once the new version's index.html and its files are in the browser's cache (or
  after prepare-ms, or on any failure: the reload then simply downloads them)."
  []
  (let [page (-> (fetch-text js/location.pathname)
                 (.then #(js/Promise.allSettled (into-array (map fetch-text (asset-urls %))))))
        limit (js/Promise. (fn [ok] (js/setTimeout ok prepare-ms)))]
    (.catch (js/Promise.race #js [page limit]) (fn [_] nil))))

(defn- mic-held? [] (some? (js/document.querySelector ".mic.starting, .mic.recording, .mic.hearing")))

(rf/reg-fx
 :version/prepare
 ;; The new files into the cache, then the reload proper (which reads the drafts as they are then).
 (fn [_]
   (if (mic-held?)
     (js/setTimeout #(rf/dispatch [:version/reload]) retry-ms)
     (.then (prepare!) #(rf/dispatch [:version/reload-now])))))

(rf/reg-fx
 :version/reload-page
 (fn [drafts]
   (if (mic-held?)
     (js/setTimeout #(rf/dispatch [:version/reload]) retry-ms)
     (do (js/sessionStorage.setItem kept-key (drafts-json drafts))
         (js/location.reload)))))

(rf/reg-event-fx
 :version/check
 (fn [_ _] {:http/get {:url "/hub/me" :on-ok [:version/heard] :on-fail [:version/unheard]}}))

(rf/reg-event-fx
 :version/heard
 (fn [{:keys [db]} [_ me]]
   (when (newer? (:loaded-commit db) (:commit me))
     {:fx [[:dispatch [:version/reload]]]})))

(rf/reg-event-fx :version/unheard (fn [_ _] {}))

(defn- wait-or [db effect]
  (if (reload-blocked? db)
    {:fx [[:dispatch-later {:ms retry-ms :dispatch [:version/reload]}]]}
    effect))

(rf/reg-event-fx
 :version/reload
 (fn [{:keys [db]} _] (wait-or db {:version/prepare true})))

(rf/reg-event-fx
 :version/reload-now
 (fn [{:keys [db]} _] (wait-or db {:version/reload-page (:drafts db)})))
