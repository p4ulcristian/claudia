# Claudia beta: the protocol

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
| hub | `HUB_CONFIG` | path of `hub.json` (below) |
| hub | `HUB_WEB` | folder of the built page (`out/web`) |
| front | `FRONT_HOST`, `FRONT_PORT` | the WireGuard address it listens on |
| front | `FRONT_PROFILES` | path of the profiles file (below) |
| front | `FRONT_NAME` | this server's name (`destiny`, `box`) |
| runner | `RUNNER_SOCKET` | unix socket it serves HTTP + WebSocket on |
| runner | `HOST_SOCKET` | the host's unix socket |
| runner | `RUNNER_DATA` | folder for `claudia.db` and uploads |
| runner | `RUNNER_PROFILE` | the profile's name (`paul`) |
| runner | `CLAUDIA_CWD` | where chats run (default `~/Work`) |
| runner | `CLAUDIA_TOKEN_FILE` | file holding the Claude long-term token (`sk-ant-oat…`) |
| runner | `CLAUDIA_OP_ACCOUNT` | 1Password account (sign-in address or id) for this profile's reads |
| runner | `CLAUDIA_VAULT_SERVER` | the server whose 1Password app unlocks for this profile (`destiny`) |
| host | `HOST_SOCKET` | unix socket it listens on |

`hub.json`:
```json
{ "servers": [ { "name": "destiny", "label": "destiny", "address": "10.0.0.2:4540" } ],
  "people":  [ { "email": "a@example.com", "name": "Paul", "admin": true, "servers": ["destiny"] } ] }
```
The profiles file (front): one line per profile, `<profile> <email> <runner socket path>`.

## Who is calling

- Caddy clears `X-Auth-Request-*` and `X-Claudia-*` from the browser, then sets
  `X-Auth-Request-Email` from the login.
- hub → front: `X-Claudia-Email: <email>`, and `X-Claudia-View: <profile>` when the caller looks at
  another profile's chats (read only). The hub drops any `X-Claudia-*` that came from the browser.
- front → runner: `X-Claudia-Profile: <profile>` (whose chats), `X-Claudia-Read-Only: 1` for a view.
  A read-only caller may only `GET` and send `hello`/`open` on the socket.

## Hub HTTP

| Call | Answer |
|---|---|
| `GET /` and `/c/*` | the page (`index.html`), `cache-control: no-cache` |
| `GET /js/*`, `/css/*`, `/fonts/*`, `/img/*` | the built page's files |
| `GET /hub/me` | `{ email, name, admin, servers: [{ name, label }] }` (only servers this person may use) |
| `GET /hub/servers/:name/state` | `{ up: bool }` (a quick look at the front) |
| `ANY /s/<server>/api/…` | passed to that front's `/api/…` (refused 403 if not in the person's list) |
| `WS /s/<server>/ws` | passed to that front's `/ws` |

## Front / runner HTTP (`/api/…`)

| Call | Answer |
|---|---|
| `GET /api/me` | `{ server, profile, viewing, readOnly, version, commit }` |
| `POST /api/secret/read` `{ ref, pubkey, request }` | the vault read (below): `{ sealed }` or `{ error }` |

## WebSocket (`/ws`): page ↔ runner

One socket per server the page uses. Every frame is a JSON object with `type`.

Page → runner:

| type | fields | effect |
|---|---|---|
| `hello` | | runner answers `chats` |
| `open` | `chat` | runner answers `chat` (full state) and sends that chat's live events from then on (several open chats per socket are fine) |
| `close` | `chat` | stop sending that chat's live events |
| `new` | `text`, `folder?`, `id` (a uuid the page made) | starts a chat with that first message; runner answers `created { chat, id }` then events |
| `send` | `chat`, `text` | a message into the chat (queued if a turn runs) |
| `stop` | `chat` | interrupts the running turn |
| `answer` | `chat`, `ask`, `answers` (`{ "<question>": "<label>" }`) | answers an AskUserQuestion |
| `secret` | `chat`, `ask`, `sealed` | the sealed secret for a `run_with_secret` ask |
| `deny` | `chat`, `ask` | refuses a secret ask |
| `rename` | `chat`, `title` | |
| `archive` | `chat`, `archived` (bool) | |
| `seen` | `chat` | the person looked at its last turn |

Runner → page:

| type | fields |
|---|---|
| `chats` | `chats: [Row]`, sent on `hello` and on every change of any row |
| `chat` | `chat`, `title`, `folder`, `messages: [Msg]`, `scene: Scene`, `running`, `asks: [Ask]` |
| `created` | `chat`, `id` (the page's uuid, to match) |
| `msg` | `chat`, `msg: Msg` (a new or replaced message, by `msg.id`) |
| `delta` | `chat`, `id`, `text` (streamed text appended to message `id`) |
| `scene` | `chat`, `scene: Scene` (the whole picture; it is small) |
| `turn` | `chat`, `running` (bool) |
| `ask` | `chat`, `ask: Ask` |
| `asked` | `chat`, `ask` (id: answered, denied or expired; remove the card) |
| `error` | `chat?`, `text` |

`Row`: `{ id, title, folder, color, running, waiting (bool: an open ask), at (last activity),
preview (start of the last reply or the open question), unseen, archived }`

`Msg`: `{ id, role: "user" | "assistant" | "tool" | "note", text?, at }`; a tool step has
`tool` (name) and `summary` (one line: `Edit src/x.cljs`, `$ npm test`, `draw`), and `error?` (bool).

`Ask`:
- `{ id, kind: "question", questions: [{ question, header, multiSelect, options: [{ label, description }] }] }`
- `{ id, kind: "secret", ref, reason, account, vault (server name), pubkey (base64), expires }`

`Scene`: `{ title, blocks: [Block] }` in order, top to bottom. A `Block` has `id`, `name`, `kind`
(`"graph"`, `"html"`, `"form"`), `width` (`"full"`/`"half"`) and:
- graph: `nodes: [{ id, label, shape, color, note, group, state }]`, `edges: [{ from, to, label, dashed, color }]`, `groups: [{ id, label, color }]`
- html: `html`, `parts: { name: markup }`
- form: `form` (`pipeline`, `timeline`, `compare`, `chart`, `stats`), `data` (as the draw tool's description says)

The runner applies each `draw` call (same input as the draw tool: `title`, `clear`, `graph`, `nodes`,
`edges`, `groups`, `remove`, `blocks`, `removeBlocks`) to the chat's scene and sends `scene`.

## Secrets (1Password, `plan/1passwd-plan.md`)

1. Claude calls `mcp__workspace__run_with_secret { ref, env_name, reason, command }` in a chat on server S.
2. S's runner makes a one-time keypair (libsodium `crypto_box_keypair`), sends `ask { kind: "secret", … pubkey }`.
3. Allow: the page calls `POST /s/<vault>/api/secret/read { ref, pubkey, request }` on the person's
   vault server V (the hub lets only that person's own profile through). V's runner runs
   `op read --account <CLAUDIA_OP_ACCOUNT> <ref>` (the desktop app asks for the fingerprint), seals the
   value to `pubkey` (`crypto_box_seal`) and answers `{ sealed }` (base64). Paste instead: the page
   seals the pasted text to `pubkey` itself.
4. The page sends `secret { chat, ask, sealed }` to S. S opens it, runs `command` (in the chat's folder,
   `sh -c`) with `env_name=<value>` in that process's environment only, replaces every occurrence of
   the value in the output with `[secret]`, and answers Claude with the output. The keypair is dropped.
5. Deny or 10 minutes without an answer: Claude gets a refusal. Nothing is written anywhere: not the
   database, not a log, not a file. Only the ref, the reason and who allowed it are logged.

## Host ↔ runner (unix socket, JSON lines)

Runner → host: `{ op: "start", id, spec }` (spec = SDK options without callbacks + the MCP tool list
(`{ server: { tool: { description, inputSchema (JSON Schema) } } }`) + hook matchers),
`{ op: "send", id, text }`, `{ op: "interrupt", id }`, `{ op: "close", id }`, `{ op: "reply", rpc, value | error }`, `{ op: "list" }`.

Host → runner: `{ ev: "msg", id, msg }` (an SDK message as is), `{ ev: "rpc", rpc, id, kind: "mcp" | "hook" | "canUseTool", args }`,
`{ ev: "ended", id, error? }`, `{ ev: "sessions", list: [{ id, busy }] }` (answer to `list`, and sent
on every runner connect so it can take running chats back).
