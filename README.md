# Claudia

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

9. The code is small pure functions (2026-11-03, after a local `keep` shadowing `clojure.core/keep`
   crashed the hub on every start): data in, data out, at most 30 lines each, effects at the edges,
   one shared copy of every helper, no shadowed names. A PR check (lint, a ratchet on shadowed names
   and long functions that only goes down, tests, the five builds) holds it. Rules in CLAUDE.md.
   Instead of: long handlers mixing reading, deciding and writing state, and a copy of each helper
   per file.
10. Claude tokens are kept by the hub (2026-11-03): saved once in Settings, sealed in `hub.db` under a
    key that lives only in the gate's secrets (`HUB_TOKEN_KEY_FILE`), one in use for every server.
    The hub writes the one in use into each server's token file (the only copy a server keeps, since
    Claude runs there) and brings a server that was off or is new along within a minute. The rail's
    foot shows it (initials; a dashed ring while a server is not on it yet) and switches it; the
    Forms button left the rail (Forms stays in Settings). Instead of: each server keeping and
    switching its own saved tokens.

11. The page runs only its own code (2026-11-03): every library it uses comes from npm at the
    version in `package-lock.json` and is built into `out/web` (Shiki, Vega, three.js and QuickJS
    in modules loaded on first use, Shiki's grammars as its own files); the hub sends a
    Content-Security-Policy that allows the page's own scripts and WebAssembly only, and no framing
    (`claudia.shared.csp`). Claude's freeform pages run in `frame.html`, a sandboxed document with its
    own policy (inline scripts and cdn.jsdelivr.net, no Claudia API): a srcdoc frame would take on the
    page's policy. A `shadow-cljs watch` build loads under the policy, but its live reload (a socket
    to the shadow-cljs server) is refused: reload by hand. Instead of: importing Shiki and Vega from
    jsdelivr by major version at run time, where a bad release would run as the signed-in person.

12. Systems popup (2026-11-05): a computer on the rail, above the sounds, opens a card per server
    the person may use (the access grid) and, for admins, one for the gate. Each card asks every 2 s
    while open (`GET /hub/servers/<name>/system`, `GET /hub/gate/system`) and shows how old its
    numbers are; older than 8 s greys it, a server that does not answer says why. Every server's
    front reads its machine straight from the kernel (`claudia.node.proc`, `claudia.node.system`):
    each core's use as the difference of two `/proc/stat` reads 2 s apart (the first after a quiet
    spell: 1 s), split into user, system, steal and waiting on disk (not counted as busy); memory
    used = MemTotal − MemAvailable; real filesystems from `/proc/self/mountinfo`, one per device,
    space by `statfs` (used of used + available, as `df` counts it); disk and network speed from
    `/proc/diskstats` and `/proc/net/dev` over the same window (physical disks and network cards
    only); CPU and drive temperatures from hwmon; NVIDIA cards by `nvidia-smi`; Claudia's own
    processes (front, runners, hosts, and as chats everything the hosts started) by resident memory.
    It reads only while someone asked in the last 30 s. On macOS (no `/proc`) the same sample comes
    from `os.cpus()` per core (no iowait or steal there), `vm_stat` (used = app memory + wired +
    compressed, as Activity Monitor counts it), `sysctl vm.swapusage`, `netstat -ibn` (en* cards),
    `ioreg` (bytes read and written), `mount` with `statfs` (one row per APFS container, its volumes' files summed) and `ps`
    (`claudia.node.darwin`); no temperatures. Instead of: the `systeminformation`
    library, which runs `df`, `lsblk` and `nvidia-smi` per call and measures CPU since the
    previous call from anywhere in the process; an admin-only view.

13. A Mac is a server too (2026-11-05; [deploy/macos/README.md](deploy/macos/README.md)): an Apple
    Silicon Mac with one user runs the front, runner and host as launchd agents written from the same
    `~/.config/claudia/env` (`deploy/macos/install.sh`), follows main with the same
    `deploy/update.sh` (bash 3.2 and BSD tools as well as Linux's), and takes the release's
    darwin-arm64 node_modules (`claudia-deps-<lockfile hash>-darwin-arm64`, packed by check.yml,
    signed by stage.yml: the Agent SDK's Claude Code is per platform). The front listens on the Mac's
    WireGuard address only, and a pf anchor (`deploy/macos/guard.sh`, root) lets its port in only
    from the hub's tunnel address, as the nft rule does on Linux. Instead of: a Linux VM on the Mac,
    or building node_modules on the Mac itself.
14. Claude chooses every block (2026-11-09): the per-chat form row is gone: the right rail on
    desktops, the row over the picture on phones, the column picker, required and off kinds,
    questions off, and the auto files/git/clojure blocks. The draw tool offers every form not deleted
    in the Forms window, and Claude picks the kinds and the layout. `chat_settings` moved its one
    kept value (the forms a chat's Claude last heard of) into `kv` as `formsSeen` and was dropped.
    Why: 8 of 177 chats on one server ever set the row in three weeks; the forms themselves are
    to be cut down next. Instead of: keeping the row, or keeping it on phones only.

15. The world (2026-11-11): a whole-window 3D mode (the rail's globe or Alt+W; Escape or "Chats" goes
    back). The gate is an island with a lighthouse in the middle, every server the person may use an
    island on a ring round it joined by a bridge, every project a building on its server's island,
    and every chat (not archived, not ash) a hovering robot in its chat colour at its project's door
    (no project: the island's square). Over each robot floats its chat's screen, a hologram from its
    antenna: the title in the chat's colour, the open question while it waits, else the start of the
    last reply, and what it does (working, waiting on you, done, idle, offline). Screens face the
    camera, far ones are drawn up to 1.4 times bigger, and screens that would cover each other on the
    page move apart every frame (nearest first stays; each moves up, left or right, the shortest way
    clear: `claudia.web.world.stack`). A click on a screen (or its robot) flies the real chat panel
    out of that screen to its place on the right and the view glides to the robot; Escape or × flies
    it back into the screen. A click on a server's island starts a new chat there. Layout, motion,
    screen text and stacking are pure data (`claudia.web.world.plan`, `.motion`, `.screen-text`,
    `.stack`, `.state`); the engine is three.js's WebGPURenderer (WebGL 2 where WebGPU is missing or
    its device is lost) with node materials and TSL: the still world is two merged meshes with
    vertex colours; robots, screens, beams, clouds, windows and packets are InstancedMeshes; every
    screen is a slot of one canvas atlas (redrawn only when its text changes, uploaded at most every
    0.4 s) read by a per-instance uv offset, so all screens are one draw call. Its own module
    (`out/web/js/world.js`) on top of the 3D module, which holds three.js's core, so WebGPU comes from
    the same sources (`three/src/Three.WebGPU.js`), not a second copy. Instead of: real chat pages
    placed in 3D (CSS3D: always drawn over the world, no fog, heavy with many chats), the scene3d
    form (a picture Claude draws per chat, not a live view of every chat), or WebGLRenderer with
    classic materials.

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
- **Following main** (built before the merge, 2026-11-03): `check.yml` checks a PR at its head
  commit and packs what it built as one bundle keyed by that commit's git tree. When the check is
  green (a PR into main from this repository, or a push to main), `stage.yml` (run on main, so its
  definition is main's) takes the run's bundle, checks its `tree.txt` against GitHub's record of the
  run, and publishes the slave release `claudia-tree-<tree>.tar.gz` on the GitHub release
  `tree-<tree>` (with the same build in two: `claudia-code-<tree>.tar.gz`, a few MB, and
  `claudia-deps-<lockfile hash>.tar.gz`, its node_modules, which a server downloads only when the
  lockfile changed and keeps in its state folder) and the hub image `ghcr.io/<owner>/claudia-hub:tree-<tree>` (COPY only from the
  bundle), both signed keyless by cosign as `stage.yml@refs/heads/main` (the image with
  `tree=<tree>`). GitHub's webhook (`POST /hub/github`, HMAC with `HUB_GITHUB_SECRET_FILE`): a stage
  run done → the hub tells every front `{stage: tree}` and the gate `stage <tree>` (lines added to
  `CLAUDIA_UPDATE_TRIGGER` and `HUB_UPDATE_REQUEST`), and they download and verify that build ahead;
  a push to main → `{main: commit}` and `update <commit> <tree>`. Each server's `deploy/update.sh`
  (path unit on the trigger, hourly timer) and the gate's helper take main's commit from git's ref
  list (waiting up to 40 s for a pushed one), its tree from the API, and install that tree's build:
  fetched ahead, so a merge of an up-to-date PR only swaps folders and restarts (seconds); else
  downloaded when stage.yml has published it for main's own check (minutes). Only forward; the
  front and the runners restart, never the hosts. A single-user machine's `CLAUDIA_CHECKOUT`
  must be a release folder (a git checkout is never replaced): first install with
  `CLAUDIA_CHECKOUT=<folder> deploy/update.sh`, then enable `claudia-update.path` and `.timer`
  (on a Mac the same first install loads the launchd agents, the update agent included:
  [deploy/macos/README.md](deploy/macos/README.md)).

## The 3D scene, the phone and alerts (2026-11-03, ported from the TypeScript Claudia's decisions 26 and 29)

- The scene3d form: parts (box, sphere, cylinder, cone, pyramid, torus, capsule, plane, ring, extrude, lathe, tube, rod, group, person) in a three.js view the person turns, zooms and pans; arrows (flowing dashes), labels, a legend, a click on a part opens its facts with Explain (asks Claude in the chat). Diagram look by default (flat colour bands, chalk outlines), a realistic one with materials and shadows. Labels are callouts, as in an atlas: never on the model, in a column on each side of it (clear of the legend, the subtitles and the bar), each with a thin line to a dot on its part; a label whose dot is hidden behind something solid is not shown, and one that does not fit is left out. Without a story every label shows; in a story, the step's focus, else what the step brings in, else (an overview step) every label, and always the part under the pointer. A story steps through it (show, hide, focus, camera, poses) with subtitles and, once turned on, a narrator voice from the hub (`/hub/narrate`, `HUB_TTS_URL`). A `script` runs in QuickJS (WebAssembly; no window, no network) for parts made by code and motion every frame; solid parts that pass through each other glow red and are counted. The film button in the bar makes the story's video in the background (the page may close at once): the page sends the scene as shown to the hub (`POST /hub/videos`), the hub hands it to the person's runner and, when it has a voice, says the lines and brings the clips (always: the page's voice switch is for listening now); the runner's own headless page plays the story on a stepped clock (every frame 1/30 s after the last, however slow drawing is), paints each frame with its labels, legend and subtitles, and ffmpeg makes them an H.264 MP4 with the voice as AAC, kept with the chat (`RUNNER_DATA/videos`). The bar shows how far it is, then a button that plays it over the page with Download (a web-app window shows no downloads of its own; the file is named after the scene), on any device and after a reload, and a desktop popup when popups are on. A scene without a story gets 15 s. Needs ffmpeg on the server (`CLAUDIA_FFMPEG`, else `ffmpeg` on the PATH). Real human anatomy: a part `{shape: "anatomy", ref, side?, system?}` becomes the structures it names (bones, muscles, nerves, ligaments, muscles' origin and insertion patches; by English or Latin name, id, region, compartment or nerve territory), each loaded as its own small model from [claudia-anatomy](https://github.com/p4ulcristian/claudia-anatomy) (Z-Anatomy, CC BY-SA 4.0); the hub (`/anatomy/<version>/…`, kept in `HUB_DATA/anatomy`) and the runner's render page (kept in `~/.cache/claudia/anatomy`) fetch each file once from the pinned version (`claudia.shared.anatomy`). One 3D scene per picture. It stays a general tool: small generic primitives, nothing topic-specific. three.js, QuickJS and the view are their own module (`out/web/js/scene3d.js`), loaded on first use. Files: `src/claudia/web/scene3d/` (engine, view, person, poses, story, ink, overlap, vm, voice, clock, maker, frames, paint, videos, icons, callouts, anatomy; pure maths, data, labels, bar, video, structures), `src/claudia/slave/video.cljs`, `src/claudia/hub/videos.cljs`, `src/claudia/hub/narrate.cljs`.
- People: a `person` part is a rigged CC0 MakeHuman body (`resources/web/person/person.glb`, about 400 KB, made by the TypeScript Claudia's `tools/person/make-person.py`), posed live: named poses measured from the body (stand, t, reach, wave, point, hands_on_hips, hold, walk, sit, sit_floor, kneel, squat, bend, lie) and any target overridden; arms and legs are exact two-bone chains, hands, feet and the gaze can target a part; parts can ride on a joint (`parent: "anna.handR"`).
- Phone: the list, the chat and the picture are screens, each sliding over the one before; sideways swipes follow the finger (`src/claudia/web/swipe.cljs`): on the chat right goes to the list and left pulls the picture in, on the list left brings the open chat back, on the picture and on Settings right goes back; past 40% or flicked it completes, else springs back; never in fields, on what scrolls sideways itself (code, tables, graphs, the 3D view), with two fingers or on a mostly vertical move. A Home Screen web app (`resources/web/app-icon/`: manifest and icons; standalone safe areas, 100lvh).
- The chalk hover tip (a clipped chat title, or any `data-tip`), the logo mark in Settings and the favicon.
- Sounds per chat colour (a bell when a chat finishes, two rising notes when it waits on you) and desktop popups (the browser's notifications, asked once), both off until turned on in Settings, per device; the open chat only when the window is not in front (`src/claudia/web/alerts.cljs`).

## Code forms and templates (2026-11-03)

Ported from the TypeScript Claudia (its decisions 17-21): the `files`, `editor` and `flow` forms are
always on; `git`, `clojure` and `calls` are opt-in per runner (`CLAUDIA_FORMS=git,clojure,calls`;
clojure and calls need `clj-kondo` on the runner). Forms are described in `claudia.shared.forms`
(the draw tool, the system prompt and the Forms gallery read it). Code is coloured by Shiki
(VS Code's grammars and Dark+ theme; 2026-11-03: highlight.js coloured differently), built from npm
into the page's own files and loaded on first use, each grammar when its language is first shown; the editor is CodeMirror 6. Templates
(`save_template`, `templates`, "save as template" on a freeform badge) live in the runner's SQLite.
Built-in forms can be deleted and restored in the Forms window (per profile; Claude's draw tool
leaves them out). Claude picks the kinds and the layout of every picture itself (decision 14).
Claude Code cuts long tool descriptions, so the forms' data is also in the system prompt.

## Build

```
npm ci
npx shadow-cljs release host runner front hub web
```

## Back from the TypeScript Claudia (2026-11-03)

- Picture history: a picture per turn (SQLite `pictures`); the bar under the picture steps through them (‹ › ←/→, a scrubber), the newest is live, older ones read only; Picture | Timeline (Alt+T); fold (Alt+P); the camera copies or saves the picture as PNG.
- Timeline: every step of the chat and its subagents from the session files, a rail per agent, quiet stretches, Edit/Write steps with +/− opening their diff (`src/claudia/slave/timeline.cljs`, `src/claudia/web/views/timeline.cljs`).
- Every draw answers Claude with a screenshot of the picture: playwright-core opens the page's render mode (`claudia.web.render`) from this checkout's `out/web` in a headless Chromium (`/opt/claudia-tools/ms-playwright`, else Playwright's own, else a system Chrome or `CLAUDIA_CHROME`).
