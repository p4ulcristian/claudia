(ns claudia.web.scene3d.vm
  "Runs a scene's `script` in QuickJS, a separate JavaScript engine compiled to WebAssembly: the script
  cannot see window, the DOM, cookies or the network, runs out of time rather than freezing the tab,
  and exchanges only JSON with the page. It defines
    build()        -> { parts?, arrows?, allow? }  parts and arrows made by code (loops, maths)
    frame(t, step) -> { [partId]: { pos?, rot?, size?, color?, opacity?, from?, to?, pose? } }
  t = seconds, step = the story's current step (-1 before it starts, or without a story)."
  (:require ["@jitl/quickjs-singlefile-cjs-release-sync" :default variant]
            ["quickjs-emscripten-core" :refer [newQuickJSWASMModuleFromVariant]]))

(def ^:private build-ms 500)
(def ^:private frame-ms 40)
(def ^:private memory (* 32 1024 1024))

;; build() and frame() wrapped so they answer JSON text.
(def ^:private wrappers
  "globalThis.__build = typeof build === 'function' ? () => JSON.stringify(build() || {}) : null;
                   globalThis.__frame = typeof frame === 'function' ? (t, s) => JSON.stringify(frame(t, s) || {}) : null;")

(defonce ^:private engine (atom nil))

(defn- read [s] (if (seq s) (js->clj (js/JSON.parse s) :keywordize-keys true) {}))

(defn- sandbox
  "A runtime and context with the memory, stack and time limits. -> {:rt :ctx :deadline}."
  [^js quickjs]
  (let [^js rt (.newRuntime quickjs)
        deadline (atom js/Infinity)]
    (.setMemoryLimit rt memory)
    (.setMaxStackSize rt (* 512 1024))
    (.setInterruptHandler rt (fn [] (> (js/performance.now) @deadline)))
    {:rt rt :ctx (.newContext rt) :deadline deadline}))

(defn- failure "The script's error as a JS Error." [^js ctx ^js err]
  (let [e (.dump ctx err)]
    (.dispose err)
    (js/Error. (if (and e (object? e))
                 (str (or (.-name e) "Error") ": " (or (.-message e) ""))
                 (str e)))))

(defn- eval! "Runs code with ms to finish; throws the script's error." [{:keys [^js ctx deadline]} code ms]
  (reset! deadline (+ (js/performance.now) ms))
  (let [^js r (.evalCode ctx code "scene.js")]
    (reset! deadline js/Infinity)
    (when (.-error r) (throw (failure ctx (.-error r))))
    (.dispose (.-value r))))

(defn- fn-of "A global function of the script (a handle), or nil." [{:keys [^js ctx]} fn-name]
  (let [^js h (.getProp ctx (.-global ctx) fn-name)]
    (if (= "function" (.typeof ctx h)) h (do (.dispose h) nil))))

(defn- call
  "Calls a script function with number args and ms to finish. -> its JSON answer as data."
  [{:keys [^js ctx deadline]} h ms & args]
  (let [as (map #(.newNumber ctx %) args)]
    (reset! deadline (+ (js/performance.now) ms))
    (let [^js r (apply js-invoke ctx "callFunction" h (.-undefined ctx) as)]
      (reset! deadline js/Infinity)
      (doseq [^js a as] (.dispose a))
      (when (.-error r) (throw (failure ctx (.-error r))))
      (let [s (.getString ctx (.-value r))]
        (.dispose (.-value r))
        (read s)))))

(defn- close! [{:keys [^js ctx ^js rt]}] (.dispose ctx) (.dispose rt))

(defn- start-script
  "Runs the source and finds build() and frame(). -> the script's api."
  [quickjs source]
  (let [box (sandbox quickjs)]
    (try
      (eval! box source build-ms)
      (eval! box wrappers build-ms)
      (catch :default e (close! box) (throw e)))
    (let [b (fn-of box "__build") f (fn-of box "__frame")]
      {:build (fn [] (if b (call box b build-ms) {}))
       :frame (when f (fn [t step] (call box f frame-ms t step)))
       :close (fn []
                (some-> ^js b .dispose)
                (some-> ^js f .dispose)
                (close! box))})))

(defn load-script
  "A promise of {:build (fn [] data) :frame (fn [t step] data) or nil :close (fn [])}; rejects
  with the script's error."
  [source]
  (-> (or @engine (reset! engine (newQuickJSWASMModuleFromVariant variant)))
      (.then (fn [quickjs] (start-script quickjs source)))))
