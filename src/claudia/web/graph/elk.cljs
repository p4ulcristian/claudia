(ns claudia.web.graph.elk
  "ELK, the graph form's layout engine: the :elk module, loaded the first time a graph is laid out
  (claudia.web.views.graph)."
  (:require ["elkjs/lib/elk.bundled.js" :as ELK]))

(defonce ^:private elk (ELK.))

(defn layout
  "A promise of ELK's layout of graph (ELK's JSON graph, as JS)."
  [graph]
  (.layout elk graph))
