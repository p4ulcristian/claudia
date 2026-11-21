(ns claudia.web.views.pure.edge
  "The edge column of every screen wider than a phone (README decisions 40, 73, 92, 109, 117) as
  data: nine slots of one size, five over the rail's middle and four under it. A slot never comes or
  goes: one that cannot act now is passive (greyed), Stop is red while a turn runs; Fork and Archive
  (the open chat; Archive asked first, Unarchive for an archived one) sit under New chat. Search,
  System, Files, Profiles (the :prompts sheet) and Settings are toggles: lit (:on) while their sheet
  is the top one, and a press then closes it.")

(def over
  "The slots over the mic, top to bottom."
  [:search :system :files :prompts :settings])

(def under
  "The slots under the mic, top to bottom: Fork and Archive under New chat."
  [:stop :new :fork :archive])

(def sheet-of
  "The slots that open a sheet of the right half, and the sheet each opens."
  {:search :search :system :system :files :files :prompts :prompts :settings :settings})

(defn- shown-if [ok?] (if ok? :shown :passive))

(defn- sheet-state "A sheet slot's state: :on while its sheet is the top one (view), else :shown." [id view]
  (if (= view (sheet-of id)) :on :shown))

(defn slot-states
  "Each slot's state now: :shown (it acts), :on (a sheet slot whose sheet is on top: a press closes
  it), :passive (greyed and disabled: it cannot act now) or :red (Stop while the open chat's turn
  runs, Fork while the server makes a fork). now: running? (the open chat's turn runs), new? (a new
  chat can be written in), view (the right half's top sheet, :picture for the Drawing board),
  read-only? (another person's chats show: no File Browser), archivable? (a started chat of the
  person's own is open: Fork, and Archive or Unarchive for an archived one), forking? (the server
  is making this chat's fork)."
  [{:keys [running? new? view read-only? archivable? forking?]}]
  {:search (sheet-state :search view) :system (sheet-state :system view)
   :files (if read-only? :passive (sheet-state :files view))
   :prompts (sheet-state :prompts view) :settings (sheet-state :settings view)
   :stop (if running? :red :passive)
   :new (shown-if new?)
   :fork (cond forking? :on archivable? :shown :else :passive)
   :archive (shown-if archivable?)})
