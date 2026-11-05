#!/bin/bash
# Claudia's launchd agents on a Mac (a single-user machine; deploy/macos/README.md):
#
#   deploy/macos/install.sh [--restart-hosts]   by hand
#   deploy/macos/install.sh --update            from deploy/update.sh, in each new release
#
# Writes ~/Library/LaunchAgents/claudia.{host,runner,front,update}.plist for the checkout in
# CLAUDIA_CHECKOUT (~/.config/claudia/env) and loads them into this user's GUI session
# (launchctl bootstrap gui/<uid>): they run while this user is logged in, from login on. Each runs
# deploy/macos/run.sh, which reads the env file at every start, so a changed setting needs only a
# restart, not a new plist.
#
# The front and the runner (re)start every time. The host starts when it is not running and
# restarts only with --restart-hosts (that stops every running chat). The update agent starts on
# CLAUDIA_UPDATE_TRIGGER (the hub's poke) and hourly. A loaded host keeps its plist (a new one would
# take effect only at its next load) unless --restart-hosts; with --update the update agent too (it
# is the one running this). A change to either is then applied by running this by hand (the
# host's with --restart-hosts): it says so.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
mode=hand
restart_hosts=0
for a in "$@"; do
	case $a in
	--update) mode=update ;;
	--restart-hosts) restart_hosts=1 ;;
	*) echo "usage: install.sh [--restart-hosts | --update]" >&2; exit 2 ;;
	esac
done
[ "$(uname -s)" = Darwin ] || { echo "install.sh is for macOS (Linux: the systemd units in deploy/)" >&2; exit 1; }
. "$here/env.sh"
load_env "$HOME/.config/claudia/env"
: "${CLAUDIA_CHECKOUT:?is not set in ~/.config/claudia/env}"
: "${CLAUDIA_UPDATE_TRIGGER:?is not set in ~/.config/claudia/env}"
[ -f "$CLAUDIA_CHECKOUT/out/front.js" ] || { echo "$CLAUDIA_CHECKOUT/out/front.js is missing: install with deploy/update.sh first" >&2; exit 1; }

domain=gui/$(id -u)
agents=$HOME/Library/LaunchAgents
logs=$HOME/Library/Logs/claudia
mkdir -p "$agents" "$logs"
mkdir -p -m 0700 "$(dirname "$CLAUDIA_UPDATE_TRIGGER")"
# The sockets' and the data's folders, this user's only.
for d in "${HOST_SOCKET:-}" "${RUNNER_SOCKET:-}"; do [ -z "$d" ] || mkdir -p -m 0700 "$(dirname "$d")"; done
[ -z "${RUNNER_DATA:-}" ] || mkdir -p -m 0700 "$RUNNER_DATA"

xml() { sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' <<<"$1"; }

# plist <program> <what launchd keeps it running by, as plist keys>: an agent running run.sh <program>.
plist() {
	cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>claudia.$1</string>
	<key>ProgramArguments</key>
	<array>
		<string>/bin/bash</string>
		<string>$(xml "$CLAUDIA_CHECKOUT")/deploy/macos/run.sh</string>
		<string>$1</string>
	</array>
$2
	<key>ThrottleInterval</key>
	<integer>5</integer>
	<key>StandardOutPath</key>
	<string>$(xml "$logs")/$1.log</string>
	<key>StandardErrorPath</key>
	<string>$(xml "$logs")/$1.log</string>
</dict>
</plist>
EOF
}

always='	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<true/>'
on_failure='	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<dict>
		<key>SuccessfulExit</key>
		<false/>
	</dict>'
# Not at load: an update the agent started while one by hand still runs would race it.
on_poke="	<key>WatchPaths</key>
	<array>
		<string>$(xml "$CLAUDIA_UPDATE_TRIGGER")</string>
	</array>
	<key>StartInterval</key>
	<integer>3600</integer>"

loaded() { launchctl print "$domain/claudia.$1" >/dev/null 2>&1; }

# load <program>: bootstraps its agent from its plist; one loaded before is booted out first and
# waited for (up to 10 s), since bootstrap fails while the old one is still going away.
load() {
	local i
	if loaded "$1"; then
		launchctl bootout "$domain/claudia.$1" 2>/dev/null || true
		for i in $(seq 50); do loaded "$1" || break; sleep 0.2; done
	fi
	launchctl enable "$domain/claudia.$1"
	launchctl bootstrap "$domain" "$agents/claudia.$1.plist"
}

# put <program> <keys>: writes its plist; 0 when that changed it, 1 when it was already so.
put() {
	local file=$agents/claudia.$1.plist new
	new=$(plist "$1" "$2")
	[ -f "$file" ] && [ "$(cat "$file")" = "$new" ] && return 1
	printf '%s\n' "$new" >"$file.new"
	plutil -lint -s "$file.new" || { echo "claudia.$1.plist is not a valid plist: not installed" >&2; rm -f "$file.new"; exit 1; }
	mv "$file.new" "$file"
}

# differs <program> <keys>: 0 when its plist on disk is not what this release writes.
differs() { [ "$(cat "$agents/claudia.$1.plist" 2>/dev/null)" != "$(plist "$1" "$2")" ]; }

# The host first (the runner talks to it), then the runner, then the front.
if loaded host && [ $restart_hosts = 0 ]; then
	differs host "$on_failure" && echo "claudia.host's plist changed: deploy/macos/install.sh --restart-hosts applies it (stops chats)"
else
	put host "$on_failure" || true
	load host
fi
for p in runner front; do
	if put "$p" "$always" || ! loaded "$p"; then load "$p"; else launchctl kickstart -k "$domain/claudia.$p"; fi
done
if loaded update && [ $mode = update ]; then
	differs update "$on_poke" && echo "claudia.update's plist changed: deploy/macos/install.sh applies it"
else
	if put update "$on_poke" || ! loaded update; then load update; fi
fi

for p in host runner front update; do
	printf 'claudia.%-7s %s\n' "$p" "$(launchctl print "$domain/claudia.$p" 2>/dev/null |
		awk '$1 == "state" && !s++ { print $3 } $1 == "pid" && !p++ { print "pid " $3 }' | tr '\n' ' ')"
done
true
