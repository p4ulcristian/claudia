(ns claudia.node.fs
  "File system questions the hub and the slave programs ask."
  (:require ["node:fs" :as fs]))

(defn dir?
  "Whether p is a folder (false when it is missing or unreadable)."
  [p]
  (try (.isDirectory (fs/statSync p)) (catch :default _ false)))
