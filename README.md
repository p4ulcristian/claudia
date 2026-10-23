# Claudia

Chat with Claude on the left; Claude explains on the right, in a picture it draws as it works. Chat stays short, like two friends talking; the explaining lives in the picture. When Claude asks you something, the right side shows it as buttons.

- Open: `omarchy-launch-webapp http://claudia.localhost:4510/`
- Three services (user units, symlinked into ~/.config/systemd/user), so the page and the runner can restart without stopping chats:
  - `claudia.service` (`server.ts`, :4510): the page, and a pass-through of `/api` and `/ws` to the runner. After edits to `web/`, `server.ts`, `src/proxy.ts`, `src/tunnel.ts`: `systemctl --user restart claudia`. Chats keep running; the page reconnects.
  - `claudia-runner.service` (`runner.ts`, 127.0.0.1:4511): the chats, the chat socket, the API. After edits to `runner.ts` or the rest of `src/`: `systemctl --user restart claudia-runner`. Chats keep running: their Claude Code lives in the host, which hands every live session back.
  - `claudia-host.service` (`host.ts`, `$XDG_RUNTIME_DIR/claudia-host.sock`): the session host, which owns every chat's Claude Code process. Restarting it stops every running chat (the next message resumes it), so it is kept small and rarely changes; after edits to `host.ts` or `src/host-protocol.ts`: `systemctl --user restart claudia-host`.
- Dev with hot reload: stop `claudia`, run `bun --hot server.ts` (the runner keeps serving chats)

## Decisions (2026-10-16)

1. Only the owner uses it, on their Claude subscription. (Since 2026-10-20 also a team instance, see 11.)
2. Claude runs through the Claude Agent SDK; drawing is Claude's own tool (`mcp__picture__draw`).
3. The picture is a fixed set of parts (circles, boxes, arrows, groups, and readable cards: text, list, table, code). The app lays them out (ELK) and animates what changed.
4. The picture stays; Claude changes it when a turn changes something. Small talk leaves it.
5. Chats are Claude Code sessions (`~/.claude/projects`), so `claude --resume` and the history window see them. The app keeps only the pictures (`~/.local/share/claudia/app.db`).
6. Every chat runs in `~/Work`, where the full memory lives.
7. A desktop window on my own machine; reachable from elsewhere through a login-gated reverse proxy (`TUNNEL_HOST` + `ALLOWED_EMAIL`).

8. File changes show in a Changes panel on the right: one diff per file per turn. Copies come from a hook before Claude's Edit/Write tools, and from a git snapshot of the working folder's repo (a separate index file; the user's index is untouched), so shell edits count too.
9. A working folder per chat that Claudia tracks (the session itself stays in ~/Work for the full memory): set when Claude first edits a file, or by Claude with `mcp__workspace__set_folder`, any time. See `plan/diffs-and-folders.md`.

10. The subscription is one long-term token (`claude setup-token`) pasted in Settings, kept in a file and read again on every turn; every profile of an instance runs on it, and anyone signed in can replace it (never read it back). A new token applies to each chat on its next message. A long-term token gets no usage readout, so the limits under the input come from the last turn's rate-limit event.

11. Profiles (2026-10-20, `plan/box-and-profiles.md`): the same code runs everywhere; an instance differs only by its settings. The gate's signed-in email picks a profile from the instance's profiles list (`<profile> <email> [runner]`); each profile has its own runner, store, preferences and GitHub login. The desktop window (127.0.0.1) is `CLAUDIA_LOCAL_PROFILE`; the gate's listener (`GATE_HOST`) believes the login header, so only the gate may reach it. `CLAUDIA_NAME` shows under the logo. On a shared server each profile's runner is its own Linux user (`claudia-<profile>`, `deploy/`).

12. A timeline (2026-10-21) in place of the picture (Picture | Timeline at the bottom right, Alt+T): every step of the chat and its subagents as one feed, top to bottom in time order, with a thin rail per agent on the left showing who ran at the same time (rails are reused by later agents); quiet stretches get a divider with their length; a filter shows one agent and the agents it started. Edit and Write steps show their +/− lines and open as a diff, from the patch Claude Code saves with each result (shell edits have none; the Changes panel covers them). Built from the session files themselves (`<session>.jsonl` and `<session>/subagents/agent-*.jsonl` + `.meta.json`), so it survives runner restarts; a step opens in full from its place in the file.

13. A session host (2026-10-22): each chat's Agent SDK query runs in `host.ts`, not the runner. The host knows nothing about Claudia: it streams messages to the runner and passes every callback (the picture and folder tools, the edit hooks, permission asks) to it as an rpc, waiting while the runner restarts. On reconnect it hands back each live session: whether a turn is running, the current turn's messages (plus background tasks' state), what the runner stored there (turn, before-copies of files, unsent messages), and unanswered rpcs, so questions show again.

14. Page cards (2026-10-22): a card can be Claude's own small HTML page (`kind: "page"`, `html`) for what boxes and arrows can't show: charts, sliders, clickable mockups, visual picks. It runs in a sandboxed iframe (`allow-scripts` only: no access to Claudia, its cookies or API), with a CSP that allows only inline code plus scripts/styles from cdn.jsdelivr.net, Claudia's theme as CSS variables, its fonts, and a height fitted to the page. Measured cost: ~1.5k tokens for a page vs ~0.45k for a drawing.

15. Start a chat from a link (2026-10-22): `/new?text=…` opens a new chat of your own (leaving another profile's read-only view) with the text in the message box, not sent; you press Enter. Eye on Rainbow's "Open in Claudia" uses it. A link that sent the text by itself would let any link drive someone's Claude.

16. Asked from outside (2026-10-22): a program that can reach a profile's runner (on the box: the page, and the users its socket's ACL adds, e.g. Eye on Rainbow for its character Gus) starts a chat with `POST /api/delegate {text}` and follows it with `GET /api/chats/:id/state` (running, waiting on a question, last answer, and its newest step, tool count, start and running agents for a progress line); `POST /api/chats/:id/send {text}` adds a follow-up to that chat (2026-10-22). `POST /api/chats/:id/answer {text}` answers its open question(s) in words. The chat is the profile's own, in its sidebar like any other, so the person can open it and steer.

17. The git form (2026-10-22): a repo's history as GitGud showed it (commit graph in lanes, branches local/remote/both with ahead/behind, the uncommitted changes as a dashed node, a commit's files and word-level diff), read live through the runner (`src/git.ts`) and redrawn when the repo's fingerprint changes. Read only: no checkout, branch or fetch buttons; git actions stay in the chat where the user sees them. It is an opt-in form: only runners started with `CLAUDIA_FORMS=git` have it (destiny's runner drop-in); elsewhere Claude doesn't get it and the page doesn't show it.

18. Form row states (2026-10-23): each circle is allowed (default) → required → off. Required means the picture always holds at least one block of that kind: Claude adds it on the next message and draw refuses a change that takes the last one away; the other kinds stay allowed. Off: draw refuses it and its blocks leave the picture. Questions only go allowed ↔ off. Instead of green = "only these kinds", which blocked every other kind with one click.

Questions use Claude Code's own AskUserQuestion tool. Permission mode matches the terminal: bypass. The few calls bypass still sends to the permission callback (its own safety checks) are allowed and logged by the runner, so no permission ask shows; only questions and plan approval reach the user.

## Files

- `server.ts`: the page; picks the profile and passes `/api` and `/ws` to its runner; the gate's listener
- `host.ts`: the session host; `src/host-protocol.ts` what it and the runner say; `src/host-client.ts` the runner's end
- `runner.ts`: chats, WebSocket, chat list, rename, archive
- `src/proxy.ts`: request and socket pass-through (page → runner, by port or unix socket)
- `src/profiles.ts`: which profile a request is, where its runner listens; the Claude token file
- `src/mode.ts`: what sets an instance apart: token file, name
- `src/github.ts`: the profile's GitHub login (gh), used for pushes and pull requests
- `src/chat.ts`: one chat = one Agent SDK session; turns, questions, stop, history
- `src/prompt.ts`: what Claude is told about the two halves, and the draw tool
- `src/scene.ts`: the picture's parts and how a draw call changes them
- `src/changes.ts`: before/after copies, git snapshots, diffs
- `src/account.ts`: the Claude token the chats run on
- `src/meta.ts`: models, slash commands and plan limits, read by a short probe
- `src/db.ts`: pictures, changes and chat settings
- `src/forms.ts`: the predefined forms; `src/files.ts` and `src/git.ts` (+ `src/git-layout.ts`) read for the files and git forms; renderers in `web/forms.tsx`, `web/git.tsx` and `web/flow.tsx`
- `src/timeline.ts`: lanes and steps from the session files, read only from where the last read stopped
- `deploy/`: a shared server: `install.sh`, the page unit, the per-profile host and runner units, and `update.sh` + its timer (follows GitHub main every 5 min; runners restart, hosts only with `--restart-hosts`)
- `web/`: the page (React): `settings.tsx` Settings and the logo, `chat.tsx` left, `picture.tsx` + `panels.tsx` + `timeline.tsx` right, `rightbar.tsx` the bar under it, `activity.tsx` the pill at its top right (background agents + changes)
