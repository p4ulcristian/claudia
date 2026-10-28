#!/usr/bin/env bash
# A profile for one more person on this server (run as root): their own Linux user, session host and
# runner. The hub's Access page ticks them for this server; until this has run, their tick shows
# "needs a profile".
#
#   sudo deploy/add-profile.sh <profile> <sign-in email>
set -euo pipefail
name="${1:?usage: add-profile.sh <profile> <sign-in email>}"
email="${2:?usage: add-profile.sh <profile> <sign-in email>}"
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }
[[ "$name" =~ ^[a-z][a-z0-9-]{0,30}$ ]] || { echo "profile: lower-case letters, digits and dashes, starting with a letter" >&2; exit 1; }
[[ "$email" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]] || { echo "not an email address: $email" >&2; exit 1; }
email=$(printf '%s' "$email" | tr 'A-Z' 'a-z')
if grep -Eq "^[[:space:]]*$name[[:space:]]" /etc/claudia/profiles; then echo "$name is already a profile here" >&2; exit 1; fi
if awk '{print tolower($2)}' /etc/claudia/profiles | grep -qxF "$email"; then echo "$email already has a profile here" >&2; exit 1; fi
echo "$name $email" >> /etc/claudia/profiles
# Makes the user, its home and units (the code is copied onto itself: nothing else changes).
exec /srv/claudia/deploy/install.sh /srv/claudia
