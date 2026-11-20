#!/usr/bin/env bash
# Keeps each release's Agent SDK where the session host loads it from (README decision 80), and the
# channel's newer ones (decision 81):
#
#   deploy/sdk-store.sh <release folder> [<node_modules folder>]
#
# The host loads a session's SDK when the session starts, from <release>.sdk/<version> when that is
# there (claudia.slave.sdk-load), and keeps running on it while later releases come. A release's own
# folder is deleted two updates later (<release>.old, /srv/claudia.old), so the SDK a session started
# from must live elsewhere: here. <release>.sdk/<version>/node_modules is the release's node_modules
# as hard links (cp -al: Linux, and macOS, whose cp takes -l too), else APFS clones (cp -c), else a
# plain copy, so it costs no space where the file system allows. With a node_modules folder (a channel tarball's, verified by deploy/update.sh),
# that folder is moved in instead, under the version its Agent SDK package says; a new session takes
# the newest stored version not marked broken (<release>.sdk/broken, one version per line), with no
# restart. Then old versions go: the three stored last that are not marked stay, and any other one
# stays while a process runs from it (a session's Claude Code binary: its command line names its path).
#
# Run by deploy/update.sh (single-user Linux and macOS, and for the channel on every kind) and
# deploy/shared-server/install.sh after the release is in place, before the runners restart. Runs on
# Linux (bash 5, GNU) and macOS (bash 3.2).
set -euo pipefail
# As the host names it (path.resolve: no trailing slash, links kept).
release=$(cd "${1:?release folder}" && pwd)
from=${2:-$release/node_modules}
store=$release.sdk
pkg=$from/@anthropic-ai/claude-agent-sdk/package.json
[ -f "$pkg" ] || { echo "sdk-store: $pkg is missing: nothing stored" >&2; exit 0; }
v=$(node -p 'require(process.argv[1]).version' "$pkg")
[[ $v =~ ^[0-9]+\.[0-9]+\.[0-9]+[0-9A-Za-z.+-]*$ ]] || { echo "sdk-store: odd SDK version '$v'" >&2; exit 1; }
mkdir -p "$store"

if [ ! -f "$store/$v/node_modules/@anthropic-ai/claude-agent-sdk/package.json" ]; then
	rm -rf "${store:?}/$v.new"
	mkdir -p "$store/$v.new"
	if [ -n "${2:-}" ]; then
		mv "$from" "$store/$v.new/node_modules"
	else
		cp -al "$from" "$store/$v.new/node_modules" 2>/dev/null ||
			{ rm -rf "$store/$v.new/node_modules"; cp -ac "$from" "$store/$v.new/node_modules" 2>/dev/null; } ||
			{ rm -rf "$store/$v.new/node_modules"; cp -a "$from" "$store/$v.new/node_modules"; }
	fi
	rm -rf "${store:?}/$v"
	mv "$store/$v.new" "$store/$v"
	echo "sdk-store: Agent SDK $v stored"
fi
# The one installed now counts as stored last.
touch "$store/$v"

# The command lines of every process on this machine (ps -A: all users; root sees the profiles' hosts).
# Node finds the binary by its real path, so the store's real path is looked for too.
running=$(ps -A -o args= 2>/dev/null || true)
real=$(cd "$store" && pwd -P)
n=0
for name in $(ls -1t "$store"); do
	[ -d "$store/$name" ] || continue
	case $name in *.new) continue ;; esac
	# A version marked broken (rolled back) is not one of the three kept.
	grep -qxF "$name" "$store/broken" 2>/dev/null || n=$((n + 1))
	[ $n -le 3 ] && ! grep -qxF "$name" "$store/broken" 2>/dev/null && continue
	if grep -qF -e "$store/$name/" -e "$real/$name/" <<<"$running"; then
		echo "sdk-store: Agent SDK $name kept: a session still runs on it"
		continue
	fi
	rm -rf "${store:?}/$name"
	echo "sdk-store: Agent SDK $name removed"
done
