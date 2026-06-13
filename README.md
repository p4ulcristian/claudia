# claudia

A standalone **Next.js** app that reproduces the *behaviour* of Olympus
Matrix's "Claude Manager" entity — with a fresh architecture (plain REST +
SSE, no Transit/WebSocket multiplexer):

- **Add / list watched folders** (persisted at `~/.claude/claudia-folders.json`)
- **Browse the filesystem** to pick a folder
- **List past Claude sessions** in a folder (reads
  `~/.claude/projects/<encoded-cwd>/*.jsonl`)
- **Open a transcript** or start a new session
- **Resume / chat live**, streaming `claude --output-format stream-json` output
  token-by-token. Stop cancels the request (and kills the spawned process).

## Architecture

| Concern            | Implementation                                              |
| ------------------ | ----------------------------------------------------------- |
| Folders / browse   | REST route handlers under `app/api/*` (Node runtime)        |
| Sessions           | `lib/sessions.ts` reads/parses the CLI's `.jsonl` files     |
| Live chat          | `POST /api/chat` returns an **SSE** `ReadableStream`        |
| Stop               | client `AbortController` → server kills the `claude` process |
| Event rendering    | `components/fold.ts` folds raw events → display items       |

Spawning lives in `lib/claude-process.ts`; paths/model in `lib/claude-home.ts`.

## Local dev

```bash
npm install
npm run dev          # http://localhost:3000
```

## Config (env)

| Var            | Default                          | Meaning                          |
| -------------- | -------------------------------- | -------------------------------- |
| `CLAUDE_BIN`   | `~/.local/bin/claude` or PATH    | claude CLI binary                |
| `CLAUDE_MODEL` | `claude-opus-4-8`                | model to run                     |
| `CLAUDE_HOME`  | `~/.claude`                      | folder store + session transcripts |
| `PORT`         | `3000`                           | server port (prod)               |

## Production / deployment

Runs on **iris-machine (`10.99.0.2`)** as a systemd service, fronted by the
VPS Caddy at `claudia.irisdoes.work` (oauth2-proxy protected, like the other
authenticated routes).

```bash
npm run build
sudo systemctl restart claudia      # unit: /etc/systemd/system/claudia.service
```

- **Service:** `claudia.service` (`User=iris`, runs `prod.sh` → `next start -p 3000`)
- **Route:** added to `iris-router/vps/Caddyfile` as a `protected` block →
  `reverse_proxy 10.99.0.2:3000`; deploy with `iris-router/deploy.sh`.
- **DNS:** `claudia.irisdoes.work` A → `136.244.88.99` (DNS-only), covered by
  the `*.irisdoes.work` wildcard TLS cert.

To redeploy code: `npm run build && sudo systemctl restart claudia`.
To change routing: edit the Caddyfile and run `iris-router/deploy.sh`.
