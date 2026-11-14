#!/usr/bin/env bash
# The release builder's loop (README decision 29), as the user claudia-release-builder, started by
# claudia-release-builder.service. It asks the hub what to build (GET /hub/releases/wanted, held up
# to 25 s), builds each commit from GitHub, signs every file with its own key and brings the files
# to the hub, then says the tree is ready (the hub tells servers and the gate). Main's newest
# commit is also looked at on every round, so a webhook the hub missed still gets built.
#
# Its home holds everything: tools/ (Node, a JDK: install.sh), repo/ (a clone of the repository),
# signing-key (ed25519, its public half in deploy/release-signers on main), token (the secret of
# the builder key pasted on the hub), github-token (fine-grained, this repository's commit statuses
# only: the status `builder`, the check main requires before a merge) and env (HUB_URL, CLAUDIA_REPO).
#
# Each commit is checked before it is built: clj-kondo and the ratchet, then the tests (compiled in
# the warm server, run beside the five release builds); the status `builder` on the commit says
# pending, then success or failure and why. A commit that fails is never brought to the hub.
set -euo pipefail
home=/home/claudia-release-builder
# shellcheck disable=SC1091
. "$home/env"
repo=${CLAUDIA_REPO:-p4ulcristian/claudia}
export PATH="$home/tools/node/bin:$home/tools/jdk/bin:$home/tools/kondo:$PATH" JAVA_HOME="$home/tools/jdk"
auth=(-H "Authorization: Bearer $(cat "$home/token")")
hub=${HUB_URL%/}/hub/releases

log() { echo "builder: $*"; }

# hub_call <curl args…>: one call to the hub that must answer 200 (a login page's redirect, which
# curl -f takes as success, is a failure here); prints the body.
hub_call() {
	local out code
	out=$(mktemp)
	code=$(curl -sS -o "$out" -w '%{http_code}' "$@") || code=000
	cat "$out"
	rm -f "$out"
	[ "$code" = 200 ]
}

# on_hub <file>: whether the hub has the file and its signature.
on_hub() { hub_call -I --max-time 20 "$hub/files/$1" >/dev/null && hub_call -I --max-time 20 "$hub/files/$1.sig" >/dev/null; }

# wanted: the commits the hub wants built, newest first, one per line.
wanted() {
	local j
	j=$(hub_call --max-time 40 "${auth[@]}" "$hub/wanted") || return 1
	node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{for(const b of JSON.parse(s).builds||[])if(/^[0-9a-f]{40}$/.test(b.commit))console.log(b.commit)})' <<<"$j"
}

# status <commit> <pending|success|failure> <description>: the status `builder` on the commit
# (nothing when this builder has no GitHub token; a failed call only logs).
status() {
	[ -s "$home/github-token" ] || return 0
	local code
	code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 -X POST \
		-H "Authorization: Bearer $(cat "$home/github-token")" -H 'Accept: application/vnd.github+json' \
		-d "{\"state\":\"$2\",\"context\":\"builder\",\"description\":\"$3\"}" \
		"https://api.github.com/repos/$repo/statuses/$1") || code=000
	[ "$code" = 201 ] || log "status $2 on ${1:0:7}: GitHub answered $code"
}

main_commit() { git -C "$home/repo" ls-remote origin refs/heads/main | cut -c1-40; }

# sign_and_send <file>: its signature made and both brought to the hub.
sign_and_send() {
	local f=$1 name
	name=$(basename "$f")
	rm -f "$f.sig"
	ssh-keygen -Y sign -q -f "$home/signing-key" -n claudia-release "$f" || return 1
	hub_call --max-time 900 "${auth[@]}" -T "$f" "$hub/files/$name" >/dev/null || return 1
	hub_call --max-time 60 "${auth[@]}" -T "$f.sig" "$hub/files/$name.sig" >/dev/null
}

ready() {
	hub_call --max-time 60 "${auth[@]}" -H 'content-type: application/json' \
		-d "{\"commit\":\"$1\",\"tree\":\"$2\",\"deps\":\"$3\"}" "$hub/ready" >/dev/null
}

# A shadow-cljs server stays running between releases: each release then compiles in a warm JVM
# (measured 2026-11-14: 35 s against 53 s for a fresh one; five builds side by side were slower,
# their JVMs fight over the cores). It starts again, after a fresh `npm ci`, only when the packages
# or shadow-cljs.edn change (warm_key); `shadow-cljs release` runs in its own JVM when no server is up.
server_pid=
warm=

warm_key() { cat package-lock.json shadow-cljs.edn | sha256sum | cut -c1-16; }

stop_server() {
	[ -n "$server_pid" ] && kill "$server_pid" 2>/dev/null && wait "$server_pid" 2>/dev/null
	server_pid=
	warm=
}

# warm_up: packages installed and the server running for this checkout's packages and config.
warm_up() {
	local key
	key=$(warm_key) || return 1
	[ "$key" = "$warm" ] && [ -n "$server_pid" ] && kill -0 "$server_pid" 2>/dev/null && return 0
	stop_server
	npm ci --include=dev --ignore-scripts --no-audit --no-fund >/dev/null || return 1
	npx shadow-cljs server >"$home/shadow-server.log" 2>&1 &
	server_pid=$!
	for _ in $(seq 90); do
		grep -q "shadow-cljs - server version" "$home/shadow-server.log" && { warm=$key; return 0; }
		sleep 1
	done
	log "the shadow-cljs server did not come up: building without it"
	stop_server
}

# check_and_compile <commit> <started>: the checkout checked and built; the tests run beside the
# release builds. Every failure sets the status and says which step.
check_and_compile() {
	local c=$1 t0=$2 tests
	fail() { status "$c" failure "$1 ($(($(date +%s) - t0)) s)"; log "${c:0:7}: $1"; return 1; }
	warm_up || { fail "packages did not install"; return 1; }
	clj-kondo --lint src test --fail-level error >"$home/lint.log" 2>&1 || { fail "clj-kondo found errors"; return 1; }
	node bin/ratchet.mjs >"$home/ratchet.log" 2>&1 || { fail "the ratchet went up"; return 1; }
	npx shadow-cljs compile test >"$home/test-build.log" 2>&1 || { fail "the tests do not compile"; return 1; }
	node out/test.js >"$home/test.log" 2>&1 &
	tests=$!
	{ npx shadow-cljs release host runner front hub web && npm run build:web-files >/dev/null; } ||
		{ kill "$tests" 2>/dev/null; wait "$tests" 2>/dev/null; fail "a release build failed"; return 1; }
	wait "$tests" || { fail "tests failed: $(grep -m1 -oE '[0-9]+ failures, [0-9]+ errors' "$home/test.log")"; return 1; }
}

# build <commit>: its tree's release on the hub (built unless the hub has it already), then ready.
# Called as `build c || …`, where bash ignores set -e: every step that can fail says so itself.
build() {
	local commit=$1 tree deps started out
	git -C "$home/repo" fetch -q origin "$commit" || return 1
	tree=$(git -C "$home/repo" rev-parse "$commit^{tree}") || return 1
	deps=$(git -C "$home/repo" show "$commit:package-lock.json" | sha256sum | cut -c1-20) || return 1
	started=$(date +%s)
	if ! on_hub "claudia-code-$tree.tar.gz"; then
		status "$commit" pending "checking and building"
		cd "$home/repo" || return 1
		git checkout -q --force --detach "$commit" || return 1
		# Untracked files go (out/ and dist/ too), but not the packages and shadow-cljs's cache (keyed by content).
		git clean -q -ffdx -e node_modules -e .shadow-cljs || return 1
		check_and_compile "$commit" "$started" || return 1
		local flag=--no-deps
		{ on_hub "claudia-deps-$deps.tar.gz" && on_hub "claudia-deps-$deps-darwin-arm64.tar.gz"; } || flag=
		out=$(deploy/bundle.sh $flag) || { log "${commit:0:7} does not bundle"; return 1; }
		[ "$(grep '^tree=' <<<"$out" | cut -d= -f2)" = "$tree" ] || { log "the bundle names another tree"; return 1; }
		for f in dist/claudia-code-*.tar.gz dist/claudia-deps-*.tar.gz dist/darwin/claudia-deps-*.tar.gz; do
			if [ -f "$f" ]; then sign_and_send "$f" || { log "could not send $(basename "$f")"; return 1; }; fi
		done
		log "built ${commit:0:7} (tree ${tree:0:7}) in $(($(date +%s) - started)) s"
	fi
	ready "$commit" "$tree" "$deps" || { log "the hub refused ${commit:0:7} as ready"; return 1; }
	status "$commit" success "checked, built and on the hub in $(($(date +%s) - started)) s"
	echo "$commit" >>"$home/done"
	log "ready ${commit:0:7} (tree ${tree:0:7})"
}

# main_if_new: main's newest commit unless it was made ready or failed before (a webhook the hub
# missed; a commit that does not build is tried again only when the hub asks for it).
main_if_new() {
	local c
	c=$(main_commit)
	cat "$home/done" "$home/failed" 2>/dev/null | grep -qx "$c" || echo "$c"
}

trap stop_server EXIT
[ -d "$home/repo/.git" ] || git clone -q "https://github.com/$repo.git" "$home/repo"
touch "$home/done" "$home/failed"
while true; do
	for f in done failed; do tail -n 200 "$home/$f" >"$home/$f.new" && mv "$home/$f.new" "$home/$f"; done
	commits=$( { main_if_new; wanted || sleep 10; } | awk 'NF && !seen[$0]++')
	for c in $commits; do
		build "$c" || { log "build of ${c:0:7} failed"; echo "$c" >>"$home/failed"; }
	done
done
