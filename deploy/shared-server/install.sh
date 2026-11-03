#!/usr/bin/env bash
# Installs Claudia beta on a shared server from a built checkout (out/ and node_modules made by
# `npm ci && npx shadow-cljs release host runner front`, here or on another machine):
#   sudo deploy/shared-server/install.sh <checkout> <profile>...
# Code goes to /srv/claudia-beta (root-owned), units to /etc/systemd/system. Settings stay in
# /etc/claudia-beta (env, profiles, profile-<p>.env), written by hand, never from the repo.
# Restarts the front and the runners; hosts only when --restart-hosts is given (that stops chats).
# deploy/update.sh --shared runs this from each signed beta release (claudia-beta-update.path/.timer).
set -euo pipefail
src=${1:?checkout}; shift
restart_hosts=0; profiles=()
for a in "$@"; do [ "$a" = --restart-hosts ] && restart_hosts=1 || profiles+=("$a"); done
[ -f "$src/out/runner.js" ] || { echo "$src/out/runner.js missing: build first" >&2; exit 1; }
[ -f /etc/claudia-beta/env ] && [ -f /etc/claudia-beta/profiles ] || { echo "write /etc/claudia-beta/env and profiles first" >&2; exit 1; }
new=/srv/claudia-beta.new
rm -rf "$new"; mkdir -p "$new"
cp -r "$src/out" "$src/node_modules" "$src/package.json" "$src/deploy" "$new/"
# A release (deploy/update.sh) says its commit; a checkout gets it from git.
if [ -f "$src/commit.txt" ]; then cp "$src/commit.txt" "$new/"
else git -C "$src" rev-parse HEAD > "$new/commit.txt" 2>/dev/null || true; fi
chown -R root:root "$new"; chmod -R go-w "$new"
rm -rf /srv/claudia-beta.old; [ -d /srv/claudia-beta ] && mv /srv/claudia-beta /srv/claudia-beta.old
mv "$new" /srv/claudia-beta
install -m 0644 "$(dirname "$0")"/claudia-beta-*.service "$(dirname "$0")"/claudia-beta-update.{path,timer} /etc/systemd/system/
systemctl daemon-reload
# Follows the beta branch by itself: the hub's poke and an hourly check (deploy/update.sh).
systemctl enable --now claudia-beta-update.path claudia-beta-update.timer >/dev/null
systemctl enable --now claudia-beta-front.service >/dev/null
systemctl restart claudia-beta-front.service
for p in "${profiles[@]}"; do
	systemctl enable claudia-beta-host@"$p".service claudia-beta-runner@"$p".service >/dev/null
	if [ $restart_hosts = 1 ]; then systemctl restart claudia-beta-host@"$p".service; else systemctl start claudia-beta-host@"$p".service; fi
	systemctl restart claudia-beta-runner@"$p".service
done
systemctl --no-pager --lines=0 status claudia-beta-front.service "${profiles[@]/#/claudia-beta-runner@}" | grep -E '●|Active:' || true
