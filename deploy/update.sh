#!/usr/bin/env bash
# Keep this server on the beta branch's newest signed release (README decision 11). Started by the
# path unit when the hub pokes the front (POST /api/update-check writes CLAUDIA_UPDATE_TRIGGER), and
# hourly by the timer for a poke that was missed.
#
#   deploy/update.sh             single-user machine (user units): installs into $CLAUDIA_CHECKOUT
#   deploy/update.sh --shared    shared server (root): installs through deploy/shared-server/install.sh
#
# Steps: ask GitHub for the beta branch's newest commit; if it is not the installed one (commit.txt)
# and is ahead of it, download that commit's slave release (claudia-beta-slave-<commit>.tar.gz from the
# release beta-<short commit>), verify its cosign signature (keyless: made by this repository's
# beta-release workflow on branch beta), and install it. The front and the runners restart (chats keep
# going in the hosts); the hosts never restart here: their new code waits for a restart by hand.
# A release not published yet (the workflow still runs) is not an error: the next poke installs it.
set -euo pipefail
repo=${CLAUDIA_REPO:-p4ulcristian/claudia}
branch=beta
identity="https://github.com/$repo/.github/workflows/beta-release.yml@refs/heads/$branch"
issuer=https://token.actions.githubusercontent.com
mode=user
[ "${1:-}" = --shared ] && mode=shared

# The poke is taken: one that comes during this run starts another.
[ -z "${CLAUDIA_UPDATE_TRIGGER:-}" ] || rm -f "$CLAUDIA_UPDATE_TRIGGER"

for t in curl tar node cosign; do command -v "$t" >/dev/null || { echo "missing: $t" >&2; exit 1; }; done

if [ $mode = shared ]; then
	[ "$(id -u)" = 0 ] || { echo "--shared runs as root" >&2; exit 1; }
	dest=/srv/claudia-beta
	work=${STATE_DIRECTORY:-/var/lib/claudia-beta-update}
else
	[ -n "${CLAUDIA_CHECKOUT:-}" ] || { echo "CLAUDIA_CHECKOUT is not set (~/.config/claudia-beta/env)" >&2; exit 1; }
	dest=$CLAUDIA_CHECKOUT
	# A git checkout is someone's working copy: it is updated by hand, never replaced.
	[ -e "$dest/.git" ] && { echo "$dest is a git checkout: not replaced (point CLAUDIA_CHECKOUT at a release folder)"; exit 0; }
	work=${XDG_STATE_HOME:-$HOME/.local/state}/claudia-beta-update
fi
mkdir -p "$work"

json() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const v=JSON.parse(s);const r=new Function("v","return "+process.argv[1])(v);process.stdout.write(String(r??""))})' "$1"; }
api() { curl -fsSL --max-time 30 -H 'Accept: application/vnd.github+json' ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} "https://api.github.com/repos/$repo/$1"; }

want=$(api "commits/$branch" | json 'v.sha')
[[ $want =~ ^[0-9a-f]{40}$ ]] || { echo "GitHub named no commit for $branch" >&2; exit 1; }
have=$(cat "$dest/commit.txt" 2>/dev/null || true)
[ "$have" = "$want" ] && exit 0

# Only forward: the new commit must contain the installed one.
if [[ $have =~ ^[0-9a-f]{40}$ ]]; then
	status=$(api "compare/$have...$want" | json 'v.status')
	[ "$status" = ahead ] || { echo "beta at ${want:0:7} is $status of the installed ${have:0:7}: not updated"; exit 0; }
fi

name=claudia-beta-slave-$want.tar.gz
base="https://github.com/$repo/releases/download/beta-${want:0:7}"
tmp=$(mktemp -d "$work/release.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
if ! curl -fsSL --max-time 600 -o "$tmp/$name" "$base/$name" ||
	! curl -fsSL --max-time 60 -o "$tmp/$name.sigstore.json" "$base/$name.sigstore.json"; then
	echo "no signed release for ${want:0:7} yet: the next poke installs it"
	exit 0
fi
cosign verify-blob --bundle "$tmp/$name.sigstore.json" \
	--certificate-identity "$identity" --certificate-oidc-issuer "$issuer" "$tmp/$name" >/dev/null 2>&1 ||
	{ echo "the release for ${want:0:7} does not carry the beta-release workflow's signature: not installed" >&2; exit 1; }

mkdir "$tmp/x"
tar -xzf "$tmp/$name" -C "$tmp/x" --no-same-owner
[ "$(cat "$tmp/x/commit.txt")" = "$want" ] || { echo "the release's commit.txt is not ${want:0:7}" >&2; exit 1; }
echo "updating Claudia beta ${have:0:7} -> ${want:0:7}"

if [ $mode = shared ]; then
	# The profiles on this machine: the first word of each line of /etc/claudia-beta/profiles.
	mapfile -t profiles < <(sed 's/#.*//' /etc/claudia-beta/profiles | awk 'NF { print $1 }')
	bash "$tmp/x/deploy/shared-server/install.sh" "$tmp/x" "${profiles[@]}"
else
	rm -rf "$dest.new" "$dest.old"
	mv "$tmp/x" "$dest.new"
	[ -d "$dest" ] && mv "$dest" "$dest.old"
	mv "$dest.new" "$dest"
	# Changed units are read again; the hosts keep running.
	systemctl --user daemon-reload
	systemctl --user restart claudia-beta-front.service claudia-beta-runner.service
fi
echo "Claudia beta is at ${want:0:7}; host code changes wait for a host restart by hand"
