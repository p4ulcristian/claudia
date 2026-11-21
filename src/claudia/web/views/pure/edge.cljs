(ns claudia.web.views.pure.edge
  "The touch tablet's mic rail (README decisions 40, 73, 92) as data: eight slots of one size, four
  over the mic and four under it, so the mic sits at the rail's middle height. A slot never comes or
  goes: one that cannot act now is passive (greyed), Stop is red while a turn runs.")

(def over
  "The slots over the mic, top to bottom."
  [:search :machines :prompts :settings])

(def under
  "The slots under the mic, top to bottom."
  [:stop :new :prev :next])

(defn- shown-if [ok?] (if ok? :shown :passive))

(defn slot-states
  "Each slot's state now: :shown (it acts), :passive (greyed and disabled: it cannot act now) or
  :red (Stop while the open chat's turn runs). now: running? (the open chat's turn runs), new? (a new chat can be written in),
  folded? (the picture is folded), index and n (the picture shown, of how many)."
  [{:keys [running? new? folded? index n]}]
  (let [i (or index 0) n (or n 0)]
    {:search :shown :machines :shown :prompts :shown :settings :shown
     :stop (if running? :red :passive)
     :new (shown-if new?)
     :prev (shown-if (and (not folded?) (pos? i) (< i n)))
     :next (shown-if (and (not folded?) (< -1 i (dec n))))}))
