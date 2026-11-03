(ns claudia.web.chart
  "The chart form's Vega-Lite, from npm: the :chart module, loaded the first time a chart is drawn
  (claudia.web.views.forms). Vega's expressions run in its interpreter (ast), never as generated
  code, so the page's policy needs no eval."
  (:require ["vega-embed" :default vega-embed]))

(defn embed
  "Promise of the chart's result (its .view) once the Vega-Lite spec (JS) is drawn into el as SVG."
  [el spec]
  (vega-embed el spec #js {:actions false :renderer "svg" :ast true}))
