See README.md for what this is and the decisions behind it.

- Bun everywhere: `bun install`, `bun server.ts`, `bun:sqlite`, HTML imports for the page (no Vite).
- Type-check: `bunx -p typescript@5 tsc --noEmit -p .`
- Server code changes need `systemctl --user restart claudexplain`; never stop it with `pkill -f` (other services run `bun server.ts` too).
- The core rule: chat replies stay short; explanation goes into the picture. Keep that in `src/prompt.ts` when changing what Claude is told.
