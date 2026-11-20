#!/usr/bin/env bash
# The release builder's Agent SDK channel (README decision 87): every server runs the newest
# @anthropic-ai/claude-agent-sdk with no merge, release or restart. builder.sh runs this every 5
# minutes as the user claudia-release-builder (install.sh puts it beside builder.sh):
#
#   sdk-channel.sh                          the builder's run: main from its clone, the hub from env
#   sdk-channel.sh --local <dir> <checkout> a trial: main = the checkout (its package files and its
#                                           built out/sdk.js), signed with <dir>/signing-key, the
#                                           files left in <dir> (<dir>/published: the newest made); no hub
#
# 1. Whether: npm's latest dist-tag, main's package-lock pin and the newest the hub has (GET
#    /hub/releases/sdk; one this builder did not send counts as none), decided by main's own code
#    (out/sdk.js from main's signed code tarball on the hub: `publish`): newer than the hub's, never
#    below main's pin.
# 2. What: main's package.json and package-lock.json with only the SDK moved to that version (npm
#    install --package-lock-only: its own dependencies follow, the rest stays as main pins), then per
#    platform a clean `npm ci --omit=dev`, as deploy/bundle.sh makes a release's node_modules (a
#    Mac's with --os=darwin --cpu=arm64), except that linux-x64 leaves the musl binary out (every
#    Claudia server is glibc Linux or a Mac); the platforms live in claudia.shared.sdk-channel/builds.
# 3. Verified: the SDK's and the platform package's integrity in the lockfile is the registry's
#    dist.integrity, npm ci checks every tarball against the lockfile, and `npm audit signatures`
#    checks the registry's signature of every package installed (and provenance where published).
# 4. Packed per platform (node_modules + sdk.txt), signed with the builder's key (ssh-keygen -Y,
#    namespace claudia-release, as every release file), brought to the hub, then POST
#    /hub/releases/sdk {version}: the hub pokes every server (`sdk <version>`).
set -euo pipefail
pkg=@anthropic-ai/claude-agent-sdk
log() { echo "sdk-channel: $*" >&2; }

local_dir=
if [ "${1:-}" = --local ]; then
	local_dir=$(cd "${2:?--local <dir> <checkout>}" && pwd)
	checkout=$(cd "${3:?--local <dir> <checkout>}" && pwd)
	key=$local_dir/signing-key
	work=$(mktemp -d "$local_dir/work.XXXXXX")
else
	home=/home/claudia-release-builder
	# shellcheck disable=SC1091
	. "$home/env"
	key=$home/signing-key
	hub=${HUB_URL%/}/hub/releases
	auth=(-H "Authorization: Bearer $(cat "$home/token")")
	work=$(mktemp -d "$home/sdk-channel.XXXXXX")
fi
trap 'rm -rf "$work"' EXIT

# hub_call <curl args…>: one call to the hub that must answer 200; prints the body.
hub_call() {
	local out code
	out=$(mktemp "$work/call.XXXXXX")
	code=$(curl -sS -o "$out" -w '%{http_code}' "$@") || code=000
	cat "$out"
	rm -f "$out"
	[ "$code" = 200 ]
}

# signed_by_me <file> <signature>: whether the signature is this builder's key's.
signed_by_me() {
	ssh-keygen -y -f "$key" | cut -d' ' -f1,2 | sed 's/^/claudia-release-builder namespaces="claudia-release" /' >"$work/own-signer"
	ssh-keygen -Y verify -f "$work/own-signer" -I claudia-release-builder -n claudia-release -s "$2" <"$1" >/dev/null 2>&1
}

# main_files: main's package.json and package-lock.json into $work/lock, its out/sdk.js into $work
# (kept in $home/sdk-cli/<tree>.js: fetched from the hub once per tree of main).
main_files() {
	mkdir -p "$work/lock"
	if [ -n "$local_dir" ]; then
		cp "$checkout/package.json" "$checkout/package-lock.json" "$work/lock/"
		cp "$checkout/out/sdk.js" "$work/sdk.js"
		return 0
	fi
	local repo=$home/repo tree code
	git -C "$repo" fetch -q origin main
	git -C "$repo" show origin/main:package.json >"$work/lock/package.json"
	git -C "$repo" show origin/main:package-lock.json >"$work/lock/package-lock.json"
	tree=$(git -C "$repo" rev-parse 'origin/main^{tree}')
	if [ ! -f "$home/sdk-cli/$tree.js" ]; then
		code=claudia-code-$tree.tar.gz
		curl -fsS --max-time 300 -o "$work/$code" "$hub/files/$code" &&
			curl -fsS --max-time 30 -o "$work/$code.sig" "$hub/files/$code.sig" ||
			{ log "main's build (tree ${tree:0:7}) is not on the hub yet: next round"; return 1; }
		signed_by_me "$work/$code" "$work/$code.sig" || { log "main's build on the hub is not signed by this builder: next round"; return 1; }
		tar -xzf "$work/$code" -C "$work" ./out/sdk.js || { log "main's build has no out/sdk.js"; return 1; }
		rm -rf "$home/sdk-cli" && mkdir -p "$home/sdk-cli" && mv "$work/out/sdk.js" "$home/sdk-cli/$tree.js"
	fi
	cp "$home/sdk-cli/$tree.js" "$work/sdk.js"
}

# published: the newest version the hub has from this builder: the hub's newest when this builder
# sent it ($home/sdk-published), else "" (a new builder publishes the latest again, with its key).
published() {
	if [ -n "$local_dir" ]; then cat "$local_dir/published" 2>/dev/null || true; return 0; fi
	local v
	v=$(hub_call --max-time 20 "$hub/sdk" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.stdout.write(String(JSON.parse(s).newest||"")))') || return 0
	if [ -n "$v" ] && grep -qxF "$v" "$home/sdk-published" 2>/dev/null; then echo "$v"; fi
	return 0
}

lock_of() { node -p "(require(process.argv[1]).packages[process.argv[2]]||{})[process.argv[3]]||''" "$@"; }

# lock_to <version>: $work/lock with only the SDK moved to version (its own dependencies follow).
lock_to() {
	(cd "$work/lock" && npm install --package-lock-only --save-exact --ignore-scripts --no-audit --no-fund "$pkg@$1" >&2)
	[ "$(lock_of "$work/lock/package-lock.json" "node_modules/$pkg" version)" = "$1" ] || { log "the lockfile did not move to $1"; return 1; }
}

# integrity_ok <dir> <name> <version>: the lockfile's integrity of that package is npm's registry's.
integrity_ok() {
	local want got
	want=$(npm view "$2@$3" dist.integrity)
	got=$(lock_of "$1/package-lock.json" "node_modules/$2" integrity)
	[ -n "$want" ] && [ "$want" = "$got" ] || { log "$2@$3: the lockfile's integrity is not the registry's"; return 1; }
}

# pack <version> <platform>: that platform's node_modules installed (npm ci's flags from out/sdk.js),
# verified, its left-out packages taken out (the musl binary: no Claudia server uses musl), packed
# and signed.
pack() {
	local v=$1 p=$2 d=$work/$2 file out
	mkdir -p "$d" "$work/out"
	cp "$work/lock/package.json" "$work/lock/package-lock.json" "$d/"
	(cd "$d" && npm ci --omit=dev --ignore-scripts --no-audit --no-fund $(node "$work/sdk.js" npm-flags "$p") >&2)
	integrity_ok "$d" "$pkg" "$v" && integrity_ok "$d" "$pkg-$p" "$v" || return 1
	(cd "$d" && npm audit signatures >&2) || { log "$v ($p): npm audit signatures failed"; return 1; }
	for out in $(node "$work/sdk.js" left-out "$p"); do rm -rf "${d:?}/node_modules/$out"; done
	test -x "$d/node_modules/$pkg-$p/claude" || { log "$v ($p): no Claude Code binary"; return 1; }
	echo "$v" >"$d/sdk.txt"
	file=$work/out/$(node "$work/sdk.js" tarball "$v" "$p")
	tar -czf "$file" -C "$d" ./node_modules ./sdk.txt
	rm -rf "$d"
	ssh-keygen -Y sign -q -f "$key" -n claudia-release "$file"
}

# send <version>: the files to the hub (or the trial's folder), then the version said published.
send() {
	local f
	if [ -n "$local_dir" ]; then
		mv "$work/out/"* "$local_dir/"
		echo "$1" >"$local_dir/published"
		return 0
	fi
	for f in "$work/out/"*; do
		hub_call --max-time 900 "${auth[@]}" -T "$f" "$hub/files/$(basename "$f")" >/dev/null || { log "could not send $(basename "$f")"; return 1; }
	done
	hub_call --max-time 60 "${auth[@]}" -H 'content-type: application/json' -d "{\"version\":\"$1\"}" "$hub/sdk" >/dev/null &&
		echo "$1" >"$home/sdk-published"
}

main_files
latest=$(npm view "$pkg" dist-tags.latest)
pinned=$(lock_of "$work/lock/package-lock.json" "node_modules/$pkg" version)
had=$(published)
node "$work/sdk.js" publish "$latest" "$had" "$pinned" || exit 0
log "publishing Agent SDK $latest (main pins $pinned, the hub had ${had:-none})"
lock_to "$latest"
for p in $(node "$work/sdk.js" platforms); do
	pack "$latest" "$p"
done
send "$latest"
log "Agent SDK $latest published"
