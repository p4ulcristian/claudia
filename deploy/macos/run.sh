#!/bin/bash
# One Claudia program under launchd on a Mac (the agents deploy/macos/install.sh writes):
#
#   run.sh host | runner | front | update
#
# Takes the settings from ~/.config/claudia/env (env.sh, as systemd's EnvironmentFile= on Linux),
# then runs the program from CLAUDIA_CHECKOUT: node out/<program>.js, or deploy/update.sh for
# `update`. launchd restarts the front and the runner whenever they stop and the host when it fails
# (as Restart=always and Restart=on-failure do), so a front that cannot listen yet (the tunnel is not
# up) is tried again every few seconds.
set -euo pipefail
what=${1:?host, runner, front or update}
here=$(cd "$(dirname "$0")" && pwd)
# launchd's own PATH has no Homebrew; the env file's PATH replaces this.
export PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
. "$here/env.sh"
load_env "$HOME/.config/claudia/env"
: "${CLAUDIA_CHECKOUT:?is not set in ~/.config/claudia/env}"

# This program's log (the agent's stdout and stderr) is cut to its last 2 MB when it passed 20 MB:
# it is opened for appending, so the program goes on writing at the new end.
log=$HOME/Library/Logs/claudia/$what.log
if [ -f "$log" ] && [ "$(wc -c <"$log" | tr -d " ")" -gt 20000000 ]; then
	tail -c 2000000 "$log" >"$log.cut" && cat "$log.cut" >"$log"
	rm -f "$log.cut"
fi

case $what in
host | runner | front)
	exec node "$CLAUDIA_CHECKOUT/out/$what.js"
	;;
update)
	# Started by launchd when CLAUDIA_UPDATE_TRIGGER changes (the hub's poke) and hourly. A poke that
	# comes while an update runs writes the trigger again; it is taken here right after, as
	# systemd's PathExists= does (a few rounds at most).
	for _ in 1 2 3 4 5; do
		bash "$CLAUDIA_CHECKOUT/deploy/update.sh" || echo "update.sh failed ($?)" >&2
		[ -n "${CLAUDIA_UPDATE_TRIGGER:-}" ] && [ -e "$CLAUDIA_UPDATE_TRIGGER" ] || break
	done
	;;
*)
	echo "run.sh: $what is not host, runner, front or update" >&2
	exit 2
	;;
esac
