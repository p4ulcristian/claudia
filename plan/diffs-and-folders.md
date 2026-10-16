# Plan: diffs with a Haiku explanation, and Claude picking the folder

Planning only (2026-10-16). One decision at a time; each line is the user's choice.

Data source (stated, not a choice): before Claude edits or writes a file, Claudia saves a copy; after the turn it diffs. Exact, works outside git, never mixes in the user's own changes.

## Diffs
1. Where: a Changes panel on the right (one row per changed file, Haiku's line under it, click opens the diff). Not in the chat steps, not in the picture.
2. Granularity: one diff per file per turn (before the turn vs after). Stepping back to an earlier picture shows that turn's changes.
3. Haiku explains automatically after every turn that changed files: one call per turn (diffs + the user's message), one plain line per file.
4. Layout: one column (red removed, green added, changed words highlighted, only changed parts + context, expandable), with a remembered toggle to side by side.

## Folder
5. A working folder Claudia tracks; the Claude Code session itself stays in ~/Work (a session's folder can't change while it runs).
   - Set automatically when Claude first edits a file: that file's project (git root).
   - Claude can switch it any time with a small tool; each switch hands Claude that project's CLAUDE.md and git status.
   - Shown as a chip; the Changes panel and diffs follow it.
   - Cost, accepted: the project's own .claude/settings.json and its memory notes aren't loaded; `claude --resume` from inside the project won't list the chat.
6. Memory: settled by 5 (stays ~/Work, full memory always).

Status: built 2026-10-16. Addition while building: shell edits are caught by a git snapshot of the working folder's repo at the start of each turn (Claude often edits with sed/echo, which the Edit/Write hook can't see). Outside git, only Edit/Write edits are caught.
