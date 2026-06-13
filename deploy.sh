#!/usr/bin/env bash
# Atomic deploy for claudia: build, then restart the service.
#
# Never run `next build` on its own against this checkout while the service is
# up — it overwrites .next underneath the running `next start`, so the live
# server serves a chunk-mismatched (white-screen) page until it restarts. This
# script keeps build+restart together so that window is as small as possible.
set -euo pipefail
cd "$(dirname "$0")"

echo "▸ building…"
node_modules/.bin/next build

echo "▸ restarting claudia…"
sudo systemctl restart claudia

sleep 3
echo "▸ status: $(systemctl is-active claudia)"
code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:"${PORT:-3000}"/ || echo "000")
echo "▸ http: $code"
[ "$code" = "200" ] && echo "✓ deployed" || { echo "✗ deploy check failed"; exit 1; }
