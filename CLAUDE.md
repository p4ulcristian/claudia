See README.md for what this is, PROTOCOL.md for the one contract between the programs.

- ClojureScript everywhere, built by shadow-cljs (`npx shadow-cljs release <build>`; builds: host runner front hub root sudo web). Plain Node at run time (22.13+ for node:sqlite). No Bun, no TypeScript.
- Plain JSON on every wire; keyword keys only inside ClojureScript (`claudia.shared.protocol`).
- Folders: `src/claudia/hub` = the master (gate), `src/claudia/slave` = front, runner, host (every server), `src/claudia/root` = the root helper and the sudo shim (every Linux server, README decision 52), `src/claudia/web` = the page (Reagent + re-frame), `src/claudia/shared` = .cljc every program uses, `src/claudia/node` = what the hub and the slave programs share on Node (env, http, fs). Tests in `test/` (namespaces ending in `-test`).
- Public repository: never commit domains, addresses, emails, tokens or host names. Those live in each machine's environment files.
- The core rule: chat replies stay short; explanation goes into the picture (the runner's system prompt says so).

## How the code is written (README decision 9)

- Small pure functions: data in, data out, one job each, named for that job. No function over 30 lines, views included (split markup into small view functions).
- Side effects at the edges: a handler reads state, calls pure functions, then writes once (one `swap!`/`reset!`). No volatiles in logic, no mutation hidden inside helpers.
- No local name shadows a var (a local `keep` once took the hub down). clj-kondo's `shadowed-var` is counted by the ratchet.
- One copy of every helper: text (`claudia.shared.text`), env/http/fs (`claudia.node.*`), the page's fetches (`claudia.web.api`), times and paths (`claudia.web.format`), hooks (`claudia.web.hooks`: `use-resize`, `use-width`, `use-poll`). A helper needed twice moves there; never a local copy.
- Every pure function pulled out gets a test on plain data in `test/`.
- Before pushing: `clj-kondo --lint src test --fail-level error`, `node bin/ratchet.mjs` (run `--write` after lowering a count; counts only go down), `npm test`, `npx shadow-cljs release host runner front hub root sudo web`, `npm run build:web-files`, `node bin/smoke.mjs` (the built programs and the page in headless Chromium, README decision 35). The PR check (`.github/workflows/check.yml`) and the release builder run the same.
