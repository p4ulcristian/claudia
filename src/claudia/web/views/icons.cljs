(ns claudia.web.views.icons
  "Icons for buttons that open a view of the right half from two places: the edge column
  (claudia.web.views.thumb-edge) and the profile menu (claudia.web.views.menu). The strokes come
  from each place's CSS (fill none, stroke currentColor).")

(def settings-icon
  "Settings: a gear."
  [:svg {:viewBox "0 0 20 20" :aria-hidden true}
   [:circle {:cx 10 :cy 10 :r 2.6}]
   [:path {:d "M10 2.5v2.2M10 15.3v2.2M2.5 10h2.2M15.3 10h2.2M4.7 4.7l1.6 1.6M13.7 13.7l1.6 1.6M4.7 15.3l1.6-1.6M13.7 6.3l1.6-1.6"}]
   [:circle {:cx 10 :cy 10 :r 5.4}]])

(def prompts-icon
  "Prompts: a speech bubble with two lines of text."
  [:svg {:viewBox "0 0 24 24" :aria-hidden true}
   [:path {:d "M4 5.5A1.5 1.5 0 0 1 5.5 4h13A1.5 1.5 0 0 1 20 5.5v9a1.5 1.5 0 0 1-1.5 1.5H10l-4.5 4v-4A1.5 1.5 0 0 1 4 14.5z"}]
   [:path {:d "M8 8.5h8M8 11.5h5"}]])
