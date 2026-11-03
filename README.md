# Claudia (beta: ClojureScript)

Chat with Claude on the left; Claude explains on the right, in a picture it draws as it works. Chat
stays short, like two friends talking; the explaining lives in the picture.

This branch is the clean-slate rewrite in ClojureScript on Node. It runs beside the TypeScript
Claudia (branch `main`) until it replaces it.

## Shape

- **hub** (the master, one, on the gate): the page, who may use which server, the pass-through.
- **front** (every server): takes the hub's calls, picks the profile. No page.
- **runner** (per profile): chats, pictures, questions, 1Password secrets, SQLite.
- **host** (per profile): the Agent SDK sessions; a runner restart keeps chats running.

The wire between them is in [PROTOCOL.md](PROTOCOL.md).

## Decisions (2026-11-02)

1. ClojureScript on Node, compiled ahead by shadow-cljs: RAM matters (the gate has under 1 GB), and the
   Agent SDK is JavaScript-only, so the slave calls it directly. Not JVM Clojure.
2. No Groundwork: Claudia has no shared database, permissions or sessions to solve; plain JSON.
3. Page: Reagent + re-frame.
4. Storage: SQLite through `node:sqlite`, one file per profile next to its Claude Code sessions
   (the chats themselves are Claude Code's own `.jsonl` files). Not Postgres.
5. A clean slate, not a mirror: v1 has chats, questions, the picture (graph, freeform, the forms
   pipeline/timeline/compare/chart/stats) and 1Password secrets; the rest comes back one at a time.
6. Runs beside the old Claudia at its own address until the switch.
7. Secrets from 1Password, never stored on a server: [plan/1passwd-plan.md](plan/1passwd-plan.md).

## Hub operations (2026-11-03, ported from the TypeScript Claudia's decisions 22, 23, 24, 27)

- **Servers and access**: the hub's own SQLite store (`HUB_DATA/hub.db`, `src/claudia/hub/store.cljs`)
  is the source of truth for the servers, the people and the access grid (person × server; a tick =
  their own profile there). A `hub.json` (`HUB_CONFIG`) is imported once into an empty store, then no
  longer read. Admins (the store's admin flag, or the login group `HUB_ADMIN_GROUP`) change it on the
  page's Servers and Access dialog. A new server joins with `deploy/pair.sh` (a WireGuard key and a
  one-time secret printed as a key, pasted into Servers; the hub writes the peer request into
  `HUB_PEERS_DIR`, the gate's root helper adds the peer and answers in `HUB_APPLIED_DIR`; the server
  fetches its address with the secret). A hub without `HUB_WG_POOL` shows pairing as unavailable.
- **Push**: the hub tells a person's iPhones (APNs, token auth with jose; `HUB_APNS_DRY_RUN=1` logs
  instead) when a chat of theirs finished a turn, asks a question, or waits on a secret. It learns
  that like the page does: one socket per person with a phone and server the grid allows, as that
  person (their own chats only). Alert texts are sealed (AES-256-GCM, node:crypto) under the key the
  app made; every alert carries the badge (waiting plus finished-unseen chats across servers); a
  change of the count alone goes as a badge-only push; one summary Live Activity per phone. While the
  person works at a desktop page (`POST /hub/presence`), alerts are held; when they leave, the newest
  per chat that still matters goes.
- **Following beta**: `.github/workflows/beta-release.yml` builds everything on each push to `beta`,
  publishes the hub image `ghcr.io/<owner>/claudia-beta-hub:sha-<commit>` and the slave release
  `claudia-beta-slave-<commit>.tar.gz` (out/, production node_modules for linux-x64, deploy/,
  commit.txt) on the GitHub release `beta-<short commit>`, both signed keyless by cosign. GitHub's
  webhook (`POST /hub/github`, HMAC with `HUB_GITHUB_SECRET_FILE`) makes the hub poke every front
  (`POST /api/update-check`, which writes `CLAUDIA_UPDATE_TRIGGER`) on a push to beta and again when
  the run succeeded, and on that success writes the commit into `HUB_UPDATE_REQUEST` for the gate.
  Each server's `deploy/update.sh` (path unit on the trigger, hourly timer) installs the newest beta
  release only after `cosign verify-blob` against this workflow on beta, only forward, restarts the
  front and the runners, never the hosts. A single-user machine's `CLAUDIA_CHECKOUT` must be a release
  folder (a git checkout is never replaced): first install with
  `CLAUDIA_CHECKOUT=<folder> deploy/update.sh`, then enable `claudia-beta-update.path` and `.timer`.

## Build

```
npm ci
npx shadow-cljs release host runner front hub web
```

## Back from the TypeScript Claudia (2026-11-03)

- Picture history: a picture per turn (SQLite `pictures`); the bar under the picture steps through them (‹ › ←/→, a scrubber), the newest is live, older ones read only; Picture | Timeline (Alt+T); fold (Alt+P); the camera copies or saves the picture as PNG.
- Timeline: every step of the chat and its subagents from the session files, a rail per agent, quiet stretches, Edit/Write steps with +/− opening their diff (`src/claudia/slave/timeline.cljs`, `src/claudia/web/views/timeline.cljs`).
- Every draw answers Claude with a screenshot of the picture: playwright-core opens the page's render mode (`claudia.web.render`) from this checkout's `out/web` in a headless Chromium (`/opt/claudia-tools/ms-playwright`, else Playwright's own, else a system Chrome or `CLAUDIA_CHROME`).
