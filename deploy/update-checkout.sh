#!/usr/bin/env bash
# Keep a server that runs straight from this checkout (destiny: the user units symlinked from here)
# on GitHub's main, run by the user unit claudia-update.timer: when origin/main moved, fast-forward
# the checkout and restart the page and the runner (chats keep going in the session host). Host code
# changes wait for `systemctl --user restart claudia-host` by hand, which stops every running chat.
set -euo pipefail
cd "$(dirname "$0")/.."

git fetch --quiet origin main
have=$(git rev-parse HEAD)
want=$(git rev-parse origin/main)
[ "$have" = "$want" ] && exit 0

# A checkout someone is working in is left alone: it updates once it is back on main, clean and
# pushed. Untracked files don't count; one main would add stops the merge below.
branch=$(git symbolic-ref --short -q HEAD || true)
[ "$branch" = main ] || { echo "checkout is on ${branch:-a detached HEAD}, not main: not updated"; exit 0; }
git diff --quiet HEAD || { echo "checkout has uncommitted changes: not updated"; exit 0; }
git merge-base --is-ancestor HEAD origin/main || { echo "checkout has commits main lacks: not updated"; exit 0; }

changed=$(git diff --name-only HEAD origin/main)
git merge --quiet --ff-only origin/main
echo "updating Claudia ${have:0:7} -> ${want:0:7}"
if grep -qx -e package.json -e bun.lock <<<"$changed"; then
  "$(command -v bun || echo "$HOME/.local/bin/bun")" install --frozen-lockfile
fi
systemctl --user restart claudia-runner.service claudia.service
if grep -qx -e host.ts -e src/host-protocol.ts <<<"$changed"; then
  echo "host code changed: restart claudia-host by hand when its chats may stop"
fi
