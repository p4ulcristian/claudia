#!/usr/bin/env bash
# Packs a built checkout into Claudia's release files (README decision 29), the same wherever it
# runs: the PR check (.github/workflows/check.yml) and the release builder
# (deploy/release-builder/builder.sh). Run from the checkout's root after
# `npx shadow-cljs release host runner front hub web && npm run build:web-files`:
#
#   deploy/bundle.sh [--no-deps]
#
# Writes into dist/:
#   claudia-code-<tree>.tar.gz                      the code: out/, deploy/, package.json, tree.txt, deps.txt
#   claudia-deps-<lockfile hash>.tar.gz              production node_modules for linux-x64
#   darwin/claudia-deps-<lockfile hash>-darwin-arm64.tar.gz  the same for a Mac (the Agent SDK's binary is per platform)
#   claudia-tree-<tree>.tar.gz                       code and Linux node_modules in one (servers from before the split)
# --no-deps: only the code (the builder skips node_modules a release already has); no tree file then.
# Prints `tree=<tree>` and `deps=<hash>` lines last.
set -euo pipefail
with_deps=1
[ "${1:-}" = --no-deps ] && with_deps=0

tree=$(git rev-parse 'HEAD^{tree}')
deps=$(sha256sum package-lock.json | cut -c1-20)
rm -rf dist
mkdir -p dist/pkg
cp -r out deploy package.json package-lock.json dist/pkg/
rm -rf dist/pkg/out/test.js dist/pkg/out/test.js.map dist/pkg/out/cljs-runtime
echo "$tree" >dist/pkg/tree.txt
echo "$deps" >dist/pkg/deps.txt
tar -czf "dist/claudia-code-$tree.tar.gz" -C dist/pkg --exclude=./package-lock.json .

if [ $with_deps = 1 ]; then
	(cd dist/pkg && npm ci --omit=dev --ignore-scripts --no-audit --no-fund && rm package-lock.json)
	tar -czf "dist/claudia-tree-$tree.tar.gz" -C dist/pkg .
	tar -czf "dist/claudia-deps-$deps.tar.gz" -C dist/pkg ./node_modules ./deps.txt
	mkdir -p dist/darwin/pkg
	cp package.json package-lock.json dist/darwin/pkg/
	echo "$deps" >dist/darwin/pkg/deps.txt
	(cd dist/darwin/pkg && npm ci --omit=dev --ignore-scripts --no-audit --no-fund --os=darwin --cpu=arm64 && rm package.json package-lock.json)
	test -x dist/darwin/pkg/node_modules/@anthropic-ai/claude-agent-sdk-darwin-arm64/claude
	tar -czf "dist/darwin/claudia-deps-$deps-darwin-arm64.tar.gz" -C dist/darwin/pkg ./node_modules ./deps.txt
fi
ls -l dist/*.tar.gz dist/darwin/*.tar.gz >&2 2>/dev/null || true
echo "tree=$tree"
echo "deps=$deps"
