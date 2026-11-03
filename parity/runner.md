# Parity: runner, host, front, hub (not visual)

Old = branch main at aeb57a1 (`src/*.ts`, `runner.ts`, `server.ts`, `host.ts`, `hub.ts`, `src/hub/*`).
Beta = `src/claudia/slave/**`, `src/claudia/hub/**`. Marks: [x] same, [~] different (how), [ ] missing.
The list below is the inventory as found; the final state follows it.

## What Claude is told
- [~] System prompt = `src/prompt.ts` SYSTEM_APPEND word for word (beta had its own shorter text; no "Images in the chat"; "~/Work" line differs) (`src/prompt.ts:7-56`)
- [~] Beta's own Secrets section (run_with_secret) kept, added after "Working folder"
- [~] The forms' data also in the system prompt (beta, `codeforms/prompt-for`); old had it only in the draw tool (`src/forms.ts:302-306`)
- [~] draw description: old order of forms (pipeline, timeline, compare, chart, stats, files, git, clojure, calls, editor, scene3d, flow), old when/fields texts (`~/…`, scene3d allow/scan/overlap sentences) (`src/forms.ts`)
- [~] draw schema: `blocks` description, `kind`/`html` descriptions, `template` and `height` properties, property order (`src/prompt.ts:63-127`)
- [~] set_folder description "relative to ~/Work" (`src/prompt.ts:141`)
- [x] show_image description and schema (`src/prompt.ts:136`)
- [x] templates, save_template descriptions and schemas (`src/prompt.ts:158-175`)
- [~] allowedTools: draw, templates, save_template, set_folder, show_image (+ beta's run_with_secret) (`src/chat.ts:1080`)
- [ ] Profile prefs: `hiddenForms` (forms deleted in the Forms window leave the tool), `chatColors` each|none (`src/db.ts:122`, `runner.ts:230-240`)

## Forms news, form row
- [~] formsSeen set at session start (old: no news on the first draw of a fresh session) (`src/chat.ts:1094`)
- [ ] forms news also with the next message after a change, not only in draw results (`src/chat.ts:781`)
- [~] reminder placement: `<text>\n\n<system-reminder>news formsNote</system-reminder>` after the text (beta put it first) (`src/chat.ts:784`)
- [~] form-row note said again after a runner start with the next message even when the session stayed live (`src/chat.ts:503`)
- [x] formsNote wording, instantForms (auto-files/git/clojure), Required refusal message, off refusal (`src/chat.ts:417-556`, `906-1000`)
- [~] questions off message (`src/chat.ts:1191`)
- [~] tool changes reach a live session by setMcp, no restart; a session that refuses is restarted after its turn and only when no task runs (beta closed it, even while background tasks ran) (`src/chat.ts:1168-1179`, `663-667`)
- [ ] prompt changed since a session started: restart it once after its turn (`src/chat.ts:1158-1161`)

## Draw (scene.ts)
- [~] result text "Picture updated: <describe>" + news + `. No template named …` + `. Not drawn: …: switched off by the user; use another kind` + `. Still missing, required …` + kept (`src/chat.ts:1009`)
- [~] describe: freeform `from template "<name>"` (beta dropped `template`) (`src/scene.ts:225-235`)
- [~] html block keeps `template` and `height`; html assumed when template given (`src/scene.ts:181-195`)
- [ ] `changed` ids (nodes, arrows, blocks, block:part, block:item) sent with the scene (`src/scene.ts:153-210`)
- [x] graph upsert, edges by from->to, remove, dangling edges/groups dropped, empty graph blocks dropped
- [x] scene3d: one per picture, takes the old one's place
- [x] screenshot: image block, or "(No screenshot of the picture: <why>)" (`src/chat.ts:1014-1020`)
- [x] templates list text, save_template texts (`src/chat.ts:373-378`, `1052-1067`)

## Pictures per turn
- [~] turn = the last sent message's uuid, kept for turns Claude starts itself (beta made a new id per self-started turn) (`src/chat.ts:794`, `1006`)
- [~] user edits (editor) saved into the picture of the turn that drew it (`src/chat.ts:1024-1033`)

## Workspace tools
- [~] set_folder: `…` in the text, empty CLAUDE.md falls to .claude/CLAUDE.md, git status lines as old (`src/chat.ts:568-582`)
- [~] show_image: messages and order of checks, 5 MB = 5 000 000 bytes, "Shown in the chat: <file> (<caption>)." (`src/chat.ts:585-595`)
- [x] edit hook: folder from the edited file's git root (`src/chat.ts:620-625`, `src/changes.ts:49`)

## Chat lifecycle
- [x] start / resume (getSessionInfo), bypassPermissions, partial messages, model/effort
- [x] idle close after 15 min, never while work it started runs
- [ ] token switched since the session started: close and start on the new one at the next message (not while running or agents work) (`src/chat.ts:785-793`, `src/account.ts`)
- [ ] mid-turn messages: queued until Claude takes them in (command_lifecycle started/cancelled), all queued taken in at turn end (`src/chat.ts:840-853`, `873-891`)
- [~] unsent messages kept and shown on open until the session file has them (beta: kept only in the live turn list, lost on a turn change) (`src/chat.ts:855-865`)
- [x] task_started / progress / updated / notification / background_tasks_changed
- [ ] settle: after the last agent ends, 5 s (30 s once a turn's init came) before the chat counts as finished; the last turn's time moves to that moment (`src/chat.ts:676-708`)
- [ ] lastTurn {id, ago, stopped, failed, reply} per chat, kept in the store; unseen = ended, not stopped, not working, not settling, after seen (`runner.ts:76-93`, `src/db.ts` last_turns)
- [~] a stopped turn is not an error, a failed one is (beta: preview only set on success; stopped turns counted unseen)
- [x] notes: task notices, slash command output, compaction, informational warnings
- [ ] compaction: the chains before a compaction read from the file (`src/chat.ts:235-270`)
- [ ] host: canUseTool title/description passed on; an aborted ask is cancelled on the page (`host.ts:139-150`, `src/chat.ts:1184-1188`)

## Titles, colours, archive
- [~] title = customTitle || summary || firstPrompt (claudia-file tags: the text before them, else the first file's name) || "New chat"; before the session file: first line ≤ 120 or first file's name (`runner.ts:139-153`, `src/chat.ts:771`)
- [~] rename writes the session's custom title (renameSession), ≤ 200 (`runner.ts:693`)
- [~] colour: random among the least used by non-archived chats, never ash (`src/colors.ts`, `runner.ts:55-59`)
- [x] passive (ash) remembers the colour; writing to it wakes it (`runner.ts:61-64`)
- [x] archive / unarchive; sending unarchives (`runner.ts:736`, `582`)

## Permissions and questions
- [ ] auto-allow log line `[xxxxxxxx] auto-allowed <tool>: <input ≤300>` (`src/chat.ts:1197`)
- [ ] ExitPlanMode: a permission panel (tool, title, description, detail), allow / no (`src/chat.ts:1205-1214`, `web/panels.tsx:130`)
- [x] AskUserQuestion: ask, answer text as the user's message, stop denies with interrupt

## Runner HTTP (old runner.ts / server.ts routes)
- [ ] /api/prefs GET/PATCH (hiddenForms, chatColors, notHere)
- [x] /api/github GET/PUT
- [~] /api/chats (rows over the socket in beta), /api/search, /api/chats/:id/timeline(+detail), agents (socket in beta)
- [~] /api/chats/:id/tasks/:task/output (beta: agent frame's output)
- [x] images (sent), shown, uploads POST/GET, changes (diff frame), project logo, explain, narrate (hub), files, git, clojure, calls, templates, blocks/:block/template, fork, seen (socket), PATCH chat
- [~] /api/delegate, /state, /send, /answer: shapes (`runner.ts:561-633`)
- [~] fork: settings (model, effort, form row, cols) and the kept turns' pictures and changes copied; title from the session (" (fork)" by Claude Code) (`src/fork.ts`)
- [~] explain: works for a step not yet in the session file (old: the page sent the step) (`src/explain.ts`)
- [~] Claude tokens: saved by the page process (old server.ts), not the runner; tokens dir `<token file>.d` (`src/mode.ts`, `src/profiles.ts:110-140`)

## Hub
- [x] routes and checks (`hub.ts` vs `hub/main.cljs`, `hub/ops.cljs`)
- [~] push notices: finished not for stopped turns, "Ended with an error" for failed, not while agents work, event per turn id, fresh by the turn's (settled) time (`src/hub/push.ts:70-91`)
- [~] summary: running includes chats whose agents work; since from ago (steady within 10 s) (`src/hub/push.ts:275-305`)
- [x] payload, badge, Live Activity, presence, narrate, pairing, GitHub

## Final state (after the fixes on branch parity-runner)

What Claude is told
- [x] System prompt: SYSTEM_APPEND word for word (checked by a script against src/prompt.ts), then beta's Secrets section (between Working folder and Progress bars), then (beta) the forms' data list. The forms list in the prompt is a deliberate difference: Claude Code cuts long tool descriptions (the old draw description reached Claude truncated), so the data is said there too; it lists this runner's forms less the deleted ones, so a form-row change needs no new prompt.
- [x] draw / templates / save_template / set_folder / show_image: descriptions identical, schemas identical up to key order (checked against the old code run with bun: `tools/` dump vs the start spec beta sends the host). run_with_secret kept (beta).
- [x] allowedTools: the five old ones + run_with_secret.
- [x] /api/prefs (chatColors, hiddenForms, notHere); hidden forms leave live chats' tools at once, their prompt after the turn. The page's Forms-window delete/restore and the Settings colours radio are not in beta's page (page agents).

Forms news, form row
- [x] formsSeen set at session start; news with the next message (sent to a session that ran with the old tools) and in draw results; reminder after the text; the row's note again after a runner start.
- [x] Questions-off message word for word.
- [x] Tools reach a live session through setMcp (host control); a refusal restarts it after its turn and only when no task runs; other instructions (prompt version) restart it after its turn. Tested: a form row change reached the live session without a restart.

Draw
- [x] "Picture updated: …", news, missing template, switched off, still missing, editor kept notes: old order and wording. Tested live: "Picture updated: 1 block: p (pipeline)" + screenshot image.
- [x] template name kept on html blocks (describe says `from template "x"`), height kept, html assumed for template blocks; `changed` ids and the turn sent with the scene frame.
- [x] auto-files keeps its folder when Claude leaves root out or null.

Pictures per turn
- [x] A turn's name = the last sent message's uuid (kept for Claude's own turns and after a runner restart); a page edit (editor, form row) saves into the newest picture too.

Workspace tools
- [x] set_folder text (…, empty CLAUDE.md falls back, `git status --short --branch`, 40 lines as the old split), show_image checks and texts, 5 000 000 bytes.

Chat lifecycle
- [x] Token switch: an idle session with no working agents starts again on the new token at the next message (tested with a /tmp token file: start count rose, the new token was used).
- [x] Mid-turn messages: `queued` until command_lifecycle started (tested: queued at 33.0 s, taken in at 37.7 s), cancelled → `gone`, all taken in at the turn's end; page shows them below the chat with "queued" (small page edit).
- [x] Sent messages kept until the session file has them (snapshot lays them over the history).
- [x] Settle: 5 s after the last agent, 30 s once init came; ended moves to that moment (tested: settling true when the background shell ended, Claude's own turn followed, then unseen).
- [x] lastTurn {id, ago, stopped, failed, reply}, runningFor, asks' ago, settling in rows; stopped turn: no error, not unseen (tested).
- [x] Compaction: earlier chains read from the file, a note per compaction (tested on a compacted session).
- [x] Notes: task notices, slash command + its output (stdout or stderr), compaction, warnings; status notes Thinking / Retrying… / Compacting (`status` frame; the page's working line does not show the note yet: page agents).
- [x] Host: canUseTool gets title/description; an aborted rpc cancels its ask (`cancel` event).

Titles, colours, archive
- [x] Titles from the session (custom title, summary, first prompt with claudia-file handling, "New chat"); before the file: first line ≤ 120 or first file's name. Rename = renameSession. (A title renamed in beta before this change, kept only in beta's store, is replaced by the session's title at the next runner start.)
- [x] Colour: least used among listed (non-archived) chats, never ash; chatColors "none" → no colour; wake on send, code_send and the delegate send; sending unarchives (also on the page socket now).

Permissions and questions
- [x] Auto-allow log line `[xxxxxxxx] auto-allowed <tool>: <input ≤300>` (with this SDK in bypass mode canUseTool is only reached for AskUserQuestion / ExitPlanMode / safety checks).
- [x] ExitPlanMode → permit ask, the page's PermitPanel (Deny / Allow, Details), Allow → plan approved (tested live: EnterPlanMode → ExitPlanMode → card → Allow → "User has approved your plan").

Runner HTTP, route by route (old runner.ts + server.ts → beta)
- [x] /api/me (front), /api/update-check (front), /api/claude-token(s) (front, as the old page process)
- [x] /api/prefs GET/PATCH (added)
- [x] /api/github GET/PUT
- [~] /api/chats (list) → `chats` frame on the socket (beta architecture); /api/chats/:id/seen → `seen` frame; /api/chats/:id/agents(/:agent, /steps, /git) → `agents`/`agent`/`agent_git` frames; /api/chats/:id/changes/:change → `diff` frame; /api/chats/:id/tasks/:task/output → the agent frame's `output`; /api/chats/:id/images → /sent; settings → `settings`/`picture_settings` frames; wake → PATCH color "wake". Same data, beta's wire.
- [x] /api/search (mid-turn sends and slash commands indexed now), timeline + detail, shown, uploads POST/GET (old error texts), fork, explain, delegate, send, answer, state, templates GET/DELETE, blocks/:block/template, files, git, clojure, calls
- [~] /api/projects/:name/logo → /api/project/logo?folder= (beta's, same file)
- [~] /api/narrate → /hub/narrate (beta: the hub speaks)
- [x] state: `step` "<label>: <title>", `asks` the open questions, `agents` real (was 0), `since` the turn's start.
- [x] fork: model/effort, form row and columns, the kept turns' pictures and changes; title from the session (Claude Code's " (fork)").
- [x] explain: a step not in the session file yet is explained from the live turn; "The step: <label> <title>".

Hub
- [x] Routes and checks as hub.ts (beta adds /hub/servers/:name/state, admin people/name, pairing availability).
- [x] Finished pushes: never for a stopped turn, "Ended with an error" for a failed one, not while agents work, one event per turn id, fresh by the settled time; permits as permission pushes.
- [x] Summary: running includes working agents, since from ago (steady within 10 s), archived chats out; badge = waiting + done.
- [x] Payload, badge-only pushes, Live Activity, presence, pairing, GitHub: already ported; log lines with the email as the old.
- [~] /hub/me has no version/commit (the hub's image carries no package.json).

Claude tokens on a shared server
- [x] The front answers the token calls; tokens dir `<token file>.d` (the old one: beta on destiny now sees the old Claudia's saved tokens); install.sh makes the token folder claudia:claudia-run 2750 and the runners join claudia-run (SupplementaryGroups). On the box the token file is /var/lib/claudia/claude-token, which the old install already made claudia:claudia-run 2750.
