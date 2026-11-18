(ns claudia.web.face.paint
  "Claudia's face from her state to SVG attributes (2026-11-18, the noir look, glasses on): part name
  -> {attribute value} (:html for the lenses). The view writes only what changed. She stays still and
  cool: her picture, the spark and her head never move; only her lenses talk (claudia.web.face.lens),
  and while they show words she leans out of the rail to be read."
  (:require [claudia.web.face.art :as art]
            [claudia.web.face.lens :as lens]))

(defn attrs
  "Every part's attributes for her state at now (calm?: the lenses' rain holds still)."
  [{:keys [pose ticker]} now calm?]
  (let [shown (lens/show pose ticker)]
    {:root {:class (if (= :text (:kind shown)) "face big" "face")}
     :lens {:html (lens/markup art/lenses shown now calm?)}}))
