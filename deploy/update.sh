#!/usr/bin/env bash
# Keep a shared server on GitHub's main (run as root by claudia-update.timer): when main's commit is
# not the one in /srv/claudia, export main with git archive (which writes src/commit.txt) and run
# that copy's install.sh. Runners restart (chats keep going in the session hosts); host.ts changes
# wait for `install.sh <checkout> --restart-hosts` or the next host restart.
set -euo pipefail
repo=https://github.com/p4ulcristian/claudia
mirror=/var/lib/claudia-update/claudia.git

[ -d "$mirror" ] || git clone --quiet --bare "$repo" "$mirror"
git -C "$mirror" fetch --quiet --force origin main:main
want=$(git -C "$mirror" rev-parse main)
have=$(cat /srv/claudia/src/commit.txt 2>/dev/null || true)
# commit.txt holds the short hash; its length can differ between repos, so compare as a prefix.
[ -n "$have" ] && [[ "$want" == "$have"* ]] && exit 0

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git -C "$mirror" archive main | tar -x -C "$tmp"
echo "updating Claudia ${have:-(none)} -> ${want:0:7}"
bash "$tmp/deploy/install.sh" "$tmp"
