# Claudia (beta: ClojureScript)

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

## Build

```
npm ci
npx shadow-cljs release host runner front hub web
```
