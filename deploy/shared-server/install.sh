#!/usr/bin/env bash
# Installs Claudia on a shared server from a built checkout (out/ and node_modules made by
# `npm ci && npx shadow-cljs release host runner front`, here or on another machine; add
# `web && npm run build:web-files` for the screenshots Claude gets back from each draw):
#   sudo deploy/shared-server/install.sh <checkout> <profile>...
# Code goes to /srv/claudia (root-owned), units to /etc/systemd/system. Settings stay in
# /etc/claudia (env, profiles, profile-<p>.env), written by hand, never from the repo.
# Restarts the front and the runners; hosts only when --restart-hosts is given (that stops chats).
# A new Agent SDK needs no host restart: each session a host starts loads the newest (README decision 80).
# deploy/update.sh --shared runs this from each signed release (claudia-update.path/.timer).
set -euo pipefail
src=${1:?checkout}; shift
restart_hosts=0; profiles=()
for a in "$@"; do [ "$a" = --restart-hosts ] && restart_hosts=1 || profiles+=("$a"); done
[ -f "$src/out/runner.js" ] || { echo "$src/out/runner.js missing: build first" >&2; exit 1; }
[ -f /etc/claudia/env ] && [ -f /etc/claudia/profiles ] || { echo "write /etc/claudia/env and profiles first" >&2; exit 1; }
new=/srv/claudia.new
rm -rf "$new"; mkdir -p "$new"
cp -r "$src/out" "$src/node_modules" "$src/package.json" "$src/deploy" "$new/"
# A release (deploy/update.sh) says its commit; a checkout gets it from git.
if [ -f "$src/commit.txt" ]; then cp "$src/commit.txt" "$new/"
else git -C "$src" rev-parse HEAD > "$new/commit.txt" 2>/dev/null || true; fi
chown -R root:root "$new"; chmod -R go-w "$new"
rm -rf /srv/claudia.old; [ -d /srv/claudia ] && mv /srv/claudia /srv/claudia.old
mv "$new" /srv/claudia
# The release's Agent SDK into /srv/claudia.sdk, where the hosts load each new session's from
# (README decision 80): a host never restarts for a new SDK.
bash /srv/claudia/deploy/sdk-store.sh /srv/claudia
# The saved Claude tokens (<token file>.d) and each person's pick (<token file>.picks.json): the front
# (user claudia) writes them, the runners (group claudia-run) read them.
getent group claudia-run >/dev/null || groupadd --system claudia-run
token_file=$(sed -n 's/^CLAUDIA_TOKEN_FILE=//p' /etc/claudia/env | tail -1)
if [ -n "$token_file" ] && [ ! -d "$(dirname "$token_file")" ]; then
	install -d -o claudia -g claudia-run -m 2750 "$(dirname "$token_file")"
fi
install -m 0644 "$(dirname "$0")"/claudia-*.service "$(dirname "$0")"/claudia-update.{path,timer} /etc/systemd/system/
# profile_user <p>: the Linux user profile <p> runs as: user=<name> on its line of
# /etc/claudia/profiles, else claudia-<p>.
profile_user() {
	local u
	u=$(sed 's/#.*//' /etc/claudia/profiles | awk -v p="$1" '$1 == p { for (i = 4; i <= NF; i++) if ($i ~ /^user=./) { print substr($i, 6); exit } }')
	echo "${u:-claudia-$1}"
}
# user_dropins <p> <user> <home>: the host and the runner of <p> run as <user>, with their data and
# chats' folder in <home>. Written on every install, so a changed line takes effect.
user_dropins() {
	install -d /etc/systemd/system/claudia-host@"$1".service.d /etc/systemd/system/claudia-runner@"$1".service.d
	printf '[Service]\nUser=%s\n' "$2" >/etc/systemd/system/claudia-host@"$1".service.d/user.conf
	printf '[Service]\nUser=%s\nEnvironment=RUNNER_DATA=%s/.local/share/claudia CLAUDIA_CWD=%s/Work\n' \
		"$2" "$3" "$3" >/etc/systemd/system/claudia-runner@"$1".service.d/user.conf
}
for p in "${profiles[@]}"; do
	u=$(profile_user "$p")
	home=$(getent passwd "$u" | cut -d: -f6)
	[ -n "$home" ] || { echo "profile $p: no Linux user $u" >&2; exit 1; }
	usermod -aG claudia-run "$u"
	# The chats' folder (CLAUDIA_CWD): Claude Code cannot start in a folder that is not there.
	[ -d "$home/Work" ] || install -d -m 700 -o "$u" -g "$(id -gn "$u")" "$home/Work"
	user_dropins "$p" "$u" "$home"
done
systemctl daemon-reload
# Follows the main branch by itself: the hub's poke and an hourly check (deploy/update.sh).
systemctl enable --now claudia-update.path claudia-update.timer >/dev/null
systemctl enable --now claudia-front.service >/dev/null
systemctl restart claudia-front.service
for p in "${profiles[@]}"; do
	systemctl enable claudia-host@"$p".service claudia-runner@"$p".service >/dev/null
	if [ $restart_hosts = 1 ]; then systemctl restart claudia-host@"$p".service; else systemctl start claudia-host@"$p".service; fi
	systemctl restart claudia-runner@"$p".service
done
systemctl --no-pager --lines=0 status claudia-front.service "${profiles[@]/#/claudia-runner@}" | grep -E '●|Active:' || true
