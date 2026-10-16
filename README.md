# claudeXplain

Chat with Claude on the left; Claude explains on the right, in a picture it draws as it works. Chat stays short, like two friends talking; the explaining lives in the picture. When Claude asks you something, the right side shows it as buttons.

- Open: `omarchy-launch-webapp http://claudexplain.localhost:4510/`
- Service: `claudexplain.service` (user unit, symlinked into ~/.config/systemd/user). After code changes: `systemctl --user restart claudexplain`
- Dev with hot reload: stop the service, run `bun --hot server.ts`

## Decisions (2026-10-16)

1. Only Paul uses it, on his Claude subscription.
2. Claude runs through the Claude Agent SDK; drawing is Claude's own tool (`mcp__picture__draw`).
3. The picture is a fixed set of parts (circles, boxes, arrows, groups, and readable cards: text, list, table, code). The app lays them out (ELK) and animates what changed.
4. The picture stays; Claude changes it when a turn changes something. Small talk leaves it.
5. Chats are Claude Code sessions (`~/.claude/projects`), so `claude --resume` and the history window see them. The app keeps only the pictures (`~/.local/share/claudexplain/app.db`).
6. Every chat runs in `~/Work`, where the full memory lives.
7. A desktop window on destiny only.

Questions use Claude Code's own AskUserQuestion tool. Permission mode matches the terminal: bypass, so permission asks don't come up.

## Files

- `server.ts`: HTTP + WebSocket, chat list, rename, delete
- `src/chat.ts`: one chat = one Agent SDK session; turns, questions, stop, history
- `src/prompt.ts`: what Claude is told about the two halves, and the draw tool
- `src/scene.ts`: the picture's parts and how a draw call changes them
- `src/db.ts`: pictures per turn
- `web/`: the page (React): `chat.tsx` left, `picture.tsx` + `panels.tsx` right
