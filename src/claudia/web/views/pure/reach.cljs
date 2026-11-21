(ns claudia.web.views.pure.reach
  "Where touches land (README decisions 40, 105), as data: which screens get the edge column, and
  how far the rail scrolls to put the open chat in its middle.")

(def phone
  "A phone-wide screen: three full screens (list, chat, picture), the mic over the text row. Every
  wider screen, touch or mouse, has one layout: the chats' rail on the left edge, the edge column
  on the right (claudia.web.views.thumb-edge); only sizes follow the pointer (reach.css)."
  "(max-width: 679px)")

(defn centre-offset
  "How far (px, down is positive) a scroller must scroll so an item sits in its middle: the item's
  top and height, the scroller's top and visible height, all in the same coordinates."
  [item-top item-h box-top box-h]
  (- (+ item-top (/ item-h 2)) (+ box-top (/ box-h 2))))
