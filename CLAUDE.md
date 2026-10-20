See README.md for what this is and the decisions behind it.

- Bun everywhere: `bun install`, `bun server.ts`, `bun:sqlite`, HTML imports for the page (no Vite).
- Type-check: `bunx -p typescript@5 tsc --noEmit -p .`
- Two services: page/proxy edits (`web/`, `server.ts`, `src/proxy.ts`, `src/tunnel.ts`) need `systemctl --user restart claudia`, which keeps chats running. Runner edits (`runner.ts`, the rest of `src/`) need `systemctl --user restart claudia-runner`, which stops every running chat: say so before doing it. Never stop either with `pkill -f` (other services run `bun server.ts` too).
- The core rule: chat replies stay short; explanation goes into the picture. Keep that in `src/prompt.ts` when changing what Claude is told.
