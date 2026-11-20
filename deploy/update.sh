#!/usr/bin/env bash
# Keep this server on the main branch's signed build (README, Hub operations: following main).
# Started by the path unit when the hub pokes the front (POST /api/update-check adds a line to
# CLAUDIA_UPDATE_TRIGGER), and hourly by the timer for a poke that was missed.
#
#   deploy/update.sh             single-user machine (systemd user units, or launchd agents on a Mac:
#                                deploy/macos): installs into $CLAUDIA_CHECKOUT
#   deploy/update.sh --shared    shared server (root, Linux): installs through deploy/shared-server/install.sh
#
# Runs on Linux (bash 5, GNU tools) and macOS (bash 3.2, BSD tools): no mapfile, no empty arrays
# under set -u, no GNU-only flags.
#
# The poke's lines: `main <commit>` (main was pushed to it: GitHub's API names it a few seconds
# later, so this waits up to 40 s for git to name it), `stage <tree>` (a build of that git tree was published
# ahead of a merge: fetch it now), `check <time>` (look at main), `releases <url>` (where the hub
# keeps the release builder's files: remembered for the runs without a poke), `sdk <version>` (the
# release builder published that Agent SDK) and `sdk-rollback <version>` (an admin rolled it back).
#
# Steps: ask GitHub for main's newest commit and its git tree; if that commit is not the installed
# one (commit.txt) and is ahead of it, install the build of that tree: the one fetched ahead
# (staged/<tree>, verified when fetched), else that tree's files: first from the hub (the release
# builder's, README decision 29: each file's ssh-keygen -Y signature must be one of the keys in
# deploy/release-signers on GitHub's main), else from GitHub's release tree-<tree> (stage.yml: its
# cosign signature must be stage.yml's on main, keyless). The tarball must say the tree it was asked for. Then fetch every staged tree the poke named (the newest two are kept).
# The front and the runners restart (chats keep going in the hosts); the hosts never restart here:
# their new code waits for a restart by hand. A new Agent SDK needs none: the release's SDK is kept
# in <release>.sdk (deploy/sdk-store.sh) and each session the hosts start loads the newest (README
# decision 86). Nothing published yet is not an error: the next poke
# installs it. Only forward, with one exception: a poke's "rollback <commit>" (the hub found that
# release broken in people's pages, README decision 35) puts the release kept before it back, once,
# and that commit is never installed again.
# The Agent SDK channel (README decision 87), first on every run: the newest Agent SDK the release
# builder published goes into the SDK store (deploy/sdk-store.sh) from this platform's signed
# tarball, and new sessions take it; nothing restarts for it (sdk_channel).
set -euo pipefail
repo=${CLAUDIA_REPO:-p4ulcristian/claudia}
branch=main
stage_identity="https://github.com/$repo/.github/workflows/stage.yml@refs/heads/$branch"
issuer=https://token.actions.githubusercontent.com
mode=user
[ "${1:-}" = --shared ] && mode=shared
os=$(uname -s)
# The node_modules hold the Agent SDK's Claude Code binary, one per platform: a Mac takes its own
# (claudia-deps-<hash>-darwin-arm64), Linux the plain one (linux-x64).
deps_suffix=
[ "$os" = Darwin ] && deps_suffix=-darwin-$(uname -m)

# The pokes are taken: the file is moved aside first, so one that comes during this run starts
# another.
lines=
if [ -n "${CLAUDIA_UPDATE_TRIGGER:-}" ] && mv "$CLAUDIA_UPDATE_TRIGGER" "$CLAUDIA_UPDATE_TRIGGER.taken" 2>/dev/null; then
	lines=$(cat "$CLAUDIA_UPDATE_TRIGGER.taken" 2>/dev/null || true)
	rm -f "$CLAUDIA_UPDATE_TRIGGER.taken"
fi
pushed=$(grep -oE '^main [0-9a-f]{40}$' <<<"$lines" | tail -1 | cut -d' ' -f2 || true)
# The staged trees the poke named, space-separated (hex only, so word splitting is safe).
stages=$(grep -oE '^stage [0-9a-f]{40}$' <<<"$lines" | cut -d' ' -f2 | sort -u | tr '\n' ' ' || true)
# The hub found the release of this commit broken in people's pages (README decision 35).
rollback=$(grep -oE '^rollback [0-9a-f]{40}$' <<<"$lines" | tail -1 | cut -d' ' -f2 || true)
# The Agent SDK channel's word (README decision 87): published, and rolled back (plain versions only).
sdk_told=$(grep -oE '^sdk [0-9]{1,6}\.[0-9]{1,6}\.[0-9]{1,6}$' <<<"$lines" | cut -d' ' -f2 | tr '\n' ' ' || true)
sdk_rollbacks=$(grep -oE '^sdk-rollback [0-9]{1,6}\.[0-9]{1,6}\.[0-9]{1,6}$' <<<"$lines" | cut -d' ' -f2 | tr '\n' ' ' || true)
told_releases=$(grep -oE '^releases https://[A-Za-z0-9.-]+(:[0-9]+)?/hub/releases/files$' <<<"$lines" | tail -1 | cut -d' ' -f2 || true)

for t in curl tar node cosign ssh-keygen; do command -v "$t" >/dev/null || { echo "missing: $t" >&2; exit 1; }; done
[ "$os" = Darwin ] && [ $mode = shared ] && { echo "--shared is Linux only (systemd, /srv/claudia)" >&2; exit 1; }

if [ $mode = shared ]; then
	[ "$(id -u)" = 0 ] || { echo "--shared runs as root" >&2; exit 1; }
	dest=/srv/claudia
	work=${STATE_DIRECTORY:-/var/lib/claudia-update}
else
	[ -n "${CLAUDIA_CHECKOUT:-}" ] || { echo "CLAUDIA_CHECKOUT is not set (~/.config/claudia/env)" >&2; exit 1; }
	dest=$CLAUDIA_CHECKOUT
	# A git checkout is someone's working copy: it is updated by hand, never replaced.
	[ -e "$dest/.git" ] && { echo "$dest is a git checkout: not replaced (point CLAUDIA_CHECKOUT at a release folder)"; exit 0; }
	work=${XDG_STATE_HOME:-$HOME/.local/state}/claudia-update
fi
staged=$work/staged
mkdir -p "$staged"
# Where the hub keeps the builder's files: the poke's word, else the last one heard.
[ -n "$told_releases" ] && echo "$told_releases" >"$work/releases-url"
releases_url=$(cat "$work/releases-url" 2>/dev/null || true)
# Which builder keys count: deploy/release-signers on GitHub's main (GitHub stays the one place that
# says it), else this release's own copy when GitHub can't be reached.
signers=$work/release-signers
curl -fsSL --max-time 20 -o "$signers.new" "https://raw.githubusercontent.com/$repo/$branch/deploy/release-signers" &&
	mv "$signers.new" "$signers" || { rm -f "$signers.new"; [ -f "$signers" ] || cp "$dest/deploy/release-signers" "$signers" 2>/dev/null || true; }
tmp=$(mktemp -d "$work/release.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

# kept <api path> <json expression>: GitHub's answer about fixed commits (a commit's tree, how two
# commits compare), asked once and kept in $work/github: it never changes. Without a token a
# server gets 60 API calls an hour, and every poke runs this script (2026-11-17: destiny ran it 28
# times in an hour, two calls each, and GitHub answered 403 until the hour was over).
kept() {
	local f v
	f="$work/github/$(printf '%s' "$1" | tr '/.' '__')"
	if [ ! -s "$f" ]; then
		v=$(api "$1" | json "$2") || return 0
		[ -n "$v" ] || return 0
		mkdir -p "$work/github"
		printf '%s\n' "$v" >"$f.new" && mv "$f.new" "$f"
		# The newest 200 are enough: only main's latest commits are asked about.
		ls -t "$work/github" | tail -n +201 | while read -r old; do rm -f "$work/github/$old"; done
	fi
	cat "$f"
}

json() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const v=JSON.parse(s);const r=new Function("v","return "+process.argv[1])(v);process.stdout.write(String(r??""))})' "$1"; }
api() { curl -fsSL --max-time 30 -H 'Accept: application/vnd.github+json' ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} "https://api.github.com/repos/$repo/$1"; }

# fetch <base url> <file> <identity> <into>: downloads a signed tarball, verifies it and unpacks
# it into <into>. 1 when it is not published (yet); 2 when its signature is not that workflow's.
fetch() {
	local base=$1 name=$2 identity=$3 into=$4 d
	d=$(mktemp -d "$work/download.XXXXXX")
	if ! curl -fsL --max-time 600 -o "$d/$name" "$base/$name" ||
		! curl -fsL --max-time 60 -o "$d/$name.sigstore.json" "$base/$name.sigstore.json"; then
		rm -rf "$d"
		return 1
	fi
	if ! cosign verify-blob --bundle "$d/$name.sigstore.json" \
		--certificate-identity "$identity" --certificate-oidc-issuer "$issuer" "$d/$name" >/dev/null 2>&1; then
		rm -rf "$d"
		return 2
	fi
	mkdir -p "$into"
	tar --no-same-owner -xzf "$d/$name" -C "$into"
	rm -rf "$d"
}

# from_hub <file> <into>: one of the release builder's files from the hub, its signature checked
# against the builder keys (ssh-keygen -Y, namespace claudia-release), unpacked into <into>. 1 when the
# hub doesn't have it (or names no address); 2 when the signature isn't a builder key's.
from_hub() {
	local name=$1 into=$2 d
	[ -n "$releases_url" ] && [ -s "$signers" ] || return 1
	d=$(mktemp -d "$work/download.XXXXXX")
	if ! curl -fsL --max-time 600 -o "$d/$name" "$releases_url/$name" ||
		! curl -fsL --max-time 60 -o "$d/$name.sig" "$releases_url/$name.sig"; then
		rm -rf "$d"
		return 1
	fi
	if ! ssh-keygen -Y verify -f "$signers" -I claudia-release-builder -n claudia-release -s "$d/$name.sig" <"$d/$name" >/dev/null 2>&1; then
		rm -rf "$d"
		return 2
	fi
	mkdir -p "$into"
	tar --no-same-owner -xzf "$d/$name" -C "$into"
	rm -rf "$d"
}

# from_release <tree> <file> <into>: one signed file of the tree's release, unpacked into <into>:
# the builder's from the hub, else stage.yml's from GitHub.
from_release() {
	local rc=0
	from_hub "$2" "$3" || rc=$?
	[ $rc = 1 ] || return $rc
	fetch "https://github.com/$repo/releases/download/tree-$1" "$2" "$stage_identity" "$3"
}

# copy_deps <from> <to>: a folder copied as hard links (cp -al: Linux, and macOS, whose cp takes -l
# too: seen 2026-11-20), else as APFS clones (cp -c), else as plain copies; a failed try leaves
# nothing behind for the next.
copy_deps() {
	cp -al "$1" "$2" 2>/dev/null && return 0
	rm -rf "$2"
	cp -ac "$1" "$2" 2>/dev/null && return 0
	rm -rf "$2"
	cp -a "$1" "$2"
}

# deps_for <into>: the node_modules the code in <into> names (deps.txt: its lockfile's hash), from
# $work/deps when an earlier build had the same lockfile, else downloaded once (claudia-deps-<hash>
# of the same release, this platform's) and kept there; hard-linked or cloned in, so a cached one
# costs no space or time.
deps_for() {
	local into=$1 t=$2 deps rc=0
	deps=$(cat "$into/deps.txt" 2>/dev/null || true)
	[[ $deps =~ ^[0-9a-f]{20}$ ]] || { echo "the code for tree ${t:0:7} names no node_modules" >&2; return 2; }
	mkdir -p "$work/deps"
	if [ ! -f "$work/deps/$deps/deps.txt" ]; then
		rm -rf "$work/deps/$deps.new"
		from_release "$t" "claudia-deps-$deps$deps_suffix.tar.gz" "$work/deps/$deps.new" || rc=$?
		[ $rc = 0 ] || { rm -rf "$work/deps/$deps.new"; return $rc; }
		[ "$(cat "$work/deps/$deps.new/deps.txt" 2>/dev/null)" = "$deps" ] || { echo "the node_modules ${deps:0:7} say another lockfile" >&2; rm -rf "$work/deps/$deps.new"; return 2; }
		mv "$work/deps/$deps.new" "$work/deps/$deps"
		echo "fetched node_modules ${deps:0:7} (the lockfile changed)"
	fi
	touch "$work/deps/$deps"
	copy_deps "$work/deps/$deps/node_modules" "$into/node_modules"
}

# build_of <tree> <into>: the tree's build: its code bundle (a few MB) and the node_modules it names;
# a release made before the split has only the whole build (claudia-tree-<tree>), taken then (on
# Linux only: its node_modules are Linux's).
build_of() {
	local rc=0
	from_release "$1" "claudia-code-$1.tar.gz" "$2" || rc=$?
	if [ $rc = 1 ] && [ -z "$deps_suffix" ]; then
		rc=0
		from_release "$1" "claudia-tree-$1.tar.gz" "$2" || rc=$?
		return $rc
	fi
	[ $rc = 0 ] || return $rc
	deps_for "$2" "$1" || { rc=$?; rm -rf "$2"; return $rc; }
}

# fetch_tree <tree> <into>: stage.yml's build of a git tree, checked to be that tree's. A tree the
# poke named as staged was just published, but GitHub can take a few seconds to serve its files:
# that one is tried again for up to a minute before it counts as not published.
fetch_tree() {
	local rc i tries=1
	case " $stages " in *" $1 "*) tries=12 ;; esac
	for ((i = 1; i <= tries; i++)); do
		rc=0
		build_of "$1" "$2" || rc=$?
		[ $rc = 1 ] && [ $i -lt $tries ] && { sleep 5; continue; }
		break
	done
	[ $rc = 0 ] || return $rc
	[ "$(cat "$2/tree.txt" 2>/dev/null)" = "$1" ] || { echo "the build for tree ${1:0:7} says another tree" >&2; rm -rf "$2"; return 2; }
}

# main's commit from git's own ref list (the API can name the commit before a push a few seconds
# longer, and this costs no API call), then its tree from the API (a commit never changes, so it is asked once: kept).
main_now() {
	curl -fsS --max-time 20 "https://github.com/$repo.git/info/refs?service=git-upload-pack" |
		grep -aoE "[0-9a-f]{40} refs/heads/$branch\$" | cut -d' ' -f1 || true
}

# sdk_mark <version> <store>: a rolled-back Agent SDK marked in the store's broken file: new sessions
# skip it, and it is never stored again.
sdk_mark() {
	grep -qxF "$1" "$2/broken" 2>/dev/null && return 0
	echo "$1" >>"$2/broken"
	echo "Agent SDK $1 was rolled back: new sessions skip it"
}

# sdk_fetch <version> <platform>: that version's tarball from the hub, its signature checked as a
# release file's (from_hub: deploy/release-signers), its version checked, then moved into the store
# (deploy/sdk-store.sh). 1 when it is not there or not right.
sdk_fetch() {
	local v=$1 name d rc=0 said
	name=$(node "$dest/out/sdk.js" tarball "$v" "$2") || return 1
	d=$(mktemp -d "$work/sdk.XXXXXX")
	from_hub "$name" "$d" || rc=$?
	case $rc in
	0) ;;
	1) echo "Agent SDK $v: $name is not on the hub"; rm -rf "$d"; return 1 ;;
	*) echo "Agent SDK $v: $name does not carry the builder's signature: not stored" >&2; rm -rf "$d"; return 1 ;;
	esac
	said=$(node -p 'require(process.argv[1]).version' "$d/node_modules/@anthropic-ai/claude-agent-sdk/package.json" 2>/dev/null || true)
	if [ "$(cat "$d/sdk.txt" 2>/dev/null)" != "$v" ] || [ "$said" != "$v" ]; then
		echo "Agent SDK $v: $name holds another version: not stored" >&2
		rm -rf "$d"
		return 1
	fi
	# Written by this user only, readable by every profile's host (shared: root's files).
	chmod -R go-w,a+rX "$d"
	bash "$dest/deploy/sdk-store.sh" "$dest" "$d/node_modules" || { rm -rf "$d"; return 1; }
	rm -rf "$d"
	echo "Agent SDK $v stored: new sessions take it; nothing restarted"
}

# sdk_channel: the Agent SDK channel (README decision 87). The hub names the newest published
# version and the rolled-back ones (GET <hub>/hub/releases/sdk; a poke's `sdk` and `sdk-rollback`
# lines say the same at once); rolled-back ones are marked in the store, and a version this
# release's out/sdk.js says to store (not marked, not stored yet, not older than the release's own)
# is fetched for this platform. The hosts see the store change by themselves (within 10 s) and the
# runners follow them: nothing restarts. A failure here never stops the rest of the update.
sdk_channel() {
	local cli=$dest/out/sdk.js store platform said newest broken v
	[ -f "$cli" ] || { echo "this release has no Agent SDK channel (out/sdk.js)"; return 0; }
	[ -n "$releases_url" ] || return 0
	store=$(cd "$dest" && pwd).sdk
	rm -rf "$work"/sdk.*
	said=$(curl -fsS --max-time 20 "${releases_url%/files}/sdk" 2>/dev/null || true)
	newest=$(if [ -n "$said" ]; then json 'v.newest||""' <<<"$said"; fi || true)
	broken=$(if [ -n "$said" ]; then json '(v.broken||[]).join(" ")' <<<"$said"; fi || true)
	mkdir -p "$store"
	for v in $sdk_rollbacks $broken; do
		if node "$cli" version "$v"; then sdk_mark "$v" "$store"; fi
	done
	platform=$(node "$cli" platform "$os" "$(uname -m)") || { echo "no Agent SDK channel for $os $(uname -m)"; return 0; }
	for v in $(printf '%s\n' $sdk_told $newest | awk 'NF && !seen[$0]++'); do
		if node "$cli" install "$v" "$dest"; then sdk_fetch "$v" "$platform" || true; fi
	done
}
sdk_channel || echo "the Agent SDK channel step failed: the next run tries again" >&2

want=$(main_now)
# A push the hub passed on: wait for git to name it (a later push wins).
for _ in $(seq 20); do
	[ -z "$pushed" ] || [ "$want" = "$pushed" ] && break
	sleep 2
	want=$(main_now)
done
[[ $want =~ ^[0-9a-f]{40}$ ]] || { echo "GitHub named no commit for $branch" >&2; exit 1; }
tree=$(kept "git/commits/$want" 'v.tree.sha')
[[ $tree =~ ^[0-9a-f]{40}$ ]] || { echo "GitHub named no tree for ${want:0:7}" >&2; exit 1; }
have=$(cat "$dest/commit.txt" 2>/dev/null || true)

# get <into>: main's build into <into>, from where it is nearest. 1 when none is published yet.
get() {
	local rc=0
	if [ -f "$staged/$tree/tree.txt" ]; then
		mv "$staged/$tree" "$1"
		echo "tree ${tree:0:7} was fetched ahead"
		return 0
	fi
	fetch_tree "$tree" "$1" || rc=$?
	return $rc
}

# restart_programs: the release's Agent SDK goes into the store the hosts load sessions from
# (deploy/sdk-store.sh; shared: its install.sh does it), then the front and the runners start again
# on what $dest holds now; the hosts keep running (their next sessions take the new SDK).
restart_programs() {
	if [ $mode = user ]; then
		bash "$dest/deploy/sdk-store.sh" "$dest" || echo "the Agent SDK store was not updated: sessions load the release's own copy" >&2
	fi
	if [ $mode = shared ]; then
		systemctl restart claudia-front.service
		sed 's/#.*//' /etc/claudia/profiles | awk 'NF { print $1 }' | while read -r p; do
			systemctl restart "claudia-runner@$p.service"
		done
	elif [ "$os" = Darwin ]; then
		# The agents are written again from the release; the front and the runner restart, the
		# hosts and this update agent keep running (deploy/macos/install.sh).
		bash "$dest/deploy/macos/install.sh" --update
	else
		# Changed units are read again; the hosts keep running.
		systemctl --user daemon-reload
		systemctl --user restart claudia-front.service claudia-runner.service
	fi
}

# roll_back <commit>: one step back, from that commit's release to the one kept before it (the hub's
# word, README decision 35). Only while that commit runs, and only once: the release before it is
# not kept after it is back, and the commit is never installed again (main must move past it).
roll_back() {
	local bad=$1 back
	[ "$have" = "$bad" ] || { echo "a rollback of ${bad:0:7} was asked, but ${have:0:7} runs: nothing to do"; return 0; }
	back=$(cat "$dest.old/commit.txt" 2>/dev/null || true)
	[[ $back =~ ^[0-9a-f]{40}$ ]] || { echo "no release before ${bad:0:7} is kept: not rolled back" >&2; return 1; }
	echo "$bad" >>"$work/rolled-back"
	rm -rf "$dest.rolled-back"
	mv "$dest" "$dest.rolled-back"
	mv "$dest.old" "$dest"
	restart_programs
	echo "rolled back from ${bad:0:7} to ${back:0:7}: its pages were broken; waiting for a newer commit"
	have=$back
}

install_main() {
	local rc=0
	get "$tmp/x" || rc=$?
	case $rc in
	0) ;;
	1) echo "no signed build for ${want:0:7} yet: the next poke installs it"; return 0 ;;
	*) echo "the build for ${want:0:7} does not carry the builder's or the workflow's signature: not installed" >&2; return 1 ;;
	esac
	echo "$want" >"$tmp/x/commit.txt"
	echo "updating Claudia ${have:0:7} -> ${want:0:7}"
	if [ $mode = shared ]; then
		# The profiles on this machine: the first word of each line of /etc/claudia/profiles.
		mapfile -t profiles < <(sed 's/#.*//' /etc/claudia/profiles | awk 'NF { print $1 }')
		bash "$tmp/x/deploy/shared-server/install.sh" "$tmp/x" "${profiles[@]}"
	else
		rm -rf "$dest.new" "$dest.old" "$dest.rolled-back"
		mv "$tmp/x" "$dest.new"
		[ -d "$dest" ] && mv "$dest" "$dest.old"
		mv "$dest.new" "$dest"
		restart_programs
	fi
	echo "Claudia is at ${want:0:7}; new sessions take its Agent SDK, host code changes wait for a host restart by hand"
	have=$want
}

[ -n "$rollback" ] && roll_back "$rollback"

if grep -qxF "$want" "$work/rolled-back" 2>/dev/null; then
	echo "main is still at ${want:0:7}, which was rolled back: waiting for a newer commit"
elif [ "$have" != "$want" ]; then
	# Only forward: the new commit must contain the installed one.
	status=ahead
	[[ $have =~ ^[0-9a-f]{40}$ ]] && status=$(kept "compare/$have...$want" 'v.status')
	if [ "$status" = ahead ]; then
		install_main
	else
		echo "main at ${want:0:7} is $status of the installed ${have:0:7}: not updated"
	fi
fi

# The trees the poke named, fetched ahead so their merge only swaps; the newest two are kept.
for t in $stages; do
	[ "$t" = "$tree" ] && [ "$have" = "$want" ] && continue
	[ -f "$staged/$t/tree.txt" ] && continue
	rc=0
	fetch_tree "$t" "$staged/$t.new" || rc=$?
	case $rc in
	0) mv "$staged/$t.new" "$staged/$t" && echo "fetched tree ${t:0:7} ahead" ;;
	1) echo "tree ${t:0:7} is not published" ;;
	*) echo "the build for tree ${t:0:7} does not carry the builder's or stage.yml's signature: not kept" >&2 ;;
	esac
	rm -rf "$staged/$t.new"
done
# keep_newest <folder>: the two folders in it changed last stay, the rest go (their names are git
# trees or lockfile hashes, plain hex, so ls's lines are safe; ls -t sorts by modification time on
# GNU and BSD alike, as find -printf '%T@' did).
keep_newest() {
	local n=0 name
	for name in $(ls -1t "$1"); do
		[ -d "$1/$name" ] || continue
		n=$((n + 1))
		[ $n -le 2 ] || rm -rf "${1:?}/$name"
	done
}
keep_newest "$staged"
# The node_modules of the two lockfiles used last stay (an install hard-links them, so it keeps its own).
if [ -d "$work/deps" ]; then
	keep_newest "$work/deps"
fi
