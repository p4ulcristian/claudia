#!/usr/bin/env bash
# Production start for claudia. Runs the pre-built Next.js server.
# Build first with: node_modules/.bin/next build
set -euo pipefail
cd "$(dirname "$0")"
exec node_modules/.bin/next start -p "${PORT:-3000}"
