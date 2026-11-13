#!/usr/bin/env bash
# Download the asset catalog the app-icon workflow compiled (newest successful run, or the run id
# given) into iphone/Icon/compiled/; commit it, and tools/testflight.sh uses it.
set -euo pipefail
cd "$(dirname "$0")/.."
run=${1:-$(gh run list --workflow app-icon.yml --status success --limit 1 --json databaseId --jq '.[0].databaseId')}
rm -rf Icon/compiled
gh run download "$run" --name compiled-icon --dir Icon/compiled
ls -la Icon/compiled
