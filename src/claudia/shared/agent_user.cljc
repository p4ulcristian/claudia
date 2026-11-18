(ns claudia.shared.agent-user
  "Agent user names (README decisions 51 and 53): on every Linux server a profile's chats run as
  `<profile>-claudia-agent`, and an SSH certificate names exactly that user. The hub (which signs)
  and the runner (which asks) read a name the same way.")

(def suffix "What every agent user's name ends in." "-claudia-agent")

(def profile-re
  "A profile name an agent user can be made of: lower-case letters, digits and inner dashes,
  starting with a letter, at most 18 characters (Linux user names stop at 32, the suffix takes 14)."
  #"^[a-z](?:[a-z0-9-]{0,16}[a-z0-9])?$")

(defn profile-name?
  "Whether s may be a profile's name (and so part of an agent user's)."
  [s]
  (boolean (and (string? s) (re-matches profile-re s))))

(defn profile-of
  "The profile part of an agent user's name (`paul-claudia-agent` -> `paul`), or nil when user is
  not one."
  [user]
  (when (string? user)
    (let [n (- (count user) (count suffix))]
      (when (and (pos? n) (= suffix (subs user n)))
        (let [profile (subs user 0 n)]
          (when (profile-name? profile) profile))))))

(defn agent-user?
  "Whether user is an agent user's name."
  [user]
  (some? (profile-of user)))

(defn of-profile
  "The agent user of a profile (`paul` -> `paul-claudia-agent`)."
  [profile]
  (str profile suffix))
