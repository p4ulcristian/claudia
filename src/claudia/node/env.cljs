(ns claudia.node.env
  "The process's environment, read the same way by the hub and every slave program.")

(defn env
  "The environment variable k, trimmed; default (nil when not given) when it is unset or blank."
  ([k] (env k nil))
  ([k default]
   (let [v (some-> (aget js/process.env k) .trim)]
     (if (seq v) v default))))
