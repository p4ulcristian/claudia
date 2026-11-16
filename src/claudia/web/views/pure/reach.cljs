(ns claudia.web.views.pure.reach
  "Where touches land (README decision 40), as data: which screens get the thumb places, and how far
  the rail scrolls to put the open chat in its middle.")

(def touch-tablet
  "A screen wider than a phone where touch is the main input (an iPad, with or without a trackpad):
  the left edge holds the chats, the right edge the mic and the picture's steps."
  "(pointer: coarse) and (min-width: 680px)")

(defn centre-offset
  "How far (px, down is positive) a scroller must scroll so an item sits in its middle: the item's
  top and height, the scroller's top and visible height, all in the same coordinates."
  [item-top item-h box-top box-h]
  (- (+ item-top (/ item-h 2)) (+ box-top (/ box-h 2))))
