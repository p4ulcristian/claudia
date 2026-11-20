(ns claudia.web.login
  "Whether the gate still lets this page in. The gate checks the login on every HTTP request and
  sends a lost one to its sign-in page with a redirect; a socket is checked only when it opens, so
  messages keep going after a logout while every fetch fails. The page's fetches (claudia.web.api)
  take no redirects: one that comes back means the login is gone, and lost! says so. The page also
  asks /hub/me (the version check) every minute while it shows and each time it comes back to the
  front, so a logout shows even when nothing else is fetched. While the login is gone a cover lies
  over the whole app (claudia.web.views.login) until the page reloads through the gate's sign-in."
  (:require [re-frame.core :as rf]))

(def check-ms "How often a showing page asks whether its login still holds." 60000)

(defn redirected?
  "Whether a fetch answer's type (taken with redirect: manual) is the gate sending the page to sign
  in: the page's own API never redirects a fetch."
  [response-type]
  (= "opaqueredirect" response-type))

(defn lost! [] (rf/dispatch [:login/lost]))

(rf/reg-event-db :login/lost (fn [db _] (assoc db :logged-out true)))

(rf/reg-sub :logged-out? (fn [db _] (boolean (:logged-out db))))

(rf/reg-event-fx
 :login/sign-in
 ;; A reload goes through the gate, which sends the page to sign in and back here afterwards; the
 ;; drafts are kept over it like an update's (claudia.web.updates), and unsent clips stay on the
 ;; device (claudia.web.clip-store). Files waiting to go can't go anyway: the reload doesn't wait.
 (fn [{:keys [db]} _] {:version/reload-page (:drafts db)}))

(defn- check! []
  (when (= "visible" js/document.visibilityState)
    (rf/dispatch [:version/check])))

(defn start!
  "Asks every check-ms while the page shows, and when it comes back to the front."
  []
  (js/setInterval check! check-ms)
  (js/document.addEventListener "visibilitychange" check!))
