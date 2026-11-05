(ns claudia.web.scene3d.icons
  "The 3D view's icons: small stroked paths in a 20 by 20 box.")

(def ^:private paths
  {:play "M6.5 4.5v11l9-5.5z"
   :pause "M7 4.5v11M13 4.5v11"
   :prev "M5 4.5v11M15 4.5l-8 5.5 8 5.5z"
   :next "M15 4.5v11M5 4.5l8 5.5-8 5.5z"
   :volume "M3 8v4h3l4 3.5v-11L6 8zM13 7.5a3.5 3.5 0 0 1 0 5M15.2 5.3a6.5 6.5 0 0 1 0 9.4"
   :mute "M3 8v4h3l4 3.5v-11L6 8zM13.5 8l4 4M17.5 8l-4 4"
   :reset "M4.5 10a5.5 5.5 0 1 0 1.8-4.1M4.5 3.5v3.7h3.7"
   :warn "M10 3.5l7 12.2H3zM10 8.3v3.2M10 13.6v.4"
   :check "M4 10.5l4 4 8-9"
   :close "M5 5l10 10M15 5L5 15"
   :full "M3.5 8V3.5H8M12 3.5h4.5V8M16.5 12v4.5H12M8 16.5H3.5V12"
   :exit "M8 3.5V8H3.5M16.5 8H12V3.5M12 16.5V12h4.5M3.5 12H8v4.5"
   :film "M3.5 5.5h13v9h-13zM7 5.5v9M13 5.5v9M3.5 8.5H7M3.5 11.5H7M13 8.5h3.5M13 11.5h3.5"
   :download "M10 3.5v9M6 8.5l4 4 4-4M4 16h12"
   :wait "M10 3.5a6.5 6.5 0 1 0 6.5 6.5"})

(defn icon
  "An icon of the 3D view's bars (a key of paths), drawn in the text's colour."
  [k]
  [:svg.s3-ic {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d (paths k)}]])
