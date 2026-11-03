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

8. Back from the TypeScript Claudia (2026-11-03): agent chips under the chat's title (subagents and
   background shells, their view with Steps / Files / Prompt / Report, Stop, progress bars from
   `##progress` lines, the dashed amber ring while work runs after the turn), the turn's file changes
   (a pill with +/− and diffs), the plan's 5-hour and week usage and the chat's context use (a click
   compacts), the model and effort per chat, and the `/` command menu. Edits inside Eye on Rainbow's
   worktrees are reported to it where `CLAUDIA_WORKTREES_DIR` and `CLAUDIA_EOR_URL` are set.
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

## The 3D scene, the phone and alerts (2026-11-03, ported from the TypeScript Claudia's decisions 26 and 29)

- The scene3d form: parts (box, sphere, cylinder, cone, pyramid, torus, capsule, plane, ring, extrude, lathe, tube, rod, group, person) in a three.js view the person turns, zooms and pans; arrows (flowing dashes), labels, a legend, a click on a part opens its facts with Explain (asks Claude in the chat). Diagram look by default (flat colour bands, chalk outlines), a realistic one with materials and shadows. A story steps through it (show, hide, focus, camera, poses) with subtitles and, once turned on, a narrator voice from the hub (`/hub/narrate`, `HUB_TTS_URL`). A `script` runs in QuickJS (WebAssembly; no window, no network) for parts made by code and motion every frame; solid parts that pass through each other glow red and are counted. One 3D scene per picture. It stays a general tool: small generic primitives, nothing topic-specific. three.js, QuickJS and the view are their own module (`out/web/js/scene3d.js`), loaded on first use. Files: `src/claudia/web/scene3d/` (engine, view, person, poses, story, ink, overlap, vm, voice), `src/claudia/hub/narrate.cljs`.
- People: a `person` part is a rigged CC0 MakeHuman body (`resources/web/person/person.glb`, about 400 KB, made by the TypeScript Claudia's `tools/person/make-person.py`), posed live: named poses measured from the body (stand, t, reach, wave, point, hands_on_hips, hold, walk, sit, sit_floor, kneel, squat, bend, lie) and any target overridden; arms and legs are exact two-bone chains, hands, feet and the gaze can target a part; parts can ride on a joint (`parent: "anna.handR"`).
- Phone: the list, the chat and the picture are screens, each sliding over the one before; sideways swipes follow the finger (`src/claudia/web/swipe.cljs`): on the chat right goes to the list and left pulls the picture in, on the list left brings the open chat back, on the picture and on Settings right goes back; past 40% or flicked it completes, else springs back; never in fields, on what scrolls sideways itself (code, tables, graphs, the 3D view), with two fingers or on a mostly vertical move. A Home Screen web app (`resources/web/app-icon/`: manifest and icons; standalone safe areas, 100lvh).
- The chalk hover tip (a clipped chat title, or any `data-tip`), the logo mark in Settings and the favicon.
- Sounds per chat colour (a bell when a chat finishes, two rising notes when it waits on you) and desktop popups (the browser's notifications, asked once), both off until turned on in Settings, per device; the open chat only when the window is not in front (`src/claudia/web/alerts.cljs`).

## Code forms and templates (2026-11-03)

Ported from the TypeScript Claudia (its decisions 17-21): the `files`, `editor` and `flow` forms are
always on; `git`, `clojure` and `calls` are opt-in per runner (`CLAUDIA_FORMS=git,clojure,calls`;
clojure and calls need `clj-kondo` on the runner). Forms are described in `claudia.shared.forms`
(the draw tool, the system prompt and the Forms gallery read it). Code is coloured by highlight.js
in VS Code's Dark+ colours (lighter than Shiki, same look); the editor is CodeMirror 6. Templates
(`save_template`, `templates`, "save as template" on a freeform badge) live in the runner's SQLite.
Each chat has a form row (allowed -> required -> off per kind, questions allowed <-> off) and a
column count (auto, 1, 2, 3) for its picture. Claude Code cuts long tool descriptions, so the forms'
data is also in the system prompt.

## Build

```
npm ci
npx shadow-cljs release host runner front hub web
```

## Back from the TypeScript Claudia (2026-11-03)

- Picture history: a picture per turn (SQLite `pictures`); the bar under the picture steps through them (‹ › ←/→, a scrubber), the newest is live, older ones read only; Picture | Timeline (Alt+T); fold (Alt+P); the camera copies or saves the picture as PNG.
- Timeline: every step of the chat and its subagents from the session files, a rail per agent, quiet stretches, Edit/Write steps with +/− opening their diff (`src/claudia/slave/timeline.cljs`, `src/claudia/web/views/timeline.cljs`).
- Every draw answers Claude with a screenshot of the picture: playwright-core opens the page's render mode (`claudia.web.render`) from this checkout's `out/web` in a headless Chromium (`/opt/claudia-tools/ms-playwright`, else Playwright's own, else a system Chrome or `CLAUDIA_CHROME`).
