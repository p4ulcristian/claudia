# Claudia: the protocol

The one contract between the four programs. Everything is plain JSON (UTF-8). In ClojureScript, keys
become keywords once, where data comes in (`claudia.shared.protocol/read-json`), and go back to strings
where it leaves (`write-json`). Times are milliseconds since the epoch (numbers). Ids are strings.

```
browser (page) ──https──▶ gate: Caddy (login) ──▶ hub ──private network + hub key──▶ front (one per machine)
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
| hub | `HUB_KEY_FILE` | the hub's secret (40+ characters, `openssl rand -base64 32`); each server's key is HMAC-SHA256 of its name under it. Required: without it the hub does not start |
| hub | `HUB_SSH_CA_FILE` | the SSH certificate authority (README decision 53): an ed25519 private key in OpenSSH format without a passphrase (`ssh-keygen -t ed25519 -N '' -f <file>`). Unset or unreadable: `/hub/ssh-ca.pub` and `/hub/server/ssh-cert` answer 503, the rest of the hub runs |
| hub | `HUB_PEERS_DIR`, `HUB_APPLIED_DIR` | peer requests for the gate's helper (default `HUB_DATA/peers`), its answers (default `HUB_DATA/peers-applied`) |
| hub | `HUB_APNS_KEY_FILE`, `HUB_APNS_KEY_ID`, `HUB_APNS_TEAM_ID` | the APNs key (.p8) and its ids; `HUB_APNS_DRY_RUN=1` logs pushes instead |
| hub | `HUB_GITHUB_SECRET_FILE`, `HUB_UPDATE_REQUEST`, `HUB_REPO` | the webhook secret, the file the gate's update helper watches, `owner/name` |
| hub | `HUB_RELEASES`, `HUB_PUBLIC_URL` | where the release builder's files are kept (default `<data>/releases`); the hub's https address, so pokes tell servers where to fetch them (`<url>/hub/releases/files`; unset: pokes name none) |
| front | `CLAUDIA_UPDATE_TRIGGER` | file `POST /api/update-check` writes (a path unit starts `deploy/update.sh`) |
| hub | `HUB_WEB` | folder of the built page (`out/web`) |
| hub | `HUB_TOKEN_KEY_FILE` | base64 of the 32-byte key the saved Claude tokens are sealed with (never in `hub.db`); unset: the hub keeps no tokens and leaves the servers' token files alone |
| hub | `HUB_TTS_URL`, `HUB_TTS_TOKEN_FILE` (or `HUB_TTS_TOKEN`) | the narrator's voice service for 3D stories and its bearer token (unset: no voice) |
| front | `FRONT_HOST`, `FRONT_PORT` | where it listens: the address the hub reaches it at on the private network (`127.0.0.1` with Tailscale in userspace mode) |
| front | `FRONT_HUB_KEY_FILE` | this server's hub key (written by `deploy/pair.sh`). Required: without it the front does not start |
| front | `FRONT_PROFILES` | path of the profiles file (below) |
| front | `FRONT_NAME` | this server's name (e.g. `home`, `work`) |
| front | `CLAUDIA_HUB_URL`, `CLAUDIA_SERVER_NAME` | the hub's https address and this server's name as the hub knows it (its hub key is made of it). With them and `FRONT_HUB_KEY_FILE` the front calls the hub as its server for the agent users' SSH certificates (`POST /hub/server/ssh-cert`, README decision 53); unset: no certificates, logged at start when a profile runs as an agent user |
| runner | `RUNNER_SOCKET` | unix socket it serves HTTP + WebSocket on |
| runner | `HOST_SOCKET` | the host's unix socket |
| runner | `RUNNER_DATA` | folder for `claudia.db`, uploads, 3D scenes' videos (`videos/`) and the Trash (`trash/<id>/<folder name>`, README decision 63) |
| runner | `CLAUDIA_FFMPEG` | the ffmpeg that makes 3D scenes' videos (default `ffmpeg` on the PATH; needs libx264 and AAC) |
| runner | `RUNNER_PROFILE` | the profile's name (`paul`) |
| runner | `CLAUDIA_HUB_URL`, `CLAUDIA_SERVER_NAME`, `CLAUDIA_HUB_KEY_FILE` | the hub link: the hub's https address, this server's name and its hub key (the front's `FRONT_HUB_KEY_FILE`, readable by the runner). With them a runner calls the hub as its server for Claudia's own chat (with `CLAUDIA_OWNER_EMAIL`, README decision 48); never set it for a runner that runs as an agent user. The SSH certificates are the front's (README decision 53) |
| runner | `CLAUDIA_CWD` | where chats run (default `~/Work`) |
| runner, front | `CLAUDIA_TOKEN_FILE` | where the Claude long-term tokens are kept (the file itself is no longer written: there is no shared token, README decision 57; the front deletes it and its `.json` note). Every saved token (`PUT /api/claude-token/saved`): `<token file>.d/<key>` each, their names in `<token file>.saved.json` `{ tokens: [{ key, name, at }] }`; a chat with a pick (`settings.plan`) starts on that one. Each person's last pick (`PUT /api/claude-token/picks`): `<token file>.picks.json` `{ <profile>: { email, key, name, at } }`; a chat without a pick starts on its profile's entry, and with none it does not start (its message gets an `error` asking to pick one in its header). Runners read them on every session start |
| runner | `CLAUDIA_FORMS` | opt-in forms this runner has, comma separated (`git,clojure,calls`); the others are always on |
| runner | `CLAUDIA_WORKTREES_DIR`, `CLAUDIA_SENTINEL_URL`, `CLAUDIA_SENTINEL_TOKEN_FILE` (default `~/.config/sentinel/token`), `CLAUDIA_CHAT_URL` (`{chat}` = the chat's id) | where set: chats' edits and commands inside those worktrees are reported to Sentinel (`POST <url>/api/worktrees/touches`) |
| host | `HOST_SOCKET` | unix socket it listens on |

`hub.json`:
```json
{ "servers": [ { "name": "home", "label": "Home", "address": "10.0.0.2:4540" } ],
  "people":  [ { "email": "a@example.com", "name": "Paul", "admin": true, "servers": ["home"], "profile": "paul" } ] }
```
`profile` (optional): the person's profile name on every server, whose agent user is
`<profile>-claudia-agent` (README decisions 51 and 53); lower-case letters, digits and dashes,
starting with a letter, at most 18 characters, each person's own.
The profiles file (front): one line per profile, `<profile> <email> <runner socket path> [user=<linux
user>]` (`#` starts a comment). The user is whom the profile's host and runner run as (default
`claudia-<profile>`, as `deploy/shared-server/install.sh`); the front renews the SSH certificate of
each such user that is an agent user (README decision 53).

## Who is calling

- Caddy clears `X-Auth-Request-*` and `X-Claudia-*` from the browser, then sets
  `X-Auth-Request-Email` from the login.
- hub → front: every call carries `X-Claudia-Hub-Key: <that server's key>` (README decision 47); a
  front answers 401 to a call without it and never passes it to a runner.
- hub → front: `X-Claudia-Email: <email>`, and `X-Claudia-View: <profile>` when the caller looks at
  another profile's chats (read only). The page asks for a view with the query parameter
  `?view=<profile>` on `/s/<server>/api/…` and `/s/<server>/ws` (a browser cannot set headers on a
  WebSocket, and Caddy clears `X-Claudia-*`); the hub takes `view` out of the query and sends it as
  `X-Claudia-View`. Whether the caller may see that profile is the front's call. On every call,
  `X-Claudia-Writes: <email>,<email>` names the people whose chats the caller may write to (the
  admins' write ticks, `PUT /hub/admin/writers`); absent when none.
- The hub passes on no browser header but `Content-Type`, `Content-Length` and `Accept`: not the
  login cookie, not `X-Auth-Request-*`, not any `X-Claudia-*`. From the front's answer it drops
  `Set-Cookie` and the hop-by-hop headers.
- `/hub/*` and `/s/*` refuse (403) a request another site's page made: `Sec-Fetch-Site` other than
  `same-origin`/`none`, or an `Origin` whose host is not this one (`X-Forwarded-Host`, else `Host`).
- front → runner: `X-Claudia-Profile: <profile>` (whose chats), `X-Claudia-Read-Only: 1` for a view
  of a profile whose owner's email is not in `X-Claudia-Writes`, and the sender (README decision
  59): `X-Claudia-By: <the caller's email>` and `X-Claudia-Via: own|write-tick|view`. The front
  drops every `X-Claudia-*` the caller sent before setting its own, so a call through the front
  cannot name another sender.
- Root (README decision 52) is the front's, never a runner's: the front answers `POST /api/root/answer`
  and `/api/root/switch` itself, only for the profile's own person (own profile = target profile; not
  a writer, not a viewer), and never passes any other `/api/root/…` on (403). Nor `/api/ssh/…`
  (the SSH certificate routes, README decision 53): 403.
- The folder browser (`/api/folders…`, README decision 64) is the profile's own person's only: the
  front answers 403 to a view and to a write tick, and the runner answers 403 to any call whose
  `X-Claudia-Via` is not `own`.
  A read-only caller may only `GET` and send `hello`/`open` on the socket.

## Hub HTTP

| Call | Answer |
|---|---|
| `GET /`, `/new` and `/c/*` | the page (`index.html`), `cache-control: no-cache` |
| `GET /js/*`, `/css/*`, `/fonts/*`, `/img/*` (any file in `HUB_WEB`) | the built page's files; `no-cache` with an ETag, fonts and images kept a week |
| `GET /frame.html` | the document an html block runs in (it asks the page for Claude's HTML by postMessage and writes it in) |

Every file of the page goes with `x-content-type-options: nosniff` and a Content-Security-Policy
(`claudia.shared.csp`): the page's own (only its own scripts and WebAssembly, no CDN, no eval;
inline styles; fonts from Google Fonts; same-origin fetches and sockets; no framing,
`x-frame-options: DENY`); `frame.html` has the html block's policy instead (`sandbox allow-scripts`,
inline scripts and `https://cdn.jsdelivr.net`, no Claudia API, framed only by the page,
`x-frame-options: SAMEORIGIN`).
| `GET /hub/me` | `{ email, name, admin, vault (a key broker is there: passkeys and secret approvals, README decision 66), servers: [{ name, label }], viewServers: [{ name, label }], people: [{ email, name, picture }] }` (people: everyone, picture the address of their profile picture or null; servers: this person's own profile is there; viewServers: the other servers, where they may only read others' chats: `/s/<server>/api/me` and calls with `view`) |
| `POST /hub/server/claudia` (a server: `X-Claudia-Server-Name`, `X-Claudia-Server-Key` = its hub key; no login) | body `{ as (email, with a profile on that server), op: chats \| new \| send \| archive \| stop \| bookmarks \| bookmark, args }` → the operation's answer, carried out on the person's servers through each runner's `/api/chat-ops/…` (`GET chats` → `{ chats }`, `POST new { text, folder? }` → `{ chat }`, `POST send { chat, text }`, `POST archive { chat, archived }`, `POST stop { chat }` → `{ ok }`); `bookmarks` → `{ items }` (the person's bookmarked folders) and `bookmark { server (one of theirs), folder (absolute), label?, remove? }` → `{ items }` (one bookmark added, relabelled or removed, README decision 67), done on the hub itself; 403 when the key or the profile does not match |
| `POST /hub/server/ssh-cert` (a server: `X-Claudia-Server-Name`, `X-Claudia-Server-Key` = its hub key; no login) | body `{ user, publicKey }` → `{ certificate, keyId }`: an OpenSSH user certificate (one line) for `publicKey` (ed25519, one OpenSSH line), signed by `HUB_SSH_CA_FILE` (README decision 53). Only when `user` is `<profile>-claudia-agent`, that profile is a person's (the People table's profile names) and that person has a profile on that server. Principal exactly `user`, valid from 5 minutes before to 60 minutes after now, key id `<server>/<user>/<ISO time>` (also `keyId`: the front logs it, README decision 59), a random serial, extension `permit-pty` only (no agent, port or X11 forwarding). 403 when the key, the user or the profile does not match; 400 for a key that is not ed25519; 503 without `HUB_SSH_CA_FILE` |
| `GET /hub/ssh-ca.pub` | no login: the SSH authority's public key, one OpenSSH line (`ssh-ed25519 … claudia-hub-ca`), for every server's `TrustedUserCAKeys`; 503 without `HUB_SSH_CA_FILE` |
| `GET /hub/picture/<key>` | a person's profile picture (PNG or WebP; key: 16 hex digits of the SHA-256 of their email), 404 without one |
| `PUT /hub/admin/picture?email=<email>` (admins) | body: the picture (PNG or WebP, up to 512 KB) → `{ picture }` |
| `GET /hub/alive` | no login: `{ alive, commit }`, 200 while the hub takes calls, 503 once it is stopping (the gate's proxy picks between the two hubs of an update by it) |
| `GET /anatomy/<version>/<file>` | an anatomy model or the index (`index.json`, `<system>/<id>.glb`) of the pinned version only (`claudia.shared.anatomy`): fetched once from the claudia-anatomy repository at that version's tag, kept in `HUB_DATA/anatomy`, then sent from disk with a year's immutable caching; 404 for another path, 502 when it can't be fetched |
| `GET /hub/progress` | person | `{ builds: [{ commit, tree?, state, note, started, at }], release: { commit, tree, title, merged, machines: { <server>: { state: waiting\|done\|not reached, secs? } }, hub: { state, secs? } } \| null }`: the update pill (README decision 32) |
| `GET /hub/narrate` | `{ voice: bool }`: a voice service is set up (`HUB_TTS_URL` and its token) |
| `POST /hub/narrate` `{ text, instruction?, language? }` | the line as speech: raw 16-bit mono PCM at 24 kHz (`audio/L16;rate=24000;channels=1`, `x-seconds`); `instruction` = a story's `voice` (one reference clip is made from it, every line is a clone of it); `language` = a story's `language` (an ISO 639 code like `hu`; anything else counts as none): it goes to the voice service, and the reference clip is spoken from a sentence in that language, so the clone keeps that language's accent; for a language the hub has no reference sentence in, the service's default voice speaks the line in that language instead of a clone. Clips are kept in `HUB_DATA/narration` by their voice, language and text. 400 without text, 501 without a voice, 502 when the voice service fails. The service's route is `<HUB_TTS_URL>/v1/audio/speech` (form fields `text`, and `ref_audio` + `ref_text` for a cloned voice, or `instruction`; `language` when there is one) |
| `GET /hub/listen` | `{ listen: bool }`: a listen service is set up (`HUB_LISTEN_URL` and its token, `HUB_LISTEN_TOKEN_FILE` or `HUB_LISTEN_TOKEN`) |
| `POST /hub/listen` <clip> | the clip (its type as `content-type`, up to 24 MB) heard: `{ text, raw }`, text cleaned up, raw as heard (a service that answers only `text`: raw is the same). The service's route is `<HUB_LISTEN_URL>/v1/audio/transcriptions` (multipart `file`, and `speaker`: the caller's sign-in email, lower case). 400 empty clip, 501 without a listen service, 502 when it fails |
| `POST /hub/listen/log` `{ event, ms?, warm?, steps?, bytes?, chars?, error?, ua?, app? }` | a page's mic report, one line in the hub's log (no sound, no words): 200 |
| `GET /hub/listen/words` | `{ words: [string] }`: the caller's own words for the listen service (`GET <HUB_LISTEN_URL>/v1/vocabulary/<email>`). 501 without a listen service, 502 when it fails |
| `PUT /hub/listen/words` `{ words: [string] }` | the caller's list replaced (`PUT <HUB_LISTEN_URL>/v1/vocabulary/<email>`); the hub trims each word, drops blanks and repeats (case ignored): `{ words }` as the service keeps them. The speaker is always the signed-in caller, never a name in the request. 400 not a list of strings, a word over 60 characters or over 500 words; 501, 502 as GET |
| `POST /hub/videos` `{ server, chat, block, title, data }` | a 3D scene's video (`data`: the scene3d form's data as shown): handed to the caller's runner on that server (`POST /api/chats/:id/videos`, as the caller), answered at once `202 { id }`; then, when a voice is set up and the story has lines (the page's voice switch does not count: a video is watched later), the lines are said (as `POST /hub/narrate` would) and brought to the runner (`PUT …/videos/:id/voice`). 400 without chat, block or data; 403 no access to the server |
| `POST /hub/chat-move` `{ chat, from, to, folder? }` | the person's own chat moved from server `from` to server `to`, both servers where they have a profile (README decision 80): `folder` (absolute, `~/…` or relative to `to`'s working folder; blank or none: no project folder) must be a folder there. The hub drives both runners as the person (Runner HTTP: moving a chat, below), streams the copy from one to the other without keeping it, and checks the target's read-back against the source (`claudia.shared.chat-move/check-problem`: same message count, same last message, every file's size and SHA-256, as many pictures and file changes). `200 { ok, server, chat, folder }` once the chat is on `to` and archived on `from` (its root switch there turned off); `409 { error }` when it did not move (the source keeps the chat as it was, the target drops its copy); `400` for a bad request or a server not the person's |
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
`/s/<server>/api/chat-move/…` is refused (404): only the hub moves a chat (README decision 80).
`/s/<server>/api/update-check`, `/s/<server>/api/claude-token/picks`,
`/s/<server>/api/claude-token/saved`, `/s/<server>/api/claude-token/limits`, `/s/<server>/api/system`, `/s/<server>/api/github`, `/s/<server>/api/github/token` and `/s/<server>/api/github/service-token` are refused (404): only
the hub makes those calls on a front.

## Hub operations

Signed-in calls refuse cross-site requests like `/hub/me`. `/hub/me` also answers `updatedAt` (ms: when the version it runs went live, the update pill's merge time for it, else when the hub started) and `push` (bool:
pushes are on). Every admin call answers the whole admin state
`{ servers: [{ name, label, address, pairing: { server, publicKey, ip, createdAt, fetchedAt } | null, peerUp }],
people: [{ email, name, admin }], access: [{ email, server }], pairing: { available } }`.

| Call | Who | Answer |
|---|---|---|
| `GET /hub/admin` | admin | the admin state |
| `PUT /hub/admin/servers/:name` `{ label?, address? }` | admin | adds or changes a server (address `host:port`) |
| `DELETE /hub/admin/servers/:name` | admin | removes it (and its peer request) |
| `POST /hub/admin/people` `{ email, name?, admin?, profile? }` | admin | adds a person |
| `PUT /hub/admin/people/:email` `{ name?, admin?, profile? }` | admin | changes a person; `profile`: their profile name (as in `hub.json`; `""` takes it away; 400 when another person has it) |
| `DELETE /hub/admin/people/:email` | admin | removes them and their ticks |
| `PUT /hub/admin/access` `{ email, server, granted }` | admin | a tick on or off |
| `PUT /hub/admin/writers` `{ email, owner, granted }` | admin | a write tick on or off: `email` may write to `owner`'s chats on every server where `owner` has a profile |
| `POST /hub/admin/builder` `{ key }` | admin | makes the machine whose `deploy/release-builder/install.sh` printed `claudia-builder:<host>:<secret>` the release builder (the one before stops counting); answers like `GET /hub/admin` (its `builder`: `{ host, pairedAt, seenAt }` or null); 400 not a builder key |
| `POST /hub/admin/pair` `{ key, address?, name?, label? }` | admin | pairs a server from `deploy/pair.sh`'s key `claudia-pair:<host>:<secret>`; `address` (host:port of its front) is required for a new server; a known name keeps its address unless given |
| `POST /hub/enroll` `{ secret }` | no login | 200 `{ name, key }` once (the server's hub key), 404 before its pairing key is pasted or after it was fetched |
| `GET /hub/releases/wanted` | the release builder (`Authorization: Bearer <secret>`) | 200 `{ builds: [{ key: "main" \| "pr-<n>", commit, kind: "main" \| "pr", at }] }`, newest first; held up to 25 s while empty. 401 not the builder |
| `PUT /hub/releases/files/<file>` | the release builder | the file's bytes (up to 400 MB) into `HUB_RELEASES` (default `<data>/releases`): 200 `{ ok, size }`. `<file>`: `claudia-code-<tree>.tar.gz`, `claudia-deps-<lockfile hash>.tar.gz`, `claudia-deps-<lockfile hash>-darwin-arm64.tar.gz`, or one of them + `.sig` (ssh-keygen -Y, namespace `claudia-release`) |
| `GET`/`HEAD /hub/releases/files/<file>` | no login | the file (immutable), 404 when not here |
| `POST /hub/releases/progress` `{ commit, tree?, state: pending\|success\|failure, description }` | the release builder | the step it just set as the commit status `builder`, for the update pill: 200 |
| `POST /hub/releases/ready` `{ commit, tree, deps }` | the release builder | 200 once the tree's code and both platforms' node_modules are here with their signatures: the commit leaves the queue, every front is poked with `{ stage: tree, releases }` and the gate gets `stage <tree>`; 409 names what is missing |
| `POST /hub/github` | no login, HMAC (`X-Hub-Signature-256`) | 202; push to `refs/heads/main` pokes every front with `{ main: commit }`, adds `update <commit> <tree>` to `HUB_UPDATE_REQUEST` and asks the release builder for it; a pull request from this repository opened, pushed to or reopened asks the builder for its head (closed: dropped); a successful `stage.yml` run (title `stage <tree>`) pokes every front with `{ stage: tree }` and adds `stage <tree>`; anything else ignored (202). 401 bad signature, 503 no secret |
| `POST /hub/push/devices` `{ token (hex), env: development\|production, topic (bundle id), key (base64 of 32 bytes), liveStartToken? }` | person | `{ ok, push }` |
| `DELETE /hub/push/devices/:token` | person | their own phone only |
| `POST /hub/push/live` `{ device, activity, token }` | person | a Live Activity's update token: `{ ok, ended }` |
| `DELETE /hub/push/live/:activity` | person | the app saw it end |
| `POST /hub/presence` `{ page (8-64 chars), active }` | person | 204; alerts are held while any page said active within 45 s |
| `GET /hub/presence?page=<id>` | person | `{ elsewhere }`: another of the person's pages said active within 45 s (this page then makes no sound or popup) |
| `GET /hub/chat-order` | person | `{ items, at }`: the person's chat order and groups (README decision 38); items: `{ chat: "<server>/<chat>" }` or `{ group, name, chats: ["<server>/<chat>"], folded, picks }`; `{ items: [], at: 0 }` before any |
| `PUT /hub/chat-order` `{ items }` | person | the same shape: the whole list kept, as far as it is well formed (at most 500 items; picks: plan, model, effort, folder, server, talk, bell) |
| `GET /hub/bookmarks` | person | `{ items, at }`: the person's bookmarked folders (README decision 61); items: `{ server, folder, label? }` (folder an absolute path); `{ items: [], at: 0 }` before any |
| `GET /hub/right-view` | person | `{ view }`: what the person's right half shows (README decision 75), one of `picture files search settings words machines prompts forms`; `picture` before any |
| `PUT /hub/right-view` `{ view }` | person | the same shape: the pick kept for every device (an unknown view is kept as `picture`) |
| `PUT /hub/bookmarks` `{ items }` | person | the same shape: the whole list kept, as far as it is well formed (`claudia.shared.bookmarks`: absolute paths without `.` or `..` parts, each server and folder once, labels of at most 80 characters, at most 200 items) |
| `GET /hub/github/state` | person | `{ app: { slug, installUrl } \| null, user: { login, accessExpiresAt, refreshExpiresAt } \| null, servers }`: the hub's GitHub App and the caller's connection (README decision 37) |
| `GET /hub/github/connect` | person | 302 to GitHub's authorize page for the hub's GitHub App |
| `GET /hub/github/callback?code&state` | person | the code traded for the caller's tokens (sealed), handed to their servers; 302 to the page. 400 not the caller's Connect, 502 GitHub gave no token |
| `GET /hub/github/installed` | person | 302 to the page (GitHub's return after installing the app) |
| `DELETE /hub/github/connection` | person | `{ ok }`: the app's grant revoked on GitHub and the caller's tokens dropped. Every `/hub/github/…` call: 501 without a GitHub App on this hub |
| `POST /hub/health` `{ load (8-64 chars, one per page load), commit (the hub's, from /hub/me), ok, reason?: no-chats\|script-error\|no-socket, detail? }` | person | 200; 400 when it is not a report. In the hub's first 15 min on its commit (`HUB_COMMIT`), two broken loads and no working one roll the release back, once (README decision 35): `rollback <commit>` for the gate, `{ rollback }` to every front. `no-socket` counts only when one of the person's servers answers |
| `GET /hub/claude-tokens` | person | `{ on, pick: { name, at } \| null, tokens: [{ name, by, at, limits, heardAt }] }`: never a token; `pick` = the caller's own: their last pick in a chat's header, else the saved token named like their profile (`at` null), else null |
| `PUT /hub/claude-token` `{ name, token }` | person | the same shape: sealed and saved under the name (same name = replaced), on every server; it moves nobody's chats; 503 when `on` is false |
| `POST /hub/claude-token/pick` `{ key }` | person | the same shape: the caller's last pick (the page sends it after a pick in the header of one of the caller's own chats, or of a new chat); their chats without a pick run on it on every server; 404 no such key |
| `DELETE /hub/claude-tokens/:name` | person | the same shape; chats on it go to their owner's pick |
| `GET /hub/github-login` | person | `{ on, login, savedAt, servers: [{ name, label, ok, login, error }] }`: the caller's GitHub login kept by the hub (never the token) and what each of their servers' gh is logged in as |
| `PUT /hub/github-login` `{ token }` | person | the same shape: checked with GitHub, sealed and kept, handed to each of their servers (`PUT /api/github` as them); 400 not a token or refused by GitHub, 503 when the hub keeps no tokens |

Claude tokens (README decisions 10, 16 and 57): every saved one on every server, and no shared
default: each person's last pick (`claude_token_picks`; never picked: the saved token whose key is
their profile name) is what their chats without a pick run on. The hub seals each saved token
(AES-256-GCM, the folded name as AAD, base64 of nonce (12) ‖ ciphertext ‖ tag (16)) under
`HUB_TOKEN_KEY_FILE`, and brings every server along: `GET /api/claude-token/saved`, then `PUT` with
all of them when the keys, names or save times differ; then `GET /api/claude-token/picks`, then
`PUT` with the picks of the people with a tick on that server when they differ; after each save,
pick or removal (the answer waits up to 6 s for it) and every minute, so a server that was off, new
or reinstalled follows by itself.
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
| `GET /api/me` | `{ server, profile, profiles, writable, viewing, readOnly, version, commit }`, answered by the front: `profile` = the caller's own, `profiles` = every profile on this server (the page's "<person>'s chats"), `writable` = the other profiles here the caller may write to, `viewing` = the other profile looked at or null, `readOnly` = that view is read only |
| `GET /api/version` | the front, the hub only: `{ commit (7 characters), version }`, what this machine runs (the update pill follows each machine with it) |
| `POST /api/update-check` `{ rollback?, main?, stage?, releases? }` | answered by the front, no caller needed (only the hub reaches it): adds a line to `CLAUDIA_UPDATE_TRIGGER` (`rollback <commit>`: back one release from that commit, README decision 35; `main <commit>`, `stage <tree>`, else `check <time>`), after `releases <url>` when `releases` is the hub's `https://…/hub/releases/files`, 202; 501 when unset |
| `GET /api/claude-token/picks` | the front, the hub only: `{ picks: [{ email, key, name, at }] }`, each person's pick kept here (by email), never a token |
| `GET /api/claude-token/saved` | the front, the hub only: `{ tokens: [{ key, name, at }] }`, the saved tokens kept here, never a token |
| `PUT /api/claude-token/saved` `{ tokens: [{ key, name, at, token }] }` | the front, the hub only: the whole set, written over the one kept here (a token no longer in it is deleted); answers like GET; 400 when one is not a key (`[a-z0-9-]{1,24}`), a name, a time and a Claude token |
| `PUT /api/claude-token/picks` `{ picks: [{ email, key, name, at }] }` | the front, the hub only: the whole set, kept by the profile here whose email each is (others left out) in `<token file>.picks.json`, written over the one kept here; the old shared token file goes; an idle session without a pick starts again on its person's with its next message; answers like GET; 400 when one is not an email, a key (`[a-z0-9-]{1,24}`), a name and a time or null |
| `GET /api/claude-token/limits` | the front, the hub only: `{ tokens: { <token hash>: { limits: [Limit], at } } }`, the newest of every profile's runner (`GET /api/limits` on each runner socket: the same shape), the hash = the first 16 hex of the token's SHA-256 |
| `PUT /api/github/service-token` `{ name, token, expiresAt, login }` | the front, the hub only (README decision 78): a service's 1-hour app installation token written to `FRONT_GITHUB_SERVICES_DIR/<name>/token` (and `login`, the app's bot) through a rename; `{ ok: true }`, 400 `{ error }` not a service name, `ghs_` token or bot login, 404 when root made no folder for that service, 501 when the front has no `FRONT_GITHUB_SERVICES_DIR` |
| `GET /api/system` | the front, the hub only: this machine's newest `Sample` (below); the first ask after 30 s without one waits about 1 s for its first window |
| `POST /api/secret/read` `{ ref, pubkey, request }` | the vault read (below): `{ sealed }` or `{ error }` |
| `POST /api/ssh/pubkey` `{}` | a runner, the front only (never passed on from the hub: 403; README decision 53): `{ publicKey }`, the one line of `~/.ssh/claudia_agent.pub` of the agent user it runs as (the ed25519 key made with ssh-keygen the first time); 409 `{ error }` when it does not run as an agent user |
| `POST /api/ssh/cert` `{ certificate }` | a runner, the front only: writes the certificate (one `ssh-ed25519-cert-v01@openssh.com` line, as the hub signed it) to `~/.ssh/claudia_agent-cert.pub` and the managed block of `~/.ssh/config` (`Host *`: that key and certificate, `IdentitiesOnly yes`, `User` the agent user it runs as): `{ ok: true }`; 400 for anything else, 409 when it does not run as an agent user. The front calls both on start and every 45 minutes for each profile whose `user=` in the profiles file is an agent user, the hub's `POST /hub/server/ssh-cert` between them (5 minutes after a failure) |
| `GET /api/chats/:id/picture.png?turn=` | the live picture (or that turn's) as a PNG, drawn by the page's render mode in a headless Chromium (the camera); 404 no such picture, 503 `{ error }` when this server can't shoot (no browser, no built `out/web`) |
| `POST /api/chats/:id/videos` `{ block, title, data, voice }` | a 3D scene's video (from the hub): `202 { id }`; it waits for its voice when `voice`, else is queued. One is made at a time: the render page in a headless browser of its own (1280×720 at 1.5×: 1920×1080) plays the story on a stepped clock and hands over JPEG frames; ffmpeg (`CLAUDIA_FFMPEG`, else `ffmpeg`) makes them H.264 and the voice AAC; the file is kept at `RUNNER_DATA/videos/<chat>/<id>.mp4` (+ `.json`) |
| `PUT /api/chats/:id/videos/:vid/voice` `{ clips: [{ text, instruction, language, pcm }], error? }` | the narrator's clips (pcm: base64 of 16-bit mono PCM at 24 kHz) for a video waiting for them; it is queued. 409 when that video waits for none |
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
| `GET /api/prompts` | `{ profiles: [{ name, text }], builtIn }`: this server's prompt profiles, by name, and `builtIn`, Claudia's part of the system prompt every chat has (README decision 60) |
| `PUT /api/prompts/<name>` `{ text }` | `{ profiles, builtIn }`: the profile set (added when new; name ≤ 60, text ≤ 20,000 characters); chats on it take the new text after their turn (not for a view) |
| `DELETE /api/prompts/<name>` | `{ profiles, builtIn }`: the profile gone; chats on it run on no profile after their turn (not for a view) |
| `GET /api/github` | `{ user: { login, name } \| null }`: this profile's gh login (the hub's call, as the person; refused from a page) |
| `PUT /api/github` `{ token }` | `{ user }`: `gh auth login` with it, git's credential helper and commit name set (the hub's call: README decision 23) |
| `GET /api/github/token` | `{ token }` gh holds for github.com, 404 `{ error }` when none: the hub takes a login made before into its keeping, once (refused from a page) |

## Runner HTTP: moving a chat (`/api/chat-move/…`; README decision 80)

The hub's calls only (it refuses them from a page), as the chat's own person: the runner answers 403
to any caller whose `X-Claudia-Via` is not `own` (a view or a write tick moves nothing). `chat` is a
chat id (a lower-case uuid).

| Call | Runner | Answer |
|---|---|---|
| `POST /api/chat-move/check` `{ chat, folder }` | target | `{ folder }` (resolved; null: none); 404 no folder there; 409 a chat with that id is here (unless it is the copy an earlier move away left behind: archived, `movedTo` kept, not written to since) |
| `POST /api/chat-move/hold` `{ chat }` | source | `{ check }` once the chat is held (15 minutes at most: messages to it are refused with an `error` frame, its Claude Code closes and does not start); 409 when it works (a turn, agents or background shells), waits on an ask, is Claudia's own chat, is held already, or has no session file. A move never stops anything |
| `GET /api/chat-move/export?chat=` | source | the copy of a held chat, `application/x-ndjson`: `{ type: "chat", id, row (every column of chats), kv ({ key: JSON text } of settings, context, turn, tasks), pending (its own move note not said yet, or null) }`, then `{ type: "picture", turn, scene (JSON text), at }` and `{ type: "change", turn, path, before, after, added, removed, status, skipped, at }` each, then the session's files (`<id>.jsonl` and everything under `<id>/`, paths relative to its Claude Code project folder) as `{ type: "file", path, data (base64, 768 KB a line) }`, then `{ type: "end" }`; 409 when not held |
| `POST /api/chat-move/import?chat=` (the copy) | target | `{ check }`: the copy kept in `RUNNER_DATA/moves/<chat>` (15 minutes at most; gone on a restart), read back from the disk. 400 for a broken copy (a line over 8 MB, a record of another chat, a file path outside the chat's files, no `end`) |
| `POST /api/chat-move/commit` `{ chat, folder, from, to }` | target | `{ ok, folder }`: the files placed in this runner's own project folder (Claude Code's folder for `CLAUDIA_CWD`, its own slug), the rows in (the row in `folder`, not archived, root off, a new chat key; the kv values; pictures; changes) and the move's note kept (`serverMove`); a copy left behind here before gives way |
| `POST /api/chat-move/drop` `{ chat }` | target | `{ ok }`: the copy kept aside goes |
| `POST /api/chat-move/release` `{ chat, to? }` | source | `{ ok }`; with `to` (only while held): the chat archived and marked `movedTo { server, at }`; without: the move given up, the chat as before |

`check` = `{ messages, last, files: [{ path, size, sha256 }], pictures, changes }`: the session file's
user and assistant entries with a uuid (how many, the last one's uuid), every file of the session,
and the chat's pictures and file changes.

The move's note goes to Claude with the next message, after the text, as `<claudia-moved>…</claudia-moved>`;
the chat shows the same words as a `note` Msg (id `<message id>:moved`, from the session file too),
and before that message as the note `moved-pending` at the end of the `chat` frame's messages (a
`msg` with `gone: true` removes it once the message goes).

## Runner HTTP: folders (`/api/folders…`; README decisions 61 to 64, 68, 69)

Answered by the runner (through the front), for the profile's own person only (403 otherwise, above:
"Who is calling"). Every call only reads: nothing deletes, moves or trashes (README decision 69).
The whole system is in reach as far as the runner's user may read; EACCES/EPERM answer 403 `{ error: "Permission denied: …" }`,
a missing folder 404, a path that is no absolute path or no folder 400. Every path is resolved
(`..`, every symlink followed) before any check. `Entry` = `{ name, path, dir: true, mtime, hidden
(the name starts with a dot), link? (only on a symlink: its target) }`.

| Call | Answer |
|---|---|
| `GET /api/folders?path=<abs>` | `{ path, entries: [Entry], cwd }`: the folders right inside `path` (default "/"; files left out; a symlink to a folder is in, with `link` = its target), sorted by name (case folded); `path` with `..` resolved, symlinks kept (a linked folder stays where its link is); `cwd` = `CLAUDIA_CWD` resolved (the tree opens it at first) |
| `GET /api/folders/contents?path=<abs>` | `{ path, entries: [Item], more }`: everything right inside `path`, folders first, then files, each by name (case folded), at most 5000 (`more` = how many were left out); symlinks followed, `path` kept as walked. `Item` = `{ name, path, dir, mtime, size (bytes; null for a folder or a broken link), hidden, kind ("folder", "image", "video", "audio", "pdf" or "file"), link? (only on a symlink: its target, "" when broken) }` |
| `GET /api/folders/file?path=<abs>&read=1` | `{ path, text, mtime }` of a text file, or `{ path, error }` when it is over 2 MB, not text (a NUL byte) or unreadable |
| `GET /api/folders/file?path=<abs>` | the file's bytes, for the kinds `claudia.slave.folders.rules/preview-types` names (images, video, audio, PDF; 415 for any other), with its content type, `x-content-type-options: nosniff`, and except for a PDF `content-security-policy: default-src 'none'; style-src 'unsafe-inline'; img-src data:`; a `Range: bytes=…` header answers 206 with that range (416 when it can't be served); 404 for no file or an empty one |
| `POST /api/folders/missing` `{ paths }` | `{ missing }`: those of `paths` (at most 200 absolute paths) where no folder sits (gone or not a folder; a folder the runner may not look at counts as there): the bookmarks shown as missing |

Folders moved to the Trash before decision 69 stay in `RUNNER_DATA/trash/<id>/` until 7 days after
they were trashed; the runner purges them at start and every hour, and no call reaches them.

## WebSocket (`/ws`): page ↔ runner

One socket per server the page uses. Every frame is a JSON object with `type`.

Page heartbeat: the page sends exactly `{"type":"ping"}` on a socket it has heard nothing on for
10 s; the hub answers `{"type":"pong"}` itself and passes the ping on to no one. No frame within
6 s after a ping = the socket is dead: the page opens a new one at once. A user action (any frame
but `hello`, `open`, `close`, `older`, `picture`, `agent`, `agent_git`, `diff`, `seen`) sent while
the socket is closed or quiet waits in the page and goes out after the socket is heard from (on a
new socket: after `hello` and the open chat's `open`).

Page → runner:

| type | fields | effect |
|---|---|---|
| `hello` | | runner answers `chats` |
| `open` | `chat` | runner answers `chat` (full state) and sends that chat's live events from then on (several open chats per socket are fine) |
| `close` | `chat` | stop sending that chat's live events |
| `new` | `text`, `folder?`, `id` (a uuid the page made), `files?`, `images?`, `model?`, `effort?`, `plan?`, `prompt?`, `color?`, `talk?`, `bell?`, `msg?` | starts a chat with that first message, on the model, effort and plan picked before it was made (else the defaults and the person's last pick, README decision 57), in the colour its header wore (else one of the least worn; every chat has a colour) and with its speaker and bell as picked (else speaker off, bell on); runner answers `created { chat, id }` then events |
| `send` | `chat`, `text`, `files?`, `images?`, `now?`, `msg?` | a message into the chat (queued if a turn runs; `now: true`: the running turn is stopped and the message goes once it has ended). `msg` (here and on `new`): a uuid the page made as the message's id, used for its `msg` events and in the session file (else the runner makes one); the page ties its own data to the message by it (a dictated message's sound) |
| `claudia` | | Claudia's own chat (README decision 48): the runner that has her (its `meta` frame says `claudia: true`) makes or finds it and answers `claudia { chat }`; elsewhere an `error` |
| `older` | `chat`, `before` (a Msg id) | runner answers `older` with the page of messages before it |
| `stop` | `chat` | interrupts the running turn |
| `answer` | `chat`, `ask`, `answers` (`{ "<question>": "<label>" }`) | answers an AskUserQuestion; on a `permit` ask (`answers` `{}`) it is the OK |
| `secret` | `chat`, `ask`, `sealed` | the sealed secret for a `run_with_secret` ask |
| `deny` | `chat`, `ask` | refuses a secret or permit ask |
| `keys` | `chat`, `ask` (a `keys_ask`'s id), `answer` (`GET /hub/keys/mine`'s answer) \| `error` (its error text) | the person's keys for a `list_secrets` call (README decision 77); the first answer ends the ask, later ones are dropped |
| `rename` | `chat`, `title` | |
| `archive` | `chat`, `archived` (bool) | |
| `folder` | `chat`, `folder` (absolute, `~/…`, or relative to the runner's working folder; empty or null: no project) | the chat's working folder; Claude hears of it with the next message; `error` when no folder is there |
| `seen` | `chat` | the person looked at its last turn |
| `code_edit` | `chat`, `block`, `file`, `text`, `base` | the user's typing in an editor block, saved into the picture (`base` = the version `v` of Claude's code the text starts from; a save on an older version is dropped) |
| `code_send` | `chat`, `block`, `note` | the editor's Send: every file with unsent edits goes to Claude as one message (`<claudia-code>` per file: diff and full code) |
| `stop_task` | `chat`, `task` | stops one agent or background shell of the chat (`Agent.task`) |
| `settings` | `chat`, `model?`, `effort?` (null = the person's default), `plan?` (a saved token's key from `meta.plans`; null or one not saved = no pick: its owner's last pick, `meta.planKey`), `prompt?` (a prompt profile's name; null = none: README decision 60), `talk?`, `bell?` (booleans) | the chat's model and effort: kept, used on its next start, and given to a live session at once. Its plan: kept, and its session starts again on that token with its next message. Its speaker (`talk`) and bell: kept with the chat, then `chats` goes to every page |
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
| `scene` | `chat`, `scene: Scene` (the whole picture; it is small), `changed` (ids the draw changed: blocks, `block:item` (a graph's nodes and arrows `block:from->to` too), `block:part`), `turn` (the picture's turn) |
| `status` | `chat`, `running`, `note?` (`Thinking`, `Retrying…`, `Compacting`; none: plain working) |
| `turn` | `chat`, `running` (bool), `stopped` (when a turn ended: the person stopped it) |
| `ask` | `chat`, `ask: Ask` |
| `asked` | `chat`, `ask` (id: answered, denied or expired; remove the card) |
| `keys_ask` | `chat`, `ask` (an id): Claude called `list_secrets`. Sent to every page on this runner that sent `hello` and is not read-only, whether or not it has the chat open; the page fetches `GET /hub/keys/mine` and answers `keys` at once, with no card |
| `error` | `chat?`, `text` |
| `agents` | `chat`, `agents: [Agent]` (the chat's whole list, on every change) |
| `changes` | `chat`, `changes: [Change]` (new ones, appended to the chat's list) |
| `context` | `chat`, `context: Context` |
| `settings` | `chat`, `settings: { model, effort, plan, prompt }`, `model?` (the model the session runs), `effort?` (the effort its turn runs with, known from its first tool call on), `commands?` (its slash command names) |
| `meta` | `limits: [Limit]`, `limitsAt` (ms, when they were heard, or null), `models: [{ value, name, efforts }]`, `commands: [{ name, description, hint }]`, `defaultModel` (the model a chat gets when none is picked, e.g. `claude-opus-5-5[1m]`), `account` (`token-<hash>`), `accountLabel` (the name of this profile's person's last pick, or null), `plans: [{ key, name }]` (the saved tokens a chat can be put on), `planKey` (the key of this profile's person's last pick: what a chat without a pick runs on, or null; README decision 57), `defaultEfforts: [{ model, effort }]` (the effort a chat runs with when it picks none, by its picked model, `""` for none, as heard from the sessions); sent after `chats` on `hello` and on every change |
| `agent` | `chat`, `agent` (id), `info: Agent`, `steps: [Msg]`, `files: [{ path, rel, added, removed, edits: [{ name, before, after }] }]`, `prompt`, `report`, `worktree: { path, branch } | null`, `git: { branch, base, commits: [{ hash, subject, when }], worktree (folder still there) } | null`; a shell: `info`, `command`, `output` (its last 64 KB); or `error` |
| `diff` | `chat`, `change`, `before`, `after` (null: the file did not exist), `skipped?`; or `error` |
| `agent_git` | `chat`, `agent`, `rev`, `patch` (git's patch text, cut at 400 KB); or `error` |
| `pictures` | `chat`, `pictures: [{ turn, title, at }]`: the chat's picture history, oldest first; the last is the live `scene`. Sent after `chat` on `open` and after every draw |
| `picture` | `chat`, `turn`, `scene` (null when there is none) |

`Row`: `{ id, title, folder, project: { name, root } | null (the folder's project: its git repo, a worktree's repo, else the folder under its group in the working folder), color, talk (bool: its speaker, talking mode, is on), root (bool: its root switch is on, README decision 52), bell (bool: it chimes, pops up and pushes when it finishes or waits; false = quiet), running, waiting (bool: an open ask), at (last activity), started (when the chat was made; the list's order),
preview (start of the last reply or the open question), unseen, archived, ended (when the last turn
ended, 0 = never), asks: [{ id, kind: "question" | "secret" | "permit", text (the question, the
secret's reason, or "<tool>: <description>"), ago }], agents, settling, lastTurn, runningFor, agentsFor? }`; `agentsFor` = ms since the longest-running of those agents started (none running: absent);
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
- `{ id, kind: "permit", root: true, tool: "Run as root", title: "Run as root", argv, cwd, description
  (the command), detail ("In <folder>" and the command), expires }`: shown for the front (Root
  helper, below). The page answers it to the front, not with `answer`/`deny` (the runner ignores those
  for it): `POST /s/<server>/api/root/answer { chat, ask, allow, argv, cwd }`

`Scene`: `{ title, blocks: [Block] }` in order, top to bottom. Every `Block` is one form with its data
(README decision 39): `{ id, name, width ("full"/"half"), form, data }`. `form` is `graph`, `pipeline`,
`timeline`, `compare`, `chart`, `stats`, `notes`, `scene3d`, the code forms `files`, `editor`, `flow`,
opt-in `git`, `clojure`, `calls`, or `html`; `data` as the draw tool's description says
(`claudia.shared.forms`, `claudia.slave.tools`). Two have rules of their own:
- graph: `{ nodes: [{ id, label, shape, color, note, group, state }], edges: [{ id, from, to, label, dashed, color }], groups: [{ id, label, color }] }`.
  An arrow's `id` is always `"from->to"` (the runner sets it); arrows whose ends are gone are dropped,
  and a graph without nodes leaves the picture.
- html: `{ html, parts: { name: markup }, template? }`. New `html` starts its parts again; `parts` sent
  merge into the ones it has.
Every other form's item lists are upserted by item id; `removeItems` drops items by id from every list.
A block given another form starts from empty data.
A picture holds one `scene3d` block: a new one takes the old one's place (or goes where its `before`
says) and every other `scene3d` block leaves; redrawing the same id keeps its place and merges its data.
Pictures saved in an older shape (blocks with a `kind`, or the graph and cards from before blocks) are
rewritten once when the runner's store opens with `PRAGMA user_version` below 1 (`claudia.slave.upgrade`).

The runner applies each `draw` call (same input as the draw tool: `title`, `clear`, `blocks: [{ id, name,
form, data, removeItems, width, before }]`, `removeBlocks`) to the chat's scene and sends `scene`. A new
block needs a `form`; a call in the older shape (`nodes`, `edges`, `groups`, `graph`, `remove`, or a
block's `kind`, `html`, `parts`, `template`, `height`) is refused with the new shape spelled out. It keeps
the picture each turn leaves (SQLite `pictures`, keyed by chat and turn: the user message's uuid, or an
id made for a turn Claude started itself), sends `pictures`, and answers the draw with text only: what changed (no
screenshot). A chat older than the history has only its last picture (turn `imported`).

## Secrets (1Password through the key broker, README decision 66)

0. Claude calls `mcp__workspace__list_secrets {}` for the references it may use (README decision 77):
   S's runner sends `keys_ask { chat, ask }` to the person's open pages; the first page fetches
   `GET /hub/keys/mine` and answers `keys { chat, ask, answer | error }`. Claude gets one line per
   item and one line per field with its reference. No page in 60 seconds, or none connected: a
   refusal that says so. No values pass, and nothing waits for a tap.
1. Claude calls `mcp__workspace__run_with_secret { ref, env_name, reason, command }` in a chat on server S.
2. S's runner makes a one-time keypair (libsodium `crypto_box_keypair`), sends `ask { kind: "secret", … pubkey }`.
3. Allow with passkey: the page calls `POST /hub/approve/options { kind: "read", ref, pubkey, request }`
   (`request` = the ask's `id`; `pubkey` and `sealed` are standard base64 with padding), the person
   signs the answer's `options` with a passkey (`navigator.credentials.get`, user verification), and
   the page sends `POST /hub/vault/read { challengeId, response }`. The broker checks the signature,
   reads `ref` (only `op://<BROKER_VAULT>/…`) with its service account, seals the value to `pubkey`
   (`crypto_box_seal`) and answers `{ sealed }`. Paste instead: the page seals the pasted text to
   `pubkey` itself.
4. The page sends `secret { chat, ask, sealed }` to S. S opens it, runs `command` (in the chat's folder,
   `sh -c`) with `env_name=<value>` in that process's environment only, replaces every occurrence of
   the value in the output with `[secret]`, and answers Claude with the output. The keypair is dropped.
5. Deny or 10 minutes without an answer: Claude gets a refusal. Nothing is written anywhere: not the
   database, not a log, not a file. Only the ref, the reason, who approved it and with which passkey
   are logged.

## Key broker (`claudia-broker`, `src/claudia/broker`; README decision 66)

`out/broker.js` runs on the gate from the hub's image as its own container and user. It answers
only on its unix socket, and only the hub calls it: every body carries the email the hub's login
vouched for, put there by the hub. Settings:

| Variable | What |
|---|---|
| `BROKER_SOCKET` | the unix socket it listens on (mode 0660; its folder is shared with the hub's container, whose user is in the socket's group). Required |
| `BROKER_DATA` | folder for `broker.db`: passkeys (public keys), enrollment codes (hashes). Default `/data` |
| `BROKER_OP_TOKEN_FILE` | the 1Password service account's token (read-only, one vault). Unset, empty or refused: reads answer 503, passkeys still work |
| `BROKER_VAULT` | the only vault read (default `Claudia`) |
| `BROKER_ORIGIN` | the hub's `https://host`: the page's origin. Required |
| `BROKER_RP_ID` | the passkeys' relying party (default: the origin's host) |
| `BROKER_COMMIT` | the commit, named to 1Password as the integration's version |
| hub: `HUB_BROKER_SOCKET` | the broker's socket as the hub sees it; unset = no broker (`/hub/me` says `vault: false`, the broker's calls answer 503) |

The hub's calls (signed in, same origin; the hub replaces any `email` in the body):

| Call | Answer |
|---|---|
| `GET /hub/passkeys` | `{ passkeys: [{ id, name, createdAt, usedAt }] }` |
| `POST /hub/approve/options` `{ kind: "read", ref, pubkey, request, chat }` \| `{ kind: "add-passkey" }` \| `{ kind: "remove-passkey", id }` \| `{ kind: "root", nonce, profile, chat, action, ask? }` | `{ challengeId, options }` (WebAuthn request options, JSON); 409 no passkey yet, 400 a bad purpose |
| `POST /hub/vault/read` `{ challengeId, response }` | `{ sealed }`; 403 the approval is not open, not theirs, for another kind, or the signature fails; 503 no token; 502 1Password's error |
| `POST /hub/passkeys/register/options` `{ code }` (first passkey) \| `{ challengeId, response }` (an `add-passkey` approval) | `{ registrationId, options }` (WebAuthn creation options) |
| `POST /hub/passkeys/register` `{ registrationId, response, name }` | `{ passkey }` |
| `POST /hub/passkeys/remove` `{ challengeId, response }` (a `remove-passkey` approval) | `{ removed }` |
| `POST /hub/root/grant` `{ challengeId, response }` | `{ grant, signature }` for an approved `kind: "root"` approval (README decision 79): the grant JSON text and its ed25519 signature under the broker's key (`BROKER_DATA/root-grant.pem`, made on first start) |
| `POST /hub/vault/standing` `{ ref, pubkey, request, chat }` | `{ sealed }` without a tap when this chat (`<server>/<chat id>`) read this key with a passkey in the last hour; 404 otherwise (ask for the passkey) |
| `GET /hub/keys` | the broker's admins: `{ items: [{ id, title }], grants: [{ item, email }], admins }`; 403 others |
| `GET /hub/keys/mine` | the keys this person may use (admins: every key; others: the ticked ones), titles sorted: `{ vault, items: [{ title, fields: [{ label, section?, ref }] }] }`, `ref` = `op://<vault>/<item>/[<section>/]<field>` ready for `run_with_secret` (an item, section or field whose title is blank, holds `/` or `?` or has a space at either end is named by its id, and so is an item whose title another item shares, or a field whose title another field of its item shares, without case; a section without a title is left out). No values and no passkey: labels come from 1Password's item reads, mapped to ids and titles at once, and are kept in memory until the item changes. 503 no token; 502 1Password's error |
| `POST /hub/keys/grants` `{ challengeId, response }` (a `grants` approval: `{ kind: "grants", changes: [{ item, email, on }] }`, 1 to 200) | `{ grants }`; admins only |

A read needs the key ticked for that person (admins: every key): 403 before the passkey asks, and again at the read. Every challenge is used once and ends after 5 minutes. On the gate, `broker.js admin <email>` makes a broker admin, and `broker.js enroll <email>` prints
a person's one-time code for their first passkey (30 minutes, 5 tries; a new one replaces the old).

## Root helper (`claudia-root`, `src/claudia/root`; README decision 52)

`out/root.js` runs as root. The host standard (`host/`, Ansible) installs it with `out/sudo.js` and
`deploy/sudo-shim` from a built checkout into a folder of its own (not the release folder Claudia's
update replaces), with its unit `claudia-root.service` and the no-tap list; `deploy/update.sh` and
`install.sh` never touch them. The proof of a person's tap lives in the front (user claudia: no
agent user reaches it, every call to it needs the hub key, decision 47); a runner runs as the agent
user, so it only names chats and shows cards. Settings:

| Program | Variable | What |
|---|---|---|
| root | `ROOT_SOCKET_DIR` | the folder of the agent users' folders (default `/run/claudia-root`). Each folder is named after an agent user (`<profile>-claudia-agent`) and lets in only that user (Ansible: mode 0750, root:<the agent's group>). The helper listens on `<folder>/root.sock` in each (read at start: a new agent user needs a restart), group the folder's, mode 0660; a folder named otherwise, or that others may enter, is skipped and logged |
| root | `ROOT_NO_TAP` | the no-tap list (default `/etc/claudia/root-no-tap`): one command per line, `#` starts a comment line; read on every command |
| root | `ROOT_FRONT_SOCKET` | the front's root socket (default `/run/claudia-front/root.sock`) |
| front | `FRONT_BROKER_GRANT_KEY` | the key broker's root-grant public key (SPKI DER, base64; `broker.js grant-key`), pinned in `/etc/claudia/env` by host apply (README decision 79); unset: root can't be turned on and Allow refuses |
| front | `FRONT_ROOT_SOCKET` | where the front takes the helper's asks (unset: none): its own, mode 0600 in a 0700 folder of user claudia's, so only root opens it (claudia-front.service: `/run/claudia-front/root.sock`) |
| front | `FRONT_ROOT_STATE` | the root switches kept across restarts, `{ "<profile>/<chat>": true }` (unset: in memory only; the unit: `/var/lib/claudia-front/root-switches.json`) |

The shim (`deploy/sudo-shim`, which runs the `sudo.js` beside it; `sudo [-n] [-E] [--] command args…`) sends
newline-delimited JSON on `/run/claudia-root/<user>/root.sock` (`CLAUDIA_ROOT_SOCKET` overrides it;
`CLAUDIA_SUDO_JS` the script). -n and -E are ignored; any other option is refused ("root shells are not
offered; run the command itself"). No socket: "no root helper on this server", exit 1.

1. Shim → helper, first line: `{ argv: [command, args…], cwd, chatKey (CLAUDIA_CHAT_KEY, may be null),
   env: { LANG, LANGUAGE, LC_ALL, LC_CTYPE, LC_MESSAGES, TERM, COLUMNS, LINES, NO_COLOR (those set) } }`.
   argv: 1 to 1000 strings, no NUL, 128 K characters in all.
2. The helper decides. On the no-tap list: argv joined by single spaces equals a line exactly (no
   globbing; an argument that is empty or holds white space never matches). Else it answers
   `{ asking: true }` (the shim says it waits when nothing came within 1.5 s) and asks the front:
   `POST /root/ask { agentUser, chatKey, argv, cwd }` on `ROOT_FRONT_SOCKET`, waiting up to 11
   minutes; the shim leaving ends the call. Only status 200 with exactly `allow: true` and `via`
   `switch` or `allow` runs the command; anything else refuses it.
3. The front: `agentUser` must be `<profile>-claudia-agent` for a profile here. It asks that profile's
   runner `POST /api/root/chat { chatKey }` → `{ chat }` (404: no chat has that key: no). Its own switch
   for `<profile>/<chat>` on: `{ allow: true, via: "switch", chat, by }` (by: the email of who turned
   it on, kept with the switch; README decision 59). Else it makes a nonce, keeps
   `{ profile, chat, argv, cwd }` under it and has the runner show the card: `POST /api/root/card
   { chat, ask: nonce, argv, cwd, expires }`. The person's `POST /api/root/answer { chat, ask, allow,
   argv, cwd }` through the hub counts only from the owner, for that open nonce, profile and chat, and
   (for Allow) the very argv and cwd kept: `{ allow: true, via: "allow", chat, by }` (by: the
   answering owner's email). Deny `{ allow: false,
   why: "The person said no.", chat }`; 10 minutes or the helper leaving: no too. Then `POST
   /api/root/done { chat, ask }` takes the card away. A refused answer: 403 `{ error }`.
   Before an answer or a switch counts, the front asks the target profile's runner `POST
   /api/root/known { chat }` → 200 `{ chat }`, or 404 `{ error }` when it has no such chat: then the
   front answers 404 `{ error: "No such chat." }` and keeps, settles and shows nothing (a runner that
   does not answer: 502). At start it drops kept switches of profiles that have no runner here.
4. Passkey first (README decision 79): turning root on and Allow carry `{ grant, signature }`, the
   key broker's signed word that the owner's passkey approved this very tap. The page asks the front
   `POST /api/root/challenge { chat, action: "on" | "allow", ask? }` (the owner only; ask: the open
   card's nonce, for allow) → `{ nonce, profile }` (a one-time nonce, 5 minutes), then the broker
   `POST /hub/approve/options { kind: "root", nonce, profile, chat, action, ask? }`, signs with the
   passkey and sends `POST /hub/root/grant { challengeId, response }` → `{ grant, signature }`.
   `grant` is JSON text `{ v: 1, kind: "root", email, nonce, profile, chat, action, ask?, expires }`,
   `signature` its ed25519 signature (base64url) under the broker's key. The front counts the tap
   only when the signature verifies under `FRONT_BROKER_GRANT_KEY`, the nonce is one it opened (used
   once), profile, chat, action and ask match the tap, it has not ended, and `email` is the target
   profile's email in the profiles file. Else 403 `{ error }`. Turning off and Deny need none.
5. `POST /api/root/switch { chat, on, grant?, signature? }` (the owner only, else 403) sets the front's switch, writes
   `FRONT_ROOT_STATE`, and has the runner show it (`POST /api/root/shown { chat, root }` → the Row's
   `root`). The runner's `/api/root/*` (known, chat, card, done, shown) decide nothing: an agent that calls
   them can only show cards no one can answer, or a wrong `root` on its own Row.
6. Helper → shim: `{ refused: why }` and the end, or `{ running: true }`, then `{ out: base64 }` and
   `{ err: base64 }` (256 KB of bytes a frame at most) as the command writes, last `{ exit: n }` (a
   signal: 128 + its number; a command not found: 127) and the end. The command runs with
   `child_process.spawn(argv[0], argv[1…])`, no shell, in `cwd` when it is a folder (else `/`), with
   root's PATH, HOME, USER, LOGNAME, SHELL, `SUDO_USER=<user>` and the env above only.
7. Shim → helper after `running`: stdin as `{ in: base64 }` frames, then `{ eof: true }` (frames before
   `running` are dropped). The shim leaving ends the command (SIGTERM).
8. A line over 1 MB ends the connection. Every command is one journal line of the helper:
   `activity { kind: "root", chat (the front's chat id, else "key:<first 8 of the key>" or "no key"),
   profile, agentUser, argv, decision ("no-tap", "switch", "allow", "refused: <why>", "gone before the
   answer"), by?, exit? }` (README decision 59); the front logs each answer and switch change in the
   same shape (decision "allowed", "denied", "switch on", "switch off", by the person's email).

The chat's key: a random key per chat (`chat_key` in the runner's store), set in every session's
environment as `CLAUDIA_CHAT_KEY` when the runner has the host start it. A session started before
there were keys has none until it starts again (its chat's commands are then refused). The runner
names the chat for a key, and it is the agent user's: a tricked agent can at worst make a key name
another chat of its own profile (one whose switch is on, or whose card the person then answers).

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
| `POST /api/chats/<id>/blocks/<block>/template` `{ name, description? }` | `{ ok, text }`: the html block kept as a template (not for a view) |
| `GET /api/chats/<id>/files?root&path` | `{ entries: [{ name, dir, status?, changed?, lines? }] }`: a folder of a files block's `root` (or inside it, or above it up to the home folder; in a view, up to the work folder) |
| `…/files?root&path&read=1&since?` | `{ text, mtime }`, `{ same: true }` while the mtime is `since`, or `{ error }` |
| `…/files?root&path&image=1` | the image itself (up to 25 MB) |
| `GET /api/chats/<id>/git?repo&limit?&branches?&fp?` | `{ fp, commits, head, currentBranch, detached, dirty, branches, more }`, or `{ same: true }` while `fp` is the repo's; only a `repo` a git block of the chat's picture names; 404 without `git` in `CLAUDIA_FORMS` |
| `…/git?repo&commit=<rev or WORKING>&file?` | `{ meta, files, diff, stats, truncated? }`; with `file`, `{ diff }` of that file |

Claude picks every kind of block itself: there are no per-chat choices. The draw tool refuses only
forms deleted in the Forms window (a session started before the delete still lists them); changes
to the forms are told to Claude once, in a `<system-reminder>` after the text of the next message
(the forms news). `templates` and `save_template` are
tools of the `picture` server; a block `{ id, form: "html", data: { template: "<name>", parts? } }` is
drawn from the template. `graph` and `html` are on every runner and cannot be deleted.
A files/git block's `root`/`repo` is made absolute (relative to the chat's folder) before it is kept.

A view's reads stay in the work folder (`claudia.slave.reach`): for a read-only view every disk read
above (files, git, clojure, calls, the project and its logo) is refused (403 `{ error }`) unless its
folder and its path, with `..` resolved and every symlink followed, are inside `CLAUDIA_CWD` (with
symlinks followed), never the home folder or above, and have no part below the work folder starting
with a dot (`.git`, `.env`, `.config`…). In a view a files folder lists no dot names, the files form
goes up only to the work folder, a git commit's or the working tree's files and diff leave dot paths
out, and a calls `file:` id with a dot part is refused. The profile's own reads are not limited by this.
