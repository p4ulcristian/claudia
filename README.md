# Claudia

Chat with Claude on the left; Claude explains on the right, in a picture it draws as it works. Chat stays short, like two friends talking; the explaining lives in the picture. When Claude asks you something, the right side shows it as buttons.

- Open: `omarchy-launch-webapp http://claudia.localhost:4510/`
- Service: `claudia.service` (user unit, symlinked into ~/.config/systemd/user). After code changes: `systemctl --user restart claudia`
- Dev with hot reload: stop the service, run `bun --hot server.ts`

## Decisions (2026-10-16)

1. Only Paul uses it, on his Claude subscription.
2. Claude runs through the Claude Agent SDK; drawing is Claude's own tool (`mcp__picture__draw`).
3. The picture is a fixed set of parts (circles, boxes, arrows, groups, and readable cards: text, list, table, code). The app lays them out (ELK) and animates what changed.
4. The picture stays; Claude changes it when a turn changes something. Small talk leaves it.
5. Chats are Claude Code sessions (`~/.claude/projects`), so `claude --resume` and the history window see them. The app keeps only the pictures (`~/.local/share/claudia/app.db`).
6. Every chat runs in `~/Work`, where the full memory lives.
7. A desktop window on my own machine; reachable from elsewhere through a login-gated reverse proxy (`TUNNEL_HOST` + `ALLOWED_EMAIL`).

8. File changes show in a Changes panel on the right: one diff per file per turn. Copies come from a hook before Claude's Edit/Write tools, and from a git snapshot of the working folder's repo (a separate index file; the user's index is untouched), so shell edits count too.
9. A working folder per chat that Claudia tracks (the session itself stays in ~/Work for the full memory): set when Claude first edits a file, or by Claude with `mcp__workspace__set_folder`, any time. See `plan/diffs-and-folders.md`.

10. The subscription follows `claude-account`, the script behind the desktop widget's account switch (since 2026-10-17): Claudia asks it for the selected account's long-term token (`claude-account token`) and gives it to Claude Code as `CLAUDE_CODE_OAUTH_TOKEN`. A switch takes effect on each chat's next turn (Claude Code starts again on the new account and resumes the session). Without `claude-account` the chats use the `claude /login`. A long-term token gets no usage readout, so the limits under the input come from the last turn's rate-limit event, kept per account.

Questions use Claude Code's own AskUserQuestion tool. Permission mode matches the terminal: bypass, so permission asks don't come up.

## Files

- `server.ts`: HTTP + WebSocket, chat list, rename, delete
- `src/chat.ts`: one chat = one Agent SDK session; turns, questions, stop, history
- `src/prompt.ts`: what Claude is told about the two halves, and the draw tool
- `src/scene.ts`: the picture's parts and how a draw call changes them
- `src/changes.ts`: before/after copies, git snapshots, diffs
- `src/account.ts`: the account and token claude-account has selected
- `src/meta.ts`: models, slash commands and plan limits, read by a short probe
- `src/db.ts`: pictures, changes and chat settings
- `web/`: the page (React): `chat.tsx` left, `picture.tsx` + `panels.tsx` right
