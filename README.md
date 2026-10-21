# Claudia

Chat with Claude on the left; Claude explains on the right, in a picture it draws as it works. Chat stays short, like two friends talking; the explaining lives in the picture. When Claude asks you something, the right side shows it as buttons.

- Open: `omarchy-launch-webapp http://claudia.localhost:4510/`
- Two services (user units, symlinked into ~/.config/systemd/user), so the page can restart without stopping chats:
  - `claudia.service` (`server.ts`, :4510): the page, and a pass-through of `/api` and `/ws` to the runner. After edits to `web/`, `server.ts`, `src/proxy.ts`, `src/tunnel.ts`: `systemctl --user restart claudia`. Chats keep running; the page reconnects.
  - `claudia-runner.service` (`runner.ts`, 127.0.0.1:4511): the Claude sessions, the chat socket, the API. After edits to `runner.ts` or the rest of `src/`: `systemctl --user restart claudia-runner`. This stops every running chat.
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

Questions use Claude Code's own AskUserQuestion tool. Permission mode matches the terminal: bypass, so permission asks don't come up.

## Files

- `server.ts`: the page; picks the profile and passes `/api` and `/ws` to its runner; the gate's listener
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
- `src/timeline.ts`: lanes and steps from the session files, read only from where the last read stopped
- `deploy/`: a shared server: `install.sh`, the page unit, the per-profile runner unit, and `update.sh` + its timer (follows GitHub main every 5 min; runners not restarted)
- `web/`: the page (React): `settings.tsx` Settings and the logo, `chat.tsx` left, `picture.tsx` + `panels.tsx` + `timeline.tsx` right, `rightbar.tsx` the bar under it
