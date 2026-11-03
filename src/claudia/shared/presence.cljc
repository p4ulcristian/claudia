(ns claudia.shared.presence
  "At the desktop (README decision 10): while a person works at a Claudia page outside the iPhone app,
  the hub holds their phone alerts. The page (claudia.web.presence) and the hub
  (claudia.hub.presence) both read these.")

(def away-after-ms
  "No mouse, keyboard or touch input on the page for this long = away: held alerts go to the phone."
  (* 2 60000))

(def heartbeat-ms
  "An active page says so this often."
  30000)

(def stale-ms
  "A page silent this long counts as gone."
  (+ heartbeat-ms 15000))
