# Claudia: the protocol

The one contract between the four programs. Everything is plain JSON (UTF-8). In ClojureScript, keys
become keywords once, where data comes in (`claudia.shared.protocol/read-json`), and go back to strings
where it leaves (`write-json`). Times are milliseconds since the epoch (numbers). Ids are strings.

```
browser (page) ──https──▶ gate: Caddy (login) ──▶ hub ──WireGuard──▶ front (one per machine)
                                                                         │ unix socket per profile
                                                                         ▼
                                                                       runner (one per profile)
                                                                         │ unix socket
                                                                         ▼
                                                                       host (one per profile: Agent SDK)
```

- **hub** (the master, on the gate): serves the page, knows the servers and who may use which,
  passes `/s/<server>/api/…` and `/s/<server>/ws` on to that server's front, saying who is calling.
- **front** (on every server): believes the hub's `X-Claudia-Email` (only the hub can reach its
  listener: firewall), picks the caller's profile, passes the request to that profile's runner.
  Serves no page.
- **runner** (per profile): chats, pictures, questions, secrets, the SQLite store. Restarting it
  keeps running chats (the host holds them).
- **host** (per profile): owns each chat's Agent SDK `query`. Knows nothing about Claudia; every tool
  call, hook and permission ask goes to the runner as an rpc.

## Settings (environment; never committed)

| Program | Variable | What |
|---|---|---|
| hub | `HUB_HOST`, `HUB_PORT` | where it listens (Caddy in front) |
| hub | `HUB_CONFIG` | path of `hub.json` (below), imported once into an empty store |
| hub | `HUB_DATA` | folder of the hub's store `hub.db` and the peer requests (default `~/.local/share/claudia-hub`) |
| hub | `HUB_ADMIN_GROUP` | a login group (`X-Auth-Request-Groups`) whose members are admins (optional) |
| hub | `HUB_WG_POOL`, `HUB_SERVER_PORT` | addresses paired servers get (`10.0.0.100-10.0.0.199`; unset: no pairing), their front's port (4540) |
| hub | `HUB_PEERS_DIR`, `HUB_APPLIED_DIR` | peer requests for the gate's helper (default `HUB_DATA/peers`), its answers (default `HUB_DATA/peers-applied`) |
| hub | `HUB_APNS_KEY_FILE`, `HUB_APNS_KEY_ID`, `HUB_APNS_TEAM_ID` | the APNs key (.p8) and its ids; `HUB_APNS_DRY_RUN=1` logs pushes instead |
| hub | `HUB_GITHUB_SECRET_FILE`, `HUB_UPDATE_REQUEST`, `HUB_REPO` | the webhook secret, the file the gate's update helper watches, `owner/name` |
| hub | `HUB_RELEASES`, `HUB_PUBLIC_URL` | where the release builder's files are kept (default `<data>/releases`); the hub's https address, so pokes tell servers where to fetch them (`<url>/hub/releases/files`; unset: pokes name none) |
| front | `CLAUDIA_UPDATE_TRIGGER` | file `POST /api/update-check` writes (a path unit starts `deploy/update.sh`) |
| hub | `HUB_WEB` | folder of the built page (`out/web`) |
| hub | `HUB_TOKEN_KEY_FILE` | base64 of the 32-byte key the saved Claude tokens are sealed with (never in `hub.db`); unset: the hub keeps no tokens and leaves the servers' token files alone |
| hub | `HUB_TTS_URL`, `HUB_TTS_TOKEN_FILE` (or `HUB_TTS_TOKEN`) | the narrator's voice service for 3D stories and its bearer token (unset: no voice) |
| front | `FRONT_HOST`, `FRONT_PORT` | the WireGuard address it listens on |
| front | `FRONT_PROFILES` | path of the profiles file (below) |
| front | `FRONT_NAME` | this server's name (e.g. `home`, `work`) |
| runner | `RUNNER_SOCKET` | unix socket it serves HTTP + WebSocket on |
| runner | `HOST_SOCKET` | the host's unix socket |
| runner | `RUNNER_DATA` | folder for `claudia.db`, uploads and 3D scenes' videos (`videos/`) |
| runner | `CLAUDIA_FFMPEG` | the ffmpeg that makes 3D scenes' videos (default `ffmpeg` on the PATH; needs libx264 and AAC) |
| runner | `RUNNER_PROFILE` | the profile's name (`paul`) |
| runner | `CLAUDIA_CWD` | where chats run (default `~/Work`) |
| runner, front | `CLAUDIA_TOKEN_FILE` | file holding the Claude long-term token in use (`sk-ant-oat…`): the front writes it when the hub sends it (`PUT /api/claude-token/current`), runners read it on every session start; whose it is: `<token file>.json` `{ key, name, by, at }`. Every saved token (`PUT /api/claude-token/saved`): `<token file>.d/<key>` each, their names in `<token file>.saved.json` `{ tokens: [{ key, name, at }] }`; a chat with a pick (`settings.plan`) starts on that one |
| runner | `CLAUDIA_OP_ACCOUNT` | 1Password account (sign-in address or id) for this profile's reads |
| runner | `CLAUDIA_VAULT_SERVER` | the server whose 1Password app unlocks for this profile (e.g. `home`) |
| runner | `CLAUDIA_FORMS` | opt-in forms this runner has, comma separated (`git,clojure,calls`); the others are always on |
| runner | `CLAUDIA_WORKTREES_DIR`, `CLAUDIA_EOR_URL`, `CLAUDIA_EOR_TOKEN_FILE` (default `~/.config/eor/token`), `CLAUDIA_CHAT_URL` (`{chat}` = the chat's id) | where set: chats' edits and commands inside those worktrees are reported to Eye on Rainbow (`POST <url>/api/worktrees/touches`) |
| host | `HOST_SOCKET` | unix socket it listens on |

`hub.json`:
```json
{ "servers": [ { "name": "home", "label": "Home", "address": "10.0.0.2:4540" } ],
  "people":  [ { "email": "a@example.com", "name": "Paul", "admin": true, "servers": ["home"] } ] }
```
The profiles file (front): one line per profile, `<profile> <email> <runner socket path>`.

## Who is calling

- Caddy clears `X-Auth-Request-*` and `X-Claudia-*` from the browser, then sets
  `X-Auth-Request-Email` from the login.
- hub → front: `X-Claudia-Email: <email>`, and `X-Claudia-View: <profile>` when the caller looks at
  another profile's chats (read only). The page asks for a view with the query parameter
  `?view=<profile>` on `/s/<server>/api/…` and `/s/<server>/ws` (a browser cannot set headers on a
  WebSocket, and Caddy clears `X-Claudia-*`); the hub takes `view` out of the query and sends it as
  `X-Claudia-View`. Whether the caller may see that profile is the front's call.
- The hub passes on no browser header but `Content-Type`, `Content-Length` and `Accept`: not the
  login cookie, not `X-Auth-Request-*`, not any `X-Claudia-*`. From the front's answer it drops
  `Set-Cookie` and the hop-by-hop headers.
- `/hub/*` and `/s/*` refuse (403) a request another site's page made: `Sec-Fetch-Site` other than
  `same-origin`/`none`, or an `Origin` whose host is not this one (`X-Forwarded-Host`, else `Host`).
- A view never reads secrets: `/s/<server>/api/secret/…` with `view` is refused (403).
- front → runner: `X-Claudia-Profile: <profile>` (whose chats), `X-Claudia-Read-Only: 1` for a view.
  A read-only caller may only `GET` and send `hello`/`open` on the socket.

## Hub HTTP

| Call | Answer |
|---|---|
| `GET /`, `/new` and `/c/*` | the page (`index.html`), `cache-control: no-cache` |
| `GET /js/*`, `/css/*`, `/fonts/*`, `/img/*` (any file in `HUB_WEB`) | the built page's files; `no-cache` with an ETag, fonts and images kept a week |
| `GET /frame.html` | the document a freeform block runs in (it asks the page for Claude's HTML by postMessage and writes it in) |

Every file of the page goes with `x-content-type-options: nosniff` and a Content-Security-Policy
(`claudia.shared.csp`): the page's own (only its own scripts and WebAssembly, no CDN, no eval;
inline styles; fonts from Google Fonts; same-origin fetches and sockets; no framing,
`x-frame-options: DENY`); `frame.html` has the freeform policy instead (`sandbox allow-scripts`,
inline scripts and `https://cdn.jsdelivr.net`, no Claudia API, framed only by the page,
`x-frame-options: SAMEORIGIN`).
| `GET /hub/me` | `{ email, name, admin, servers: [{ name, label }] }` (only servers this person may use) |
| `GET /anatomy/<version>/<file>` | an anatomy model or the index (`index.json`, `<system>/<id>.glb`) of the pinned version only (`claudia.shared.anatomy`): fetched once from the claudia-anatomy repository at that version's tag, kept in `HUB_DATA/anatomy`, then sent from disk with a year's immutable caching; 404 for another path, 502 when it can't be fetched |
| `GET /hub/narrate` | `{ voice: bool }`: a voice service is set up (`HUB_TTS_URL` and its token) |
| `POST /hub/narrate` `{ text, instruction? }` | the line as speech: raw 16-bit mono PCM at 24 kHz (`audio/L16;rate=24000;channels=1`, `x-seconds`); `instruction` = a story's `voice` (one reference clip is made from it, every line is a clone of it). Clips are kept in `HUB_DATA/narration` by their text. 400 without text, 501 without a voice, 502 when the voice service fails. The service's route is `<HUB_TTS_URL>/v1/audio/speech` (form fields `text`, and `ref_audio` + `ref_text` for a cloned voice, or `instruction`) |
| `POST /hub/videos` `{ server, chat, block, title, data }` | a 3D scene's video (`data`: the scene3d form's data as shown): handed to the caller's runner on that server (`POST /api/chats/:id/videos`, as the caller), answered at once `202 { id }`; then, when a voice is set up and the story has lines (the page's voice switch does not count: a video is watched later), the lines are said (as `POST /hub/narrate` would) and brought to the runner (`PUT …/videos/:id/voice`). 400 without chat, block or data; 403 no access to the server |
| `GET /hub/servers/:name/state` | `{ up: bool }`: `true` when the front answered `GET /api/me` (as the caller) with 2xx within 2 s |
| `GET /hub/servers/:name/system` | that server's newest `Sample` (from its front's `GET /api/system`, 4 s); 502 `{ error }` when the front is not reached or fails; 403 when not in the person's list |
| `GET /hub/gate/system` | admins: the gate's own `Sample` (the hub reads its machine itself); 403 for others |
| `ANY /s/<server>/api/…` | passed to that front's `/api/…`, bodies streamed both ways (refused 403 if not in the person's list; 502 when the front is not reachable) |
| `WS /s/<server>/ws` | passed to that front's `/ws`; the front's own refusal (status and body) reaches the page; frames pass as they are, close codes too |

No `X-Auth-Request-Email` → 401 `{ error }`; an email not in the store → 403 `{ error }`. The page's
files are served without either check (they hold no data). The hub listens on `HUB_HOST`
(default `127.0.0.1`) and `HUB_PORT` (default `4590`); `HUB_WEB` defaults to `out/web`; `HUB_CONFIG`
has no default. The servers and people live in the hub's store (`HUB_DATA/hub.db`); `hub.json` is
imported only into an empty store (a broken file then stops the hub) and never read again.
`/s/<server>/api/update-check`, `/s/<server>/api/claude-token/current`,
`/s/<server>/api/claude-token/saved`, `/s/<server>/api/claude-token/limits`, `/s/<server>/api/system`, `/s/<server>/api/github` and `/s/<server>/api/github/token` are refused (404): only
the hub makes those calls on a front.

## Hub operations

Signed-in calls refuse cross-site requests like `/hub/me`. `/hub/me` also answers `push` (bool:
pushes are on). Every admin call answers the whole admin state
`{ servers: [{ name, label, address, pairing: { server, publicKey, ip, createdAt, fetchedAt } | null, peerUp }],
people: [{ email, name, admin }], access: [{ email, server }], pairing: { available } }`.

| Call | Who | Answer |
|---|---|---|
| `GET /hub/admin` | admin | the admin state |
| `PUT /hub/admin/servers/:name` `{ label?, address? }` | admin | adds or changes a server (address `host:port`) |
| `DELETE /hub/admin/servers/:name` | admin | removes it (and its peer request) |
| `POST /hub/admin/people` `{ email, name?, admin? }` | admin | adds a person |
| `PUT /hub/admin/people/:email` `{ name?, admin? }` | admin | changes a person |
| `DELETE /hub/admin/people/:email` | admin | removes them and their ticks |
| `PUT /hub/admin/access` `{ email, server, granted }` | admin | a tick on or off |
| `POST /hub/admin/builder` `{ key }` | admin | makes the machine whose `deploy/release-builder/install.sh` printed `claudia-builder:<host>:<secret>` the release builder (the one before stops counting); answers like `GET /hub/admin` (its `builder`: `{ host, pairedAt, seenAt }` or null); 400 not a builder key |
| `POST /hub/admin/pair` `{ key, name?, label? }` | admin | pairs a server from `deploy/pair.sh`'s key `claudia-pair:<host>:<wg public key>:<secret>`; 409 when unavailable (no `HUB_WG_POOL`) |
| `POST /hub/enroll` `{ secret }` | no login | 200 `{ name, address, port, gate: { publicKey, endpoint, address } }`, 202 while the gate adds the peer, 404 unknown |
| `GET /hub/releases/wanted` | the release builder (`Authorization: Bearer <secret>`) | 200 `{ builds: [{ key: "main" \| "pr-<n>", commit, kind: "main" \| "pr", at }] }`, newest first; held up to 25 s while empty. 401 not the builder |
| `PUT /hub/releases/files/<file>` | the release builder | the file's bytes (up to 400 MB) into `HUB_RELEASES` (default `<data>/releases`): 200 `{ ok, size }`. `<file>`: `claudia-code-<tree>.tar.gz`, `claudia-deps-<lockfile hash>.tar.gz`, `claudia-deps-<lockfile hash>-darwin-arm64.tar.gz`, or one of them + `.sig` (ssh-keygen -Y, namespace `claudia-release`) |
| `GET`/`HEAD /hub/releases/files/<file>` | no login | the file (immutable), 404 when not here |
| `POST /hub/releases/ready` `{ commit, tree, deps }` | the release builder | 200 once the tree's code and both platforms' node_modules are here with their signatures: the commit leaves the queue, every front is poked with `{ stage: tree, releases }` and the gate gets `stage <tree>`; 409 names what is missing |
| `POST /hub/github` | no login, HMAC (`X-Hub-Signature-256`) | 202; push to `refs/heads/main` pokes every front with `{ main: commit }`, adds `update <commit> <tree>` to `HUB_UPDATE_REQUEST` and asks the release builder for it; a pull request from this repository opened, pushed to or reopened asks the builder for its head (closed: dropped); a successful `stage.yml` run (title `stage <tree>`) pokes every front with `{ stage: tree }` and adds `stage <tree>`; anything else ignored (202). 401 bad signature, 503 no secret |
| `POST /hub/push/devices` `{ token (hex), env: development\|production, topic (bundle id), key (base64 of 32 bytes), liveStartToken? }` | person | `{ ok, push }` |
| `DELETE /hub/push/devices/:token` | person | their own phone only |
| `POST /hub/push/live` `{ device, activity, token }` | person | a Live Activity's update token: `{ ok, ended }` |
| `DELETE /hub/push/live/:activity` | person | the app saw it end |
| `POST /hub/presence` `{ page (8-64 chars), active }` | person | 204; alerts are held while any page said active within 45 s |
| `GET /hub/claude-tokens` | person | `{ on, inUse: { name, by, at } \| null, tokens: [{ name, by, at, limits, heardAt }], servers: [{ name, label, ok, current, error }] }`: never a token; `servers` = the caller's, with the name each runs on now (`ok` false: not reached) |
| `PUT /hub/claude-token` `{ name, token }` | person | the same shape: sealed and saved under the name (same name = replaced), in use on every server; 503 when `on` is false |
| `POST /hub/claude-token/use` `{ name }` | person | the same shape: in use on every server; 404 no such name |
| `DELETE /hub/claude-tokens/:name` | person | the same shape; 409 for the one in use |
| `GET /hub/github-login` | person | `{ on, login, savedAt, servers: [{ name, label, ok, login, error }] }`: the caller's GitHub login kept by the hub (never the token) and what each of their servers' gh is logged in as |
| `PUT /hub/github-login` `{ token }` | person | the same shape: checked with GitHub, sealed and kept, handed to each of their servers (`PUT /api/github` as them); 400 not a token or refused by GitHub, 503 when the hub keeps no tokens |

Claude tokens (README decisions 10 and 16): every saved one on every server, one of them in use (the
default: what a chat without its own pick runs on). The hub brings each front's saved tokens along
the same minute and after each save or removal: `GET /api/claude-token/saved`, then `PUT` with all
of them when the keys, names or save times differ. The hub seals each saved token
(AES-256-GCM, the folded name as AAD, base64 of nonce (12) ‖ ciphertext ‖ tag (16)) under
`HUB_TOKEN_KEY_FILE`, and brings every server along: `GET /api/claude-token/current` on its front,
then `PUT` with the token when the name or `at` differ; after each save or switch (the answer waits
up to 6 s for it) and every minute, so a server that was off, new or reinstalled follows by itself.
The same minute it reads each front's `GET /api/claude-token/limits` and keeps, per saved token, the
newest limits heard (`claude_token_limits`); `GET /hub/claude-tokens` gives each saved token its
`limits: [Limit]` and `heardAt` (ms, or null): the page's popup of every token's limits.

The hub watches each server for each person with a phone over that front's `/ws` (as the person,
`hello`, then every `chats`). A push alert: `{ aps: { alert: { title: "Claudia", body: "New message" },
mutable-content: 1, badge, thread-id: "<server>/<chat>", interruption-level }, enc, url: "/c/<server>/<chat>",
server, chat, kind: finished|ask|permission }`, `enc` = base64 of nonce (12) ‖ AES-256-GCM ciphertext
of `{ title, body }` ‖ tag (16). Live Activity attributes type: `ClaudiaSummaryAttributes`, content
state `{ running: [Item], waiting: [Item], done, updatedAt }`, `Item` = `{ title, server, chat, since, kind }`.

## Front / runner HTTP (`/api/…`)

| Call | Answer |
|---|---|
| `GET /api/me` | `{ server, profile, profiles, viewing, readOnly, version, commit }`, answered by the front: `profile` = the caller's own, `profiles` = every profile on this server (the page's "<person>'s chats"), `viewing` = the profile looked at (read only) or null |
| `POST /api/update-check` `{ main?, stage?, releases? }` | answered by the front, no caller needed (only the hub reaches it): adds a line to `CLAUDIA_UPDATE_TRIGGER` (`main <commit>`, `stage <tree>`, else `check <time>`), after `releases <url>` when `releases` is the hub's `https://…/hub/releases/files`, 202; 501 when unset |
| `GET /api/claude-token/current` | the front, the hub only: `{ key, name, by, at }` of the token in use here (nulls when none came from the hub), never the token |
| `GET /api/claude-token/saved` | the front, the hub only: `{ tokens: [{ key, name, at }] }`, the saved tokens kept here, never a token |
| `PUT /api/claude-token/saved` `{ tokens: [{ key, name, at, token }] }` | the front, the hub only: the whole set, written over the one kept here (a token no longer in it is deleted); answers like GET; 400 when one is not a key (`[a-z0-9-]{1,24}`), a name, a time and a Claude token |
| `PUT /api/claude-token/current` `{ key, name, by, at, token }` | the front, the hub only: written over `CLAUDIA_TOKEN_FILE` and its note (an idle session starts again on it with its next message); answers like GET |
| `GET /api/claude-token/limits` | the front, the hub only: `{ tokens: { <token hash>: { limits: [Limit], at } } }`, the newest of every profile's runner (`GET /api/limits` on each runner socket: the same shape), the hash = the first 16 hex of the token's SHA-256 |
| `GET /api/system` | the front, the hub only: this machine's newest `Sample` (below); the first ask after 30 s without one waits about 1 s for its first window |
| `POST /api/secret/read` `{ ref, pubkey, request }` | the vault read (below): `{ sealed }` or `{ error }` |
| `GET /api/chats/:id/picture.png?turn=` | the live picture (or that turn's) as a PNG, drawn by the page's render mode in a headless Chromium (the camera); 404 no such picture, 503 `{ error }` when this server can't shoot (no browser, no built `out/web`) |
| `POST /api/chats/:id/videos` `{ block, title, data, voice }` | a 3D scene's video (from the hub): `202 { id }`; it waits for its voice when `voice`, else is queued. One is made at a time: the render page in a headless browser of its own (1280×720 at 1.5×: 1920×1080) plays the story on a stepped clock and hands over JPEG frames; ffmpeg (`CLAUDIA_FFMPEG`, else `ffmpeg`) makes them H.264 and the voice AAC; the file is kept at `RUNNER_DATA/videos/<chat>/<id>.mp4` (+ `.json`) |
| `PUT /api/chats/:id/videos/:vid/voice` `{ clips: [{ text, instruction, pcm }], error? }` | the narrator's clips (pcm: base64 of 16-bit mono PCM at 24 kHz) for a video waiting for them; it is queued. 409 when that video waits for none |
| `GET /api/chats/:id/videos?block=&fp=` | `{ fp, videos: [{ id, block, title, state, progress, error, seconds, at }] }` newest first (state: voice, queued, render, encode, done, failed; videos being made are lost when the runner restarts); `{ same: true }` when nothing changed since fp |
| `GET /api/chats/:id/videos/:vid.mp4` | the finished video (`video/mp4`, as an attachment named after the scene's title); 404 when there is none |

`Sample` (README decision 12; sizes and speeds in bytes and bytes per second, shares in percent):
`{ at, age (ms since it was read, when answered), window (ms the use covers), uptime (s), host: { name, os (os-release's PRETTY_NAME; macOS and its version), kernel, arch },
cpu: { model, load: [1, 5, 15 min], all: Share, cores: [Share] }, memory: { total, available, used,
cached, swapTotal, swapUsed }, disks: [{ mount, fs, source, size, used, avail }], io: { read, write },
net: { rx, tx }, temps: [{ name, label, c }], gpus: [{ index, name, util, memUsed, memTotal, temp, power }],
claudia: [{ kind: front|runner|host|chats|hub, count, rss }] }`; `Share` = `{ busy, user, sys, iowait, steal }`
(busy = user + sys + steal; waiting on disk is not busy). A field a card does not report is null. On macOS iowait and steal are 0, `temps` is empty, and a disk is an APFS container (its volumes' files summed).

## Runner HTTP: chat operations (`/api/…`)

Answered by the runner (through the front). No call starts a chat, sends a message into one, answers
its questions or reads how it stands: that is the page's socket only (README decision 18). A read-only caller gets 403 for anything but `GET`. `:id` = a chat id.

| Call | Answer |
|---|---|
| `POST /api/chats/:id/uploads?name=<file name>` (body: the file, up to 100 MB) | `{ path, name, size, type }`: saved as `uploads/<id>/<name>` in `RUNNER_DATA` (`-2` before the extension when taken). `:id` is the id the page used: a new chat's own uuid before `created` |
| `GET /api/chats/:id/uploads/:name` | the file, as a download |
| `GET /api/chats/:id/sent/:msg/:n` | the n-th image the user message `msg` sent (from the session file) |
| `GET /api/chats/:id/shown/:step` | the image a `show_image` step (its tool-use id) showed, or the video a `show_video` step showed (a `Range` header gets `206` with that part) |
| `POST /api/chats/:id/fork` `{ at? }` | `{ id }`: a new chat holding this one up to the end of the turn of user message `at` (default: the last finished turn); same folder, picture copied, a colour of its own; 409 with `{ error }` when it can't |
| `POST /api/chats/:id/explain` `{ step }` (a tool-use id) | `{ text }`: Haiku's 2-3 plain sentences on what that step does (or waits on) |
| `PATCH /api/chats/:id` `{ title?, archived?, color? }` | `{ ok }`; `title` (≤ 200) is the session's own custom title (`renameSession`); `color`: one of the chat colours, `"ash"` (passive: remembers the colour it had) or `"wake"` (back to that colour, else a fresh one) |
| `GET /api/search?q=&archived=` | `archived`: `all` every chat, `1` the archived ones, none the chats not archived. `{ found: [{ id, hits: [{ uuid, role: "user"\|"claude", snippet }], count, title (bool: every word is in the title) }] }`; the words are prefixes, all needed; `\u0001…\u0002` marks them in a snippet. An SQLite FTS5 index (`search.db`), read on from each session file |
| `GET /api/sessions` | `{ sessions: [{ id, title, at }] }`: Claude Code sessions of `CLAUDIA_CWD` that are not chats here |
| `POST /api/sessions/:id/adopt` | `{ id }`: that session becomes a chat here |
| `GET /api/project?folder=<abs>` | `{ name, logo (bool) }`: the folder's project (its git repo, or `<group>/<project>` under `CLAUDIA_CWD`); `{ name: null, logo: false }` for a folder a view may not read (below: "A view's reads stay in the work folder") |
| `GET /api/project/logo?folder=<abs>` | the project's logo file (favicon, icon or logo in the repo), when the caller may read it (a view: below) |
| `GET /api/prefs` | `{ hiddenForms: [forms deleted in the Forms window], notHere: [opt-in forms this runner lacks] }` |
| `PATCH /api/prefs` `{ hiddenForms? }` | the same shape; hidden forms leave every live chat's draw tool at once (its system prompt after its turn) |
| `GET /api/github` | `{ user: { login, name } \| null }`: this profile's gh login (the hub's call, as the person; refused from a page) |
| `PUT /api/github` `{ token }` | `{ user }`: `gh auth login` with it, git's credential helper and commit name set (the hub's call: README decision 23) |
| `GET /api/github/token` | `{ token }` gh holds for github.com, 404 `{ error }` when none: the hub takes a login made before into its keeping, once (refused from a page) |

## WebSocket (`/ws`): page ↔ runner

One socket per server the page uses. Every frame is a JSON object with `type`.

Page → runner:

| type | fields | effect |
|---|---|---|
| `hello` | | runner answers `chats` |
| `open` | `chat` | runner answers `chat` (full state) and sends that chat's live events from then on (several open chats per socket are fine) |
| `close` | `chat` | stop sending that chat's live events |
| `new` | `text`, `folder?`, `id` (a uuid the page made), `files?`, `images?`, `model?`, `effort?`, `plan?`, `color?`, `talk?`, `bell?` | starts a chat with that first message, on the model, effort and plan picked before it was made (else the defaults and the plan in use), in the colour its header wore (else one of the least worn; every chat has a colour) and with its speaker and bell as picked (else speaker off, bell on); runner answers `created { chat, id }` then events |
| `send` | `chat`, `text`, `files?`, `images?` | a message into the chat (queued if a turn runs) |
| `older` | `chat`, `before` (a Msg id) | runner answers `older` with the page of messages before it |
| `stop` | `chat` | interrupts the running turn |
| `answer` | `chat`, `ask`, `answers` (`{ "<question>": "<label>" }`) | answers an AskUserQuestion; on a `permit` ask (`answers` `{}`) it is the OK |
| `secret` | `chat`, `ask`, `sealed` | the sealed secret for a `run_with_secret` ask |
| `deny` | `chat`, `ask` | refuses a secret or permit ask |
| `rename` | `chat`, `title` | |
| `archive` | `chat`, `archived` (bool) | |
| `folder` | `chat`, `folder` (absolute, `~/…`, or relative to the runner's working folder; empty or null: no project) | the chat's working folder; Claude hears of it with the next message; `error` when no folder is there |
| `seen` | `chat` | the person looked at its last turn |
| `code_edit` | `chat`, `block`, `file`, `text`, `base` | the user's typing in an editor block, saved into the picture (`base` = the version `v` of Claude's code the text starts from; a save on an older version is dropped) |
| `code_send` | `chat`, `block`, `note` | the editor's Send: every file with unsent edits goes to Claude as one message (`<claudia-code>` per file: diff and full code) |
| `stop_task` | `chat`, `task` | stops one agent or background shell of the chat (`Agent.task`) |
| `settings` | `chat`, `model?`, `effort?` (null = the person's default), `plan?` (a saved token's key from `meta.plans`; null or one not saved = no pick), `talk?`, `bell?` (booleans) | the chat's model and effort: kept, used on its next start, and given to a live session at once. Its plan: kept, and its session starts again on that token with its next message. Its speaker (`talk`) and bell: kept with the chat, then `chats` goes to every page |
| `agent` | `chat`, `agent` (an `Agent.id`) | runner answers `agent` (one agent's view); read-only callers may ask |
| `diff` | `chat`, `change` (a `Change.id`) | runner answers `diff`; read-only callers may ask |
| `agent_git` | `chat`, `agent`, `rev` (a commit of the agent's worktree branch, or `"BRANCHED"`: all of it since it branched) | runner answers `agent_git`; read-only callers may ask |
| `picture` | `chat`, `turn` | runner answers `picture` with that turn's scene (an earlier picture; read-only callers may send it) |

Runner → page:

| type | fields |
|---|---|
| `chats` | `chats: [Row]`, sent on `hello` and on every change of any row |
| `chat` | `chat`, `title`, `folder`, `messages: [Msg]` (the newest 120), `more` (bool: older ones exist), `scene: Scene`, `running`, `asks: [Ask]` |
| `older` | `chat`, `before`, `messages: [Msg]` (up to 120 before `before`), `more` |
| `created` | `chat`, `id` (the page's uuid, to match) |
| `msg` | `chat`, `msg: Msg` (a new or replaced message, by `msg.id`; `gone: true`: a queued message Claude Code dropped) |
| `delta` | `chat`, `id`, `text` (streamed text appended to message `id`) |
| `scene` | `chat`, `scene: Scene` (the whole picture; it is small), `changed` (ids the draw changed: nodes, arrows `from->to`, blocks, `block:part`, `block:item`), `turn` (the picture's turn) |
| `status` | `chat`, `running`, `note?` (`Thinking`, `Retrying…`, `Compacting`; none: plain working) |
| `turn` | `chat`, `running` (bool), `stopped` (when a turn ended: the person stopped it) |
| `ask` | `chat`, `ask: Ask` |
| `asked` | `chat`, `ask` (id: answered, denied or expired; remove the card) |
| `error` | `chat?`, `text` |
| `agents` | `chat`, `agents: [Agent]` (the chat's whole list, on every change) |
| `changes` | `chat`, `changes: [Change]` (new ones, appended to the chat's list) |
| `context` | `chat`, `context: Context` |
| `settings` | `chat`, `settings: { model, effort, plan }`, `model?` (the model the session runs), `effort?` (the effort its turn runs with, known from its first tool call on), `commands?` (its slash command names) |
| `meta` | `limits: [Limit]`, `limitsAt` (ms, when they were heard, or null), `models: [{ value, name, efforts }]`, `commands: [{ name, description, hint }]`, `defaultModel` (the model a chat gets when none is picked, e.g. `claude-opus-5-5[1m]`), `account` (`token-<hash>`), `accountLabel` (whose token, from Settings, or null), `plans: [{ key, name }]` (the saved tokens a chat can be put on), `planKey` (the key of the one in use, or null), `defaultEfforts: [{ model, effort }]` (the effort a chat runs with when it picks none, by its picked model, `""` for none, as heard from the sessions); sent after `chats` on `hello` and on every change |
| `agent` | `chat`, `agent` (id), `info: Agent`, `steps: [Msg]`, `files: [{ path, rel, added, removed, edits: [{ name, before, after }] }]`, `prompt`, `report`, `worktree: { path, branch } | null`, `git: { branch, base, commits: [{ hash, subject, when }], worktree (folder still there) } | null`; a shell: `info`, `command`, `output` (its last 64 KB); or `error` |
| `diff` | `chat`, `change`, `before`, `after` (null: the file did not exist), `skipped?`; or `error` |
| `agent_git` | `chat`, `agent`, `rev`, `patch` (git's patch text, cut at 400 KB); or `error` |
| `pictures` | `chat`, `pictures: [{ turn, title, at }]`: the chat's picture history, oldest first; the last is the live `scene`. Sent after `chat` on `open` and after every draw |
| `picture` | `chat`, `turn`, `scene` (null when there is none) |

`Row`: `{ id, title, folder, project: { name, root } | null (the folder's project: its git repo, a worktree's repo, else the folder under its group in the working folder), color, talk (bool: its speaker, talking mode, is on), bell (bool: it chimes, pops up and pushes when it finishes or waits; false = quiet), running, waiting (bool: an open ask), at (last activity), started (when the chat was made; the list's order),
preview (start of the last reply or the open question), unseen, archived, ended (when the last turn
ended, 0 = never), asks: [{ id, kind: "question" | "secret" | "permit", text (the question, the
secret's reason, or "<tool>: <description>"), ago }], agents, settling, lastTurn, runningFor }`;
`agents` = how many agents and background shells Claude started still run. `lastTurn` = `{ id (the
result's uuid), ago, stopped, failed, reply (the start of the reply, plain text; "" unless it
succeeded) }` or null; `runningFor` = ms since the turn began while the chat works (its turn or its
agents), else null. After the last agent ends the chat is `settling` for 5 s (30 s once a turn's init
came: a turn of Claude's own usually follows); then `ended` moves to that moment. `unseen` = the
last turn ended (not stopped) after the person last looked, nothing works and it is not settling.
`title` = the session's custom title, else Claude Code's summary, else the first prompt (with
`<claudia-file>` tags: the text before them, else the first file's name), else "New chat"; before
the session file exists, the first message's first line (≤ 120) or its first file's name.

The `chat` frame also carries `agents`, `changes` (the chat's newest 500), `settings`, `model`,
`commands` and `context`.

`Agent` (a subagent or a background shell; an agent's own shells and agents are part of it, counted in
`children`): `{ id, kind: "agent" | "shell", name, status: "running" | "completed" | "failed" | "stopped",
start, end, tools, tokens, step (what it is on: "Edit src/x.cljs", "$ npm test"), task (id to stop it,
while it runs), type, background, model, active, children, progress: { done, total, label } | null }`,
running ones first. `progress` is the last `##progress <done>/<total> <label>` line of a shell's output
(`bin/claudia-progress` prints them; it is on the PATH of every chat's commands).

`Change` (one file Claude changed in one turn, from its Edit/Write calls and a git snapshot of the chat's
folder repo, so shell edits count): `{ id, turn, path, added, removed, status: "added" | "deleted" |
"modified", skipped (why no text is kept: "binary file", "too large to show") | null, at }`.

`Context`: `{ used, max, percent, model }` (the chat's context window, read after each turn).
`Limit`: `{ key: "five_hour" | "seven_day" | …, label, percent, resetsAt (ms) | null }`, from the SDK's
`rate_limit_event`, kept per Claude token (by a hash of it).

`Msg`: `{ id, role: "user" | "assistant" | "tool" | "note", text?, at }`; a tool step has
`tool` (name) and `summary` (one line: `Edit src/x.cljs`, `$ npm test`, `draw`), and `error?` (bool).
A user message has `images?` (how many it sent: `GET /api/chats/:chat/sent/<id>/<n>`) and
`queued: true` while Claude Code hasn't taken it in (sent during a turn; the `msg` without it, or
the turn's end, says it was); a `show_image` step has `image: true` and a `show_video` step `video: true`
(both from `GET /api/chats/:chat/shown/<id>`).
An assistant message has `aside: true` when it was the turn's final reply and went over the chat's
limit (`claudia.slave.tools/reply-limit` characters): the Stop hook handed it back to Claude to
shorten, and the page folds it into the steps. Once a turn has ended the page folds every text of
it but the last into the steps (`claudia.web.views.pure.steps/folded-ids`).
A tool step also has the TypeScript Claudia's `label` (`Draw`, `Folder`, `Ask`, `Bash`, `workspace
show_image`), `title` (one line: `picture: <title>`, a file's name, a command's description),
`detail` (its input as JSON, ≤ 4000) and `result?` (`{ error, text ≤ 4000 }`).

Attachments: `files` = `[{ path, name, size, type }]` as uploads answered them (the runner keeps
only its own saved files) and goes as one `<claudia-file path name size type>` tag per file after
the text (`claudia.shared.files`); `images` = `[{ media_type, data (base64) }]` (JPEG, PNG, GIF,
WebP; photos and video frames, at most 40), sent before the text. The read-only `older` is allowed.

`Ask`:
- `{ id, kind: "question", questions: [{ question, header, multiSelect, options: [{ label, description }] }] }`
- `{ id, kind: "secret", ref, reason, account, vault (server name), pubkey (base64), expires }`
- `{ id, kind: "permit", tool, title, description, detail }`: a tool waiting for the person's OK
  (ExitPlanMode: the plan); every other tool is allowed without asking (each logged as
  `[<chat id 8>] auto-allowed <tool>: <input ≤ 300>`)

`Scene`: `{ title, blocks: [Block] }` in order, top to bottom. A `Block` has `id`, `name`, `kind`
(`"graph"`, `"html"`, `"form"`), `width` (`"full"`/`"half"`) and:
- graph: `nodes: [{ id, label, shape, color, note, group, state }]`, `edges: [{ from, to, label, dashed, color }]`, `groups: [{ id, label, color }]`
- html: `html`, `parts: { name: markup }`
- form: `form` (`pipeline`, `timeline`, `compare`, `chart`, `stats`, `notes`, `scene3d`, and the code forms `files`, `editor`, `flow`, opt-in `git`, `clojure`, `calls`), `data` (as the draw tool's description says; `claudia.shared.forms`).
  A picture holds one `scene3d` block: a new one takes the old one's place (or goes where its `before`
  says) and every other `scene3d` block leaves; redrawing the same id keeps its place and merges its data.

The runner applies each `draw` call (same input as the draw tool: `title`, `clear`, `graph`, `nodes`,
`edges`, `groups`, `remove`, `blocks`, `removeBlocks`) to the chat's scene and sends `scene`. It keeps
the picture each turn leaves (SQLite `pictures`, keyed by chat and turn: the user message's uuid, or an
id made for a turn Claude started itself), sends `pictures`, and answers the draw with the text and a
screenshot of the whole picture (`{ type: "image", data, mimeType: "image/png" }`), or the text and why
there is none. A chat older than the history has only its last picture (turn `imported`).

## Secrets (1Password, `plan/1passwd-plan.md`)

1. Claude calls `mcp__workspace__run_with_secret { ref, env_name, reason, command }` in a chat on server S.
2. S's runner makes a one-time keypair (libsodium `crypto_box_keypair`), sends `ask { kind: "secret", … pubkey }`.
3. Allow: the page calls `POST /s/<vault>/api/secret/read { ref, pubkey, request }` (`request` = the
   ask's `id`; `pubkey` and `sealed` are standard base64 with padding) on the person's
   vault server V (the hub lets only that person's own profile through). V's runner runs
   `op read --account <CLAUDIA_OP_ACCOUNT> <ref>` (the desktop app asks for the fingerprint), seals the
   value to `pubkey` (`crypto_box_seal`) and answers `{ sealed }` (base64). Paste instead: the page
   seals the pasted text to `pubkey` itself.
   `pubkey` and `sealed` are standard base64 with padding.
4. The page sends `secret { chat, ask, sealed }` to S. S opens it, runs `command` (in the chat's folder,
   `sh -c`) with `env_name=<value>` in that process's environment only, replaces every occurrence of
   the value in the output with `[secret]`, and answers Claude with the output. The keypair is dropped.
5. Deny or 10 minutes without an answer: Claude gets a refusal. Nothing is written anywhere: not the
   database, not a log, not a file. Only the ref, the reason and who allowed it are logged.

## Host ↔ runner (unix socket, JSON lines)

Runner → host: `{ op: "start", id, spec }` with spec = `{ options, mcp, hooks }`: `options` = SDK
query options without callbacks, `mcp` = the tool list (`{ server: { tool: { description, inputSchema
(JSON Schema) } } }`), `hooks` = `{ <HookEvent>: [{ matcher? }] }`;
`{ op: "send", id, text, uuid?, images? }` (uuid: the user message's id, kept in the session file;
images: `[{ media_type, data }]`, image blocks before the text),
`{ op: "interrupt", id }`, `{ op: "close", id }`, `{ op: "reply", rpc, value | error }`, `{ op: "list" }`,
`{ op: "control", id, req, method, args }` (a call on the chat's query: `stopTask`, `setModel`,
`applyFlagSettings`, `getContextUsage`, `supportedCommands`, `supportedModels`, and `setMcp` with
`[mcp]`: the session's tools replaced), answered by `{ ev: "control", req, value | error }`.

Host → runner: `{ ev: "msg", id, msg }` (an SDK message as is), `{ ev: "rpc", rpc, id, kind: "mcp" | "hook" | "canUseTool", args }`,
`{ ev: "ended", id, error? }`, `{ ev: "sessions", list: [{ id, busy }] }` (answer to `list`, and sent
on every runner connect so it can take running chats back), `{ ev: "cancel", rpc, id }` (the SDK
aborted that rpc: its ask goes).

rpc `args` by kind: `mcp` `{ server, tool, args }` → value = an MCP tool result (`{ content, isError? }`);
`hook` `{ event, index, input }` → value = the hook's output; `canUseTool` `{ name, input, toolUseID, title?, description? }`
→ value = a permission result (`{ behavior: "allow", updatedInput }` or `{ behavior: "deny", message }`).
Only the newest runner connection is listened to. Right after `sessions` on a connect, the host sends
every rpc still unanswered again (a runner that restarted asks the person again); the runner sends
nothing before that first `sessions`.

## Code forms and templates (`claudia.slave.codeforms`)

Page → runner reads, through the front (`/s/<server>/api/…` from the page). A read-only view may read.

| Call | Answer |
|---|---|
| `GET /api/forms` | `{ forms: [names this runner offers, in the draw tool's order], hidden: [the ones deleted in the Forms window], templates: [Template] }` |
| `GET /api/templates` | `[Template]`: `{ name, html, parts, description }` |
| `DELETE /api/templates/<name>` | `{ ok }` (not for a view) |
| `POST /api/chats/<id>/blocks/<block>/template` `{ name, description? }` | `{ ok, text }`: the freeform block kept as a template (not for a view) |
| `GET /api/chats/<id>/files?root&path` | `{ entries: [{ name, dir, status?, changed?, lines? }] }`: a folder of a files block's `root` (or inside it, or above it up to the home folder; in a view, up to the work folder) |
| `…/files?root&path&read=1&since?` | `{ text, mtime }`, `{ same: true }` while the mtime is `since`, or `{ error }` |
| `…/files?root&path&image=1` | the image itself (up to 25 MB) |
| `GET /api/chats/<id>/git?repo&limit?&branches?&fp?` | `{ fp, commits, head, currentBranch, detached, dirty, branches, more }`, or `{ same: true }` while `fp` is the repo's; only a `repo` a git block of the chat's picture names; 404 without `git` in `CLAUDIA_FORMS` |
| `…/git?repo&commit=<rev or WORKING>&file?` | `{ meta, files, diff, stats, truncated? }`; with `file`, `{ diff }` of that file |

Claude picks every kind of block itself: there are no per-chat choices. The draw tool refuses only
forms deleted in the Forms window (a session started before the delete still lists them); changes
to the forms are told to Claude once, in a `<system-reminder>` after the text of the next message
(the forms news). `templates` and `save_template` are
tools of the `picture` server; a block `{ id, template: "<name>", parts? }` is drawn from the template.
A files/git block's `root`/`repo` is made absolute (relative to the chat's folder) before it is kept.

A view's reads stay in the work folder (`claudia.slave.reach`): for a read-only view every disk read
above (files, git, clojure, calls, the project and its logo) is refused (403 `{ error }`) unless its
folder and its path, with `..` resolved and every symlink followed, are inside `CLAUDIA_CWD` (with
symlinks followed), never the home folder or above, and have no part below the work folder starting
with a dot (`.git`, `.env`, `.config`…). In a view a files folder lists no dot names, the files form
goes up only to the work folder, a git commit's or the working tree's files and diff leave dot paths
out, and a calls `file:` id with a dot part is refused. The profile's own reads are not limited by this.
