(ns claudia.node.env
  "The process's environment, read the same way by the hub and every slave program."
  (:require ["fs/promises" :as fsp]
            [clojure.string :as str]))

(defn env
  "The environment variable k, trimmed; default (nil when not given) when it is unset or blank."
  ([k] (env k nil))
  ([k default]
   (let [v (some-> (aget js/process.env k) .trim)]
     (if (seq v) v default))))

(defn service
  "A promise of {:url :token} for a service behind a bearer token, set by <prefix>_URL and
  <prefix>_TOKEN_FILE (or <prefix>_TOKEN): the url without a trailing slash; nil when either is
  missing or the file cannot be read."
  [prefix]
  (let [url (env (str prefix "_URL"))
        file (env (str prefix "_TOKEN_FILE"))]
    (-> (if file
          (-> (fsp/readFile file "utf8") (.then str/trim) (.catch (fn [_] nil)))
          (js/Promise.resolve (env (str prefix "_TOKEN"))))
        (.then (fn [token]
                 (when (and url (not (str/blank? token)))
                   {:url (str/replace url #"/$" "") :token token}))))))
