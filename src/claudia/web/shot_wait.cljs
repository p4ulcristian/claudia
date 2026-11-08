(ns claudia.web.shot-wait
  "What a screenshot of the picture waits for: claudia.slave.render shoots once
  window.__scene3dLoading is 0. A 3D scene holds it from the moment its module starts loading
  (module), then from its start until its first build (the scene), and while its models and code
  load. Holds and releases always come in pairs.")

(defonce ^:private module-held (atom false))

(defn hold! [] (set! (.-__scene3dLoading js/window) (inc (or (.-__scene3dLoading js/window) 0))))

(defn release! [] (set! (.-__scene3dLoading js/window) (max 0 (dec (or (.-__scene3dLoading js/window) 0)))))

(defn hold-module!
  "The 3D module starts loading: held until a scene view has mounted (release-module!)."
  []
  (when (compare-and-set! module-held false true) (hold!)))

(defn release-module! [] (when (compare-and-set! module-held true false) (release!)))
