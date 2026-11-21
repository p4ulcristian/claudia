(ns claudia.web.views.icons
  "Icons for buttons that open a view of the right half from two places: the touch tablet's rail
  (claudia.web.views.thumb-edge) and the profile menu (claudia.web.views.menu). The strokes come
  from each place's CSS (fill none, stroke currentColor).")

(def words-icon
  "My words: a book."
  [:svg {:viewBox "0 0 24 24" :aria-hidden true}
   [:path {:d "M5 4.5A1.5 1.5 0 0 1 6.5 3H19v15H6.5A1.5 1.5 0 0 0 5 19.5z"}]
   [:path {:d "M5 19.5A1.5 1.5 0 0 0 6.5 21H19v-3"}] [:path {:d "M9 7.5h6"}]])

(def prompts-icon
  "Prompts: a speech bubble with two lines of text."
  [:svg {:viewBox "0 0 24 24" :aria-hidden true}
   [:path {:d "M4 5.5A1.5 1.5 0 0 1 5.5 4h13A1.5 1.5 0 0 1 20 5.5v9a1.5 1.5 0 0 1-1.5 1.5H10l-4.5 4v-4A1.5 1.5 0 0 1 4 14.5z"}]
   [:path {:d "M8 8.5h8M8 11.5h5"}]])
