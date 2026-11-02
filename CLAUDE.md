See README.md for what this is, PROTOCOL.md for the one contract between the programs.

- ClojureScript everywhere, built by shadow-cljs (`npx shadow-cljs release <build>`; builds: host runner front hub web). Plain Node at run time (22.13+ for node:sqlite). No Bun, no TypeScript.
- Plain JSON on every wire; keyword keys only inside ClojureScript (`claudia.shared.protocol`).
- Folders: `src/claudia/hub` = the master (gate), `src/claudia/slave` = front, runner, host (every server), `src/claudia/web` = the page (Reagent + re-frame), `src/claudia/shared` = .cljc both use.
- Public repository: never commit domains, addresses, emails, tokens or host names. Those live in each machine's environment files.
- The core rule: chat replies stay short; explanation goes into the picture (the runner's system prompt says so).
- The TypeScript Claudia (branch main) is the behaviour reference, not a template: this is a clean slate.
