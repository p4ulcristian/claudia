#!/bin/bash
# The front's port on a Mac reachable by the hub only (deploy/macos/README.md), as deploy/pair.sh's
# nft rule on Linux. The front believes the hub's X-Claudia-Email, so nothing else may reach it:
# not the LAN, not another WireGuard peer, not a program on this Mac.
#
#   sudo deploy/macos/guard.sh            install, or rewrite after FRONT_* changed
#   sudo deploy/macos/guard.sh --remove   take it away
#
# Reads FRONT_HOST (this Mac's tunnel address, the only one the front listens on), FRONT_PORT and
# FRONT_HUB_ADDRESS (the hub's tunnel address, where its calls come from) from the invoking user's
# ~/.config/claudia/env, and writes:
# - /etc/pf.anchors/claudia: pf rules letting FRONT_PORT in only from FRONT_HUB_ADDRESS to
#   FRONT_HOST, never over loopback, and dropping every other packet to that port (IPv4 and IPv6);
# - /Library/LaunchDaemons/claudia.guard.plist: at boot and every minute, loads the rules as the
#   anchor com.apple/claudia (macOS's own /etc/pf.conf evaluates every com.apple/* anchor, so that
#   file is not edited and survives macOS updates) and turns pf on (it is off by default) when it is
#   off or something flushed the rules.
# The WireGuard interface's name (utunN) changes from one connection to the next, so the rules name
# addresses, not the interface: a LAN host that routes the tunnel address to this Mac comes from its
# own address and is dropped; one that forges the hub's never completes the TCP handshake, as the
# answer goes into the tunnel, to the real hub.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
[ "$(uname -s)" = Darwin ] || { echo "guard.sh is for macOS (Linux: deploy/pair.sh)" >&2; exit 1; }
[ "$(id -u)" = 0 ] || { echo "run with sudo" >&2; exit 1; }
anchor=com.apple/claudia
rules=/etc/pf.anchors/claudia
daemon=/Library/LaunchDaemons/claudia.guard.plist

if [ "${1:-}" = --remove ]; then
	launchctl bootout system/claudia.guard 2>/dev/null || true
	pfctl -a "$anchor" -F all 2>/dev/null || true
	rm -f "$daemon" "$rules"
	echo "removed: the front's port is no longer guarded (pf stays on)"
	exit 0
fi

user=${SUDO_USER:?run with sudo from the user Claudia runs as}
home=$(dscl . -read "/Users/$user" NFSHomeDirectory | awk '{ print $2 }')
. "$here/env.sh"
load_env "$home/.config/claudia/env"
ip4='^[0-9]{1,3}(\.[0-9]{1,3}){3}$'
host=${FRONT_HOST:-}
hub=${FRONT_HUB_ADDRESS:-}
port=${FRONT_PORT:-4540}
[[ $host =~ $ip4 ]] || { echo "FRONT_HOST must be this Mac's tunnel address (IPv4), not '$host'" >&2; exit 1; }
[[ $hub =~ $ip4 ]] || { echo "FRONT_HUB_ADDRESS must be the hub's tunnel address (IPv4), not '$hub'" >&2; exit 1; }
[[ $port =~ ^[0-9]{1,5}$ ]] || { echo "FRONT_PORT is not a port: $port" >&2; exit 1; }

new=$(mktemp)
trap 'rm -f "$new"' EXIT
cat >"$new" <<EOF
# Claudia (deploy/macos/guard.sh): port $port only from the hub ($hub) to this Mac's tunnel
# address ($host), never over loopback; every other packet to it is dropped.
pass in quick on ! lo0 inet proto tcp from $hub to $host port $port flags S/SA keep state
block drop in quick proto tcp from any to any port $port
EOF
pfctl -n -a "$anchor" -f "$new" || { echo "pf did not take the rules: nothing changed" >&2; exit 1; }
install -m 0644 -o root -g wheel "$new" "$rules"

# Each minute: macOS's main ruleset loaded (it holds the com.apple/* anchor), this anchor's rules
# there, pf on. Only what is missing is done again.
check="/sbin/pfctl -s rules 2>/dev/null | /usr/bin/grep -q '^anchor \"com.apple/\*\"' || /sbin/pfctl -f /etc/pf.conf 2>/dev/null; \
/sbin/pfctl -a $anchor -s rules 2>/dev/null | /usr/bin/grep -q . || /sbin/pfctl -a $anchor -f $rules 2>/dev/null; \
/sbin/pfctl -s info 2>/dev/null | /usr/bin/grep -q '^Status: Enabled' || /sbin/pfctl -E >/dev/null 2>&1; true"
cat >"$new" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>claudia.guard</string>
	<key>ProgramArguments</key>
	<array>
		<string>/bin/sh</string>
		<string>-c</string>
		<string>$(sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' <<<"$check")</string>
	</array>
	<key>RunAtLoad</key>
	<true/>
	<key>StartInterval</key>
	<integer>60</integer>
</dict>
</plist>
EOF
plutil -lint -s "$new"
install -m 0644 -o root -g wheel "$new" "$daemon"

# The new rules now (a loaded anchor is replaced as a whole), then the daemon from its new plist.
pfctl -a "$anchor" -f "$rules"
launchctl bootout system/claudia.guard 2>/dev/null || true
for _ in $(seq 50); do launchctl print system/claudia.guard >/dev/null 2>&1 || break; sleep 0.2; done
launchctl bootstrap system "$daemon"
sleep 1
pfctl -s info 2>/dev/null | grep '^Status'
echo "rules in $anchor:"
pfctl -a "$anchor" -s rules
