(ns claudia.web.views.pure.edge
  "The edge column of every screen wider than a phone (README decisions 40, 73, 92, 105) as data: nine slots of one size, four
  over the rail's middle and five under it. A slot never comes or
  goes: one that cannot act now is passive (greyed), Stop is red while a turn runs; Archive (the open
  chat, asked first; Unarchive for an archived one) sits under New chat. Search, System,
  Prompts and Settings are toggles: lit (:on) while their sheet is the top one, and a press then
  closes it.")

(def over
  "The slots over the mic, top to bottom."
  [:search :system :prompts :settings])

(def under
  "The slots under the mic, top to bottom: Archive under New chat."
  [:stop :new :archive :prev :next])

(def sheet-of
  "The slots that open a sheet of the right half, and the sheet each opens."
  {:search :search :system :system :prompts :prompts :settings :settings})

(defn- shown-if [ok?] (if ok? :shown :passive))

(defn- sheet-state "A sheet slot's state: :on while its sheet is the top one (view), else :shown." [id view]
  (if (= view (sheet-of id)) :on :shown))

(defn slot-states
  "Each slot's state now: :shown (it acts), :on (a sheet slot whose sheet is on top: a press closes
  it), :passive (greyed and disabled: it cannot act now) or :red (Stop while the open chat's turn
  runs). now: running? (the open chat's turn runs), new? (a new chat can be written in), folded?
  (the picture is folded), index and n (the picture shown, of how many), view (the right half's top
  sheet, :picture for the Drawing board), archivable? (a started chat of the person's own is open:
  Archive, or Unarchive for an archived one)."
  [{:keys [running? new? folded? index n view archivable?]}]
  (let [i (or index 0) n (or n 0)]
    {:search (sheet-state :search view) :system (sheet-state :system view)
     :prompts (sheet-state :prompts view) :settings (sheet-state :settings view)
     :stop (if running? :red :passive)
     :new (shown-if new?)
     :archive (shown-if archivable?)
     :prev (shown-if (and (not folded?) (pos? i) (< i n)))
     :next (shown-if (and (not folded?) (< -1 i (dec n))))}))
