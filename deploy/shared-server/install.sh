#!/usr/bin/env bash
# Installs Claudia beta on a shared server from a built checkout (out/ and node_modules made by
# `npm ci && npx shadow-cljs release host runner front`, here or on another machine):
#   sudo deploy/shared-server/install.sh <checkout> <profile>...
# Code goes to /srv/claudia-beta (root-owned), units to /etc/systemd/system. Settings stay in
# /etc/claudia-beta (env, profiles, profile-<p>.env), written by hand, never from the repo.
# Restarts the front and the runners; hosts only when --restart-hosts is given (that stops chats).
set -euo pipefail
src=${1:?checkout}; shift
restart_hosts=0; profiles=()
for a in "$@"; do [ "$a" = --restart-hosts ] && restart_hosts=1 || profiles+=("$a"); done
[ -f "$src/out/runner.js" ] || { echo "$src/out/runner.js missing: build first" >&2; exit 1; }
[ -f /etc/claudia-beta/env ] && [ -f /etc/claudia-beta/profiles ] || { echo "write /etc/claudia-beta/env and profiles first" >&2; exit 1; }
new=/srv/claudia-beta.new
rm -rf "$new"; mkdir -p "$new"
cp -r "$src/out" "$src/node_modules" "$src/package.json" "$new/"
chown -R root:root "$new"; chmod -R go-w "$new"
rm -rf /srv/claudia-beta.old; [ -d /srv/claudia-beta ] && mv /srv/claudia-beta /srv/claudia-beta.old
mv "$new" /srv/claudia-beta
install -m 0644 "$(dirname "$0")"/claudia-beta-*.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now claudia-beta-front.service >/dev/null
systemctl restart claudia-beta-front.service
for p in "${profiles[@]}"; do
	systemctl enable claudia-beta-host@"$p".service claudia-beta-runner@"$p".service >/dev/null
	if [ $restart_hosts = 1 ]; then systemctl restart claudia-beta-host@"$p".service; else systemctl start claudia-beta-host@"$p".service; fi
	systemctl restart claudia-beta-runner@"$p".service
done
systemctl --no-pager --lines=0 status claudia-beta-front.service "${profiles[@]/#/claudia-beta-runner@}" | grep -E '●|Active:' || true
