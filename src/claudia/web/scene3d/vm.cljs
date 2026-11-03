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

(defonce ^:private engine (atom nil))

(defn- read [s] (if (seq s) (js->clj (js/JSON.parse s) :keywordize-keys true) {}))

(defn load-script
  "A promise of {:build (fn [] data) :frame (fn [t step] data) or nil :close (fn [])}; rejects
  with the script's error."
  [source]
  (-> (or @engine (reset! engine (newQuickJSWASMModuleFromVariant variant)))
      (.then
       (fn [^js quickjs]
         (let [^js rt (.newRuntime quickjs)
               deadline (atom js/Infinity)
               _ (do (.setMemoryLimit rt memory)
                     (.setMaxStackSize rt (* 512 1024))
                     (.setInterruptHandler rt (fn [] (> (js/performance.now) @deadline))))
               ^js ctx (.newContext rt)
               failure (fn [^js err]
                         (let [e (.dump ctx err)]
                           (.dispose err)
                           (js/Error. (if (and e (object? e))
                                        (str (or (.-name e) "Error") ": " (or (.-message e) ""))
                                        (str e)))))
               run (fn [code ms]
                     (reset! deadline (+ (js/performance.now) ms))
                     (let [^js r (.evalCode ctx code "scene.js")]
                       (reset! deadline js/Infinity)
                       (when (.-error r) (throw (failure (.-error r))))
                       (.dispose (.-value r))))
               fn-of (fn [name]
                       (let [^js h (.getProp ctx (.-global ctx) name)]
                         (if (= "function" (.typeof ctx h)) h (do (.dispose h) nil))))
               call (fn [h ms & args]
                      (let [as (map #(.newNumber ctx %) args)]
                        (reset! deadline (+ (js/performance.now) ms))
                        (let [^js r (apply js-invoke ctx "callFunction" h (.-undefined ctx) as)]
                          (reset! deadline js/Infinity)
                          (doseq [^js a as] (.dispose a))
                          (when (.-error r) (throw (failure (.-error r))))
                          (let [s (.getString ctx (.-value r))]
                            (.dispose (.-value r))
                            (read s)))))
               close (fn [] (.dispose ctx) (.dispose rt))]
           (try
             (run source build-ms)
             (run "globalThis.__build = typeof build === 'function' ? () => JSON.stringify(build() || {}) : null;
                   globalThis.__frame = typeof frame === 'function' ? (t, s) => JSON.stringify(frame(t, s) || {}) : null;"
                  build-ms)
             (catch :default e (close) (throw e)))
           (let [b (fn-of "__build") f (fn-of "__frame")]
             {:build (fn [] (if b (call b build-ms) {}))
              :frame (when f (fn [t step] (call f frame-ms t step)))
              :close (fn []
                       (some-> ^js b .dispose)
                       (some-> ^js f .dispose)
                       (close))}))))))
