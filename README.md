# claudia

A small, self-hostable **Next.js** web UI for your local
[Claude Code](https://www.anthropic.com/claude-code) CLI. Point it at any
folder on your machine and:

- **Add / list watched folders** (persisted at `~/.claude/claudia-folders.json`)
- **Browse the filesystem** to pick a folder
- **List past Claude sessions** in a folder (reads `~/.claude/projects/**/*.jsonl`)
- **Open a transcript** or start a new session
- **Resume / chat live**, streaming Claude's output token-by-token. Stop cancels
  the request and kills the spawned process.

The chat view renders Markdown (headings, code blocks, tables, lists),
collapsible tool calls, and live streaming — over a warm, dark theme.

> It's a thin wrapper around the `claude` binary already installed on your
> machine. claudia never talks to Anthropic directly — it spawns your CLI, which
> uses your existing login.

---

## Requirements

- **Node.js 18.18+** (20+ recommended) and npm
- The **Claude Code CLI** installed and authenticated. Verify with:
  ```bash
  claude --version
  claude -p "hello"     # should stream a reply
  ```
  Install instructions: <https://www.anthropic.com/claude-code>

claudia is platform-agnostic — it runs anywhere Node and the `claude` CLI run
(macOS, Linux, WSL).

## Quick start

```bash
git clone https://github.com/p4ulcristian/claudia.git
cd claudia
npm install
npm run dev          # http://localhost:3000
```

Open <http://localhost:3000>, add a folder, and pick or start a session.

## Production

```bash
npm run build
npm start            # serves on $PORT (default 3000)
```

## Configuration (environment)

All optional — sensible defaults are used if unset. For local dev you can copy
`.env.local.example` to `.env.local`.

| Var            | Default                              | Meaning                              |
| -------------- | ------------------------------------ | ------------------------------------ |
| `CLAUDE_BIN`   | `~/.local/bin/claude`, else `$PATH`  | Path to the `claude` CLI binary      |
| `CLAUDE_MODEL` | `claude-opus-4-8`                    | Model passed to `claude --model`     |
| `CLAUDE_HOME`  | `~/.claude`                          | Folder store + session transcripts   |
| `PORT`         | `3000`                               | Server port (prod)                   |

If `claude` isn't on your `PATH` (or lives somewhere custom), set `CLAUDE_BIN`:

```bash
CLAUDE_BIN="$(command -v claude)" npm run dev
```

## How it works

| Concern          | Implementation                                              |
| ---------------- | ---------------------------------------------------------- |
| Folders / browse | REST route handlers under `app/api/*` (Node runtime)       |
| Sessions         | `lib/sessions.ts` reads/parses the CLI's `.jsonl` files    |
| Live chat        | `POST /api/chat` returns an **SSE** `ReadableStream`        |
| Stop             | client `AbortController` → server kills the `claude` process |
| Event folding    | `components/fold.ts` folds raw events → display items       |
| Rendering        | `components/StreamRenderer.tsx` + `components/Markdown.tsx`  |

Spawning lives in `lib/claude-process.ts`; paths/model in `lib/claude-home.ts`.

## Security

claudia executes the `claude` CLI **on the host with
`--dangerously-skip-permissions`**, so it can read, write, and run commands in
any folder you add. Treat it like a terminal on your machine:

- **Run it locally**, or
- If exposed on a network, put it **behind authentication** (reverse proxy with
  auth, VPN, SSO/oauth proxy, etc.). Do not expose it to the open internet
  unprotected.

## Deploy as a service (optional)

Any Node process manager works (pm2, Docker, systemd, …). Minimal systemd unit:

```ini
# /etc/systemd/system/claudia.service
[Unit]
Description=claudia
After=network.target

[Service]
Type=simple
User=YOUR_USER
WorkingDirectory=/path/to/claudia
Environment=PORT=3000
# Environment=CLAUDE_BIN=/home/YOUR_USER/.local/bin/claude
ExecStart=/usr/bin/npm start
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

```bash
npm run build
sudo systemctl enable --now claudia
```

Front it with your reverse proxy of choice (Caddy, nginx, Traefik) and add auth.

## License

MIT — do whatever you like.
